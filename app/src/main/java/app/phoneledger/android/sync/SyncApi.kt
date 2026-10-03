package app.phoneledger.android.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant

data class SyncTokens(val access: String, val refresh: String)
data class LoginChallenge(
    val salt: String,
    val serverEphemeral: String,
    val encryptionType: String,
    val encryptionSettings: String,
    val srpIdentity: String?,
)
data class LoginValidation(val requiresTwoFactor: Boolean, val serverProof: String, val tokens: SyncTokens?)
data class RemoteVault(val blob: String, val revision: Long)
data class UploadResult(val accepted: Boolean, val revision: Long)

class SyncApi(
    serverUrl: String,
    private val deviceId: String,
    initialTokens: SyncTokens? = null,
    private val onTokensChanged: (SyncTokens) -> Unit = {},
) {
    private val baseUrl = "${normalizeServerUrl(serverUrl)}/v1/"
    var tokens: SyncTokens? = initialTokens
        private set

    fun register(
        username: String,
        salt: String,
        verifier: String,
        srpIdentity: String,
    ): SyncTokens {
        val body = JSONObject()
            .put("username", username)
            .put("salt", salt)
            .put("verifier", verifier)
            .put("encryptionType", SyncCrypto.ENCRYPTION_TYPE)
            .put("encryptionSettings", SyncCrypto.ENCRYPTION_SETTINGS)
            .put("srpIdentity", srpIdentity)
        return parseTokens(expectSuccess(request("POST", "Auth/register", body.toString())).body)
            .also(::setTokens)
    }

    fun initiateLogin(username: String): LoginChallenge {
        val response = expectSuccess(request("POST", "Auth/login", JSONObject().put("username", username).toString()))
        val json = JSONObject(response.body)
        return LoginChallenge(
            salt = json.getString("salt"),
            serverEphemeral = json.getString("serverEphemeral"),
            encryptionType = json.getString("encryptionType"),
            encryptionSettings = json.getString("encryptionSettings"),
            srpIdentity = json.optString("srpIdentity").ifBlank { null },
        )
    }

    fun validateLogin(
        username: String,
        clientPublic: String,
        clientProof: String,
        twoFactorCode: String?,
    ): LoginValidation {
        val body = JSONObject()
            .put("username", username)
            .put("rememberMe", true)
            .put("clientPublicEphemeral", clientPublic)
            .put("clientSessionProof", clientProof)
        val endpoint = if (twoFactorCode.isNullOrBlank()) {
            "Auth/validate"
        } else {
            require(twoFactorCode.matches(Regex("[0-9]{6}"))) { "Two-factor code must contain 6 digits" }
            body.put("code2Fa", twoFactorCode.toInt())
            "Auth/validate-2fa"
        }
        val json = JSONObject(expectSuccess(request("POST", endpoint, body.toString())).body)
        val tokenObject = json.optJSONObject("token")
        val parsed = LoginValidation(
            requiresTwoFactor = json.optBoolean("requiresTwoFactor"),
            serverProof = json.optString("serverSessionProof"),
            tokens = tokenObject?.let(::parseTokens),
        )
        parsed.tokens?.let(::setTokens)
        return parsed
    }

    fun getVault(): RemoteVault {
        val json = JSONObject(expectSuccess(authorized("GET", "Vault", null)).body)
        require(json.optInt("status", -1) == STATUS_OK) { "Server could not return the sync vault" }
        val vault = json.getJSONObject("vault")
        return RemoteVault(vault.optString("blob"), vault.getLong("currentRevisionNumber"))
    }

    fun uploadVault(username: String, blob: String, currentRevision: Long): UploadResult {
        val now = Instant.now().toString()
        val body = JSONObject()
            .put("username", username)
            .put("blob", blob)
            .put("version", PAYLOAD_VERSION)
            .put("currentRevisionNumber", currentRevision)
            .put("credentialsCount", 0)
            .put("emailAddressList", JSONArray())
            .put("createdAt", now)
            .put("updatedAt", now)
        val json = JSONObject(expectSuccess(authorized("POST", "Vault", body.toString())).body)
        val status = json.optInt("status", -1)
        require(status == STATUS_OK || status == STATUS_OUTDATED) { "Server rejected the sync vault" }
        return UploadResult(status == STATUS_OK, json.getLong("newRevisionNumber"))
    }

    fun revoke() {
        val current = tokens ?: return
        val body = JSONObject().put("token", current.access).put("refreshToken", current.refresh).toString()
        expectSuccess(request("POST", "Auth/revoke", body))
    }

    private fun authorized(method: String, endpoint: String, body: String?): HttpResponse {
        val current = tokens ?: throw IllegalStateException("Sync account is not authenticated")
        var response = request(method, endpoint, body, current.access)
        if (response.code != HttpURLConnection.HTTP_UNAUTHORIZED) return response
        val refreshed = refresh(current)
        response = request(method, endpoint, body, refreshed.access)
        return response
    }

    private fun refresh(current: SyncTokens): SyncTokens {
        val body = JSONObject().put("token", current.access).put("refreshToken", current.refresh).toString()
        val refreshed = parseTokens(expectSuccess(request("POST", "Auth/refresh", body)).body)
        setTokens(refreshed)
        return refreshed
    }

    private fun setTokens(value: SyncTokens) {
        tokens = value
        onTokensChanged(value)
    }

    private fun request(method: String, endpoint: String, body: String?, bearer: String? = null): HttpResponse {
        require(endpoint.matches(Regex("[A-Za-z0-9/-]+"))) { "Invalid API endpoint" }
        val connection = URL(baseUrl + endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-AliasVault-Client", "phoneledger-$PAYLOAD_VERSION")
            connection.setRequestProperty("X-AliasVault-AppInstanceId", deviceId)
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_REQUEST_BYTES) { "Sync request is too large" }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use(::readBounded)?.toString(StandardCharsets.UTF_8.name()).orEmpty()
            return HttpResponse(code, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArrayOutputStream {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RESPONSE_BYTES) { "Sync server response is too large" }
            output.write(buffer, 0, count)
        }
        return output
    }

    private fun expectSuccess(response: HttpResponse): HttpResponse {
        if (response.code in 200..299) return response
        val message = runCatching {
            val json = JSONObject(response.body)
            json.optString("title").ifBlank { json.optString("message") }
        }.getOrNull().orEmpty()
        throw IllegalStateException(if (message.isBlank()) "Sync server returned HTTP ${response.code}" else message)
    }

    private fun parseTokens(json: JSONObject): SyncTokens = SyncTokens(
        access = json.getString("token"),
        refresh = json.getString("refreshToken"),
    )

    private fun parseTokens(body: String): SyncTokens = parseTokens(JSONObject(body))

    private data class HttpResponse(val code: Int, val body: String)

    companion object {
        fun normalizeServerUrl(raw: String): String {
            val value = raw.trim().trimEnd('/')
            val uri = try { URI(value) } catch (error: Exception) { throw IllegalArgumentException("Enter a valid server URL", error) }
            require(uri.scheme.equals("https", ignoreCase = true)) { "The sync server must use HTTPS" }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Enter an HTTPS server URL without credentials, query, or fragment"
            }
            require(!uri.path.trimEnd('/').endsWith("/v1", ignoreCase = true)) { "Enter the API root without /v1" }
            return value
        }

        private const val PAYLOAD_VERSION = "1.0.0"
        private const val STATUS_OK = 0
        private const val STATUS_OUTDATED = 2
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REQUEST_BYTES = 90 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 90 * 1024 * 1024
    }
}
