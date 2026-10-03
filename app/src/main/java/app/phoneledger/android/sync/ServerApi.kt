package app.phoneledger.android.sync

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ServerStatus(val protocolVersion: Int, val instanceId: String, val maxVaultBytes: Int)
data class ServerTokens(
    val access: String,
    val refresh: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
)
data class ServerRemoteVault(val blob: ByteArray?, val revision: Long)
data class ServerUploadResult(val accepted: Boolean, val revision: Long, val rollbackRecovery: Boolean = false)

class ServerApi(
    serverUrl: String,
    private val instanceId: String? = null,
    private val accountId: String? = null,
    private val deviceId: String? = null,
    private val devicePrivateKey: ByteArray? = null,
    initialTokens: ServerTokens? = null,
    private val onTokensChanged: (ServerTokens) -> Unit = {},
) : AutoCloseable {
    private val baseUrl = "${normalizeServerUrl(serverUrl)}/v1/"
    var tokens: ServerTokens? = initialTokens
        private set

    override fun close() {
        devicePrivateKey?.fill(0)
    }

    fun status(): ServerStatus {
        val json = JSONObject(expectSuccess(request("GET", "status")).bodyText())
        val protocol = json.getInt("protocolVersion")
        require(protocol == PROTOCOL_VERSION) { "Server protocol $protocol is not supported" }
        return ServerStatus(protocol, json.getString("instanceId"), json.getInt("maxVaultBytes"))
    }

    fun createAccount(
        inviteCode: String,
        newAccountId: String,
        enrollmentVerifier: ByteArray,
        newDeviceId: String,
        deviceName: String,
        devicePublicKey: ByteArray,
    ): ServerTokens {
        val body = JSONObject()
            .put("inviteCode", inviteCode.trim())
            .put("accountId", newAccountId)
            .put("enrollmentVerifier", ServerSyncCrypto.encode(enrollmentVerifier))
            .put("device", deviceJson(newDeviceId, deviceName, devicePublicKey))
        val json = JSONObject(expectSuccess(request("POST", "accounts", body = body.toString().toByteArray())).bodyText())
        return parseTokens(json.getJSONObject("tokens")).also(::setTokens)
    }

    fun enrollDevice(
        existingAccountId: String,
        enrollmentSecret: ByteArray,
        newDeviceId: String,
        deviceName: String,
        devicePublicKey: ByteArray,
    ): ServerTokens {
        val body = JSONObject()
            .put("accountId", existingAccountId)
            .put("enrollmentSecret", ServerSyncCrypto.encode(enrollmentSecret))
            .put("device", deviceJson(newDeviceId, deviceName, devicePublicKey))
        return parseTokens(JSONObject(expectSuccess(request("POST", "devices/enroll", body = body.toString().toByteArray())).bodyText()))
            .also(::setTokens)
    }

    fun getVault(): ServerRemoteVault {
        val response = authorized("GET", "vault", accept = "application/octet-stream")
        if (response.code == HttpURLConnection.HTTP_NO_CONTENT) {
            return ServerRemoteVault(null, parseRevision(response.header("ETag")))
        }
        expectSuccess(response)
        require(response.header("X-Phone-Ledger-Format") == PROTOCOL_VERSION.toString()) { "Server returned an unsupported vault format" }
        val expectedDigest = ServerSyncCrypto.decode(response.header("X-Content-SHA256"), 32, "Vault digest")
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(response.body)
        require(MessageDigest.isEqual(expectedDigest, actualDigest)) { "Server returned a vault with an invalid digest" }
        return ServerRemoteVault(response.body, parseRevision(response.header("ETag")))
    }

    fun uploadVault(blob: ByteArray, baseRevision: Long): ServerUploadResult {
        val response = authorized(
            "PUT", "vault", body = blob, contentType = "application/octet-stream",
            headers = vaultHeaders(blob, baseRevision),
        )
        if (response.code == HttpURLConnection.HTTP_CONFLICT) {
            return ServerUploadResult(false, parseRevision(response.header("ETag")))
        }
        expectSuccess(response)
        return ServerUploadResult(
            true,
            parseRevision(response.header("ETag")),
            response.header("X-Phone-Ledger-Rollback-Recovery") == "true",
        )
    }

    fun rotateRecovery(blob: ByteArray, baseRevision: Long, enrollmentVerifier: ByteArray): ServerUploadResult {
        val headers = vaultHeaders(blob, baseRevision).toMutableMap()
        headers["X-Phone-Ledger-Enrollment-Verifier"] = ServerSyncCrypto.encode(enrollmentVerifier)
        val response = authorized(
            "PUT", "recovery", body = blob, contentType = "application/octet-stream", headers = headers,
        )
        if (response.code == HttpURLConnection.HTTP_CONFLICT) {
            return ServerUploadResult(false, parseRevision(response.header("ETag")))
        }
        expectSuccess(response)
        return ServerUploadResult(true, parseRevision(response.header("ETag")))
    }

    fun revokeCurrentDevice() {
        val id = requireNotNull(deviceId)
        expectSuccess(authorized("DELETE", "devices/$id"))
    }

    private fun authorized(
        method: String,
        endpoint: String,
        body: ByteArray? = null,
        contentType: String = "application/json",
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        var current = tokens ?: reauthenticate()
        var response = request(method, endpoint, body, current.access, contentType, accept, headers)
        if (response.code != HttpURLConnection.HTTP_UNAUTHORIZED) return response
        current = runCatching { refresh(current.refresh) }.getOrElse { reauthenticate() }
        response = request(method, endpoint, body, current.access, contentType, accept, headers)
        return response
    }

    private fun refresh(refreshToken: String): ServerTokens {
        val body = JSONObject().put("refreshToken", refreshToken).toString().toByteArray()
        return parseTokens(JSONObject(expectSuccess(request("POST", "auth/refresh", body = body)).bodyText())).also(::setTokens)
    }

    private fun reauthenticate(): ServerTokens {
        val account = requireNotNull(accountId) { "Sync account identity is missing" }
        val device = requireNotNull(deviceId) { "Sync device identity is missing" }
        val privateKey = requireNotNull(devicePrivateKey) { "Sync device key is missing" }
        val challengeRequest = JSONObject().put("accountId", account).put("deviceId", device).toString().toByteArray()
        val challenge = JSONObject(expectSuccess(request("POST", "auth/challenges", body = challengeRequest)).bodyText())
        val challengeId = challenge.getString("challengeId")
        val nonce = ServerSyncCrypto.decode(challenge.getString("nonce"), 32, "Authentication challenge")
        val signature = ServerSyncCrypto.signChallenge(
            privateKey, requireNotNull(instanceId), account, device, challengeId, nonce,
        )
        return try {
            val exchange = JSONObject().put("challengeId", challengeId)
                .put("signature", ServerSyncCrypto.encode(signature)).toString().toByteArray()
            parseTokens(JSONObject(expectSuccess(request("POST", "auth/tokens", body = exchange)).bodyText())).also(::setTokens)
        } finally {
            nonce.fill(0)
            signature.fill(0)
        }
    }

    private fun setTokens(value: ServerTokens) {
        tokens = value
        onTokensChanged(value)
    }

    private fun request(
        method: String,
        endpoint: String,
        body: ByteArray? = null,
        bearer: String? = null,
        contentType: String = "application/json",
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        require(endpoint.matches(Regex("[a-z0-9/-]+"))) { "Invalid API endpoint" }
        val connection = URL(baseUrl + endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("X-Phone-Ledger-Client", "android/$CLIENT_VERSION")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                require(body.size <= MAX_REQUEST_BYTES) { "Sync request is too large" }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { readBounded(it, if (accept == "application/octet-stream") MAX_VAULT_BYTES else MAX_JSON_BYTES) }
                ?: ByteArray(0)
            val responseHeaders = connection.headerFields.entries.mapNotNull { (key, value) ->
                key?.let { it to value }
            }.toMap()
            return HttpResponse(code, responseBody, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Sync server response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun expectSuccess(response: HttpResponse): HttpResponse {
        if (response.code in 200..299) return response
        val message = runCatching { JSONObject(response.bodyText()).optString("message") }.getOrNull().orEmpty()
        throw IllegalStateException(if (message.isBlank()) "Sync server returned HTTP ${response.code}" else message)
    }

    private fun deviceJson(id: String, name: String, publicKey: ByteArray) = JSONObject()
        .put("id", id).put("name", name.take(100)).put("publicKey", ServerSyncCrypto.encode(publicKey))

    private fun vaultHeaders(blob: ByteArray, revision: Long) = mapOf(
        "If-Match" to "\"$revision\"",
        "X-Phone-Ledger-Format" to PROTOCOL_VERSION.toString(),
        "X-Content-SHA256" to ServerSyncCrypto.encode(MessageDigest.getInstance("SHA-256").digest(blob)),
    )

    private fun parseTokens(json: JSONObject) = ServerTokens(
        json.getString("accessToken"), json.getString("refreshToken"),
        json.getLong("accessExpiresAt"), json.getLong("refreshExpiresAt"),
    )

    private fun parseRevision(etag: String): Long = etag.trim().removeSurrounding("\"").toLongOrNull()
        ?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Server returned an invalid revision")

    private data class HttpResponse(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
        fun bodyText(): String = String(body, StandardCharsets.UTF_8)
        fun header(name: String): String = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull().orEmpty()
    }

    companion object {
        fun normalizeServerUrl(raw: String): String {
            val value = raw.trim().trimEnd('/')
            val uri = try { URI(value) } catch (error: Exception) {
                throw IllegalArgumentException("Enter a valid server URL", error)
            }
            require(uri.scheme.equals("https", ignoreCase = true)) { "The sync server must use HTTPS" }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Enter an HTTPS server URL without credentials, query, or fragment"
            }
            require(!uri.path.trimEnd('/').endsWith("/v1", ignoreCase = true)) { "Enter the server root without /v1" }
            return value
        }

        private const val PROTOCOL_VERSION = 1
        private const val CLIENT_VERSION = "0.3.0"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val MAX_JSON_BYTES = 64 * 1024
        private const val MAX_VAULT_BYTES = 64 * 1024 * 1024 + 64
        private const val MAX_REQUEST_BYTES = MAX_VAULT_BYTES
    }
}
