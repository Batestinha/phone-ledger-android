package app.phoneledger.android.sync

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class EnrollmentBundle(val serverUrl: String, val accountId: String, val recoveryWords: String)

object EnrollmentQr {
    fun encode(bundle: EnrollmentBundle): String {
        require(bundle.serverUrl.length <= 2_048) { "Server URL is too long" }
        require(bundle.accountId.matches(Regex("[0-9a-f]{32}"))) { "Account ID is invalid" }
        RecoveryWords.decode(bundle.recoveryWords)
        return "phoneledger://enroll/v1?" + listOf(
            "server" to bundle.serverUrl,
            "account" to bundle.accountId,
            "words" to bundle.recoveryWords,
        ).joinToString("&") { (key, value) -> "$key=${encodeValue(value)}" }
    }

    fun decode(payload: String): EnrollmentBundle {
        require(payload.length <= MAX_PAYLOAD_LENGTH) { "Recovery QR is too large" }
        val uri = try { URI(payload) } catch (error: Exception) {
            throw IllegalArgumentException("This is not a valid Phone Ledger recovery QR", error)
        }
        require(uri.scheme == "phoneledger" && uri.host == "enroll" && uri.path == "/v1") {
            "This is not a Phone Ledger recovery QR"
        }
        val fields = LinkedHashMap<String, String>()
        uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).forEach { part ->
            val separator = part.indexOf('=')
            require(separator > 0) { "Recovery QR contains malformed data" }
            val key = decodeValue(part.substring(0, separator))
            require(key in setOf("server", "account", "words") && !fields.containsKey(key)) {
                "Recovery QR contains unexpected data"
            }
            fields[key] = decodeValue(part.substring(separator + 1))
        }
        val accountId = fields["account"].orEmpty().lowercase()
        val words = fields["words"].orEmpty()
        require(accountId.matches(Regex("[0-9a-f]{32}"))) { "Recovery QR account ID is invalid" }
        RecoveryWords.decode(words)
        return EnrollmentBundle(
            fields["server"]?.takeIf(String::isNotBlank) ?: error("Recovery QR has no server URL"),
            accountId,
            words,
        )
    }

    private fun encodeValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
    private fun decodeValue(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private const val MAX_PAYLOAD_LENGTH = 8_192
}
