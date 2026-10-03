package app.phoneledger.android.storage

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PinUnlock {
    private const val PREFS = "phone_ledger_pin"
    private const val SALT = "salt"
    private const val IV = "iv"
    private const val WRAPPED = "wrapped"
    private const val FAILURES = "failures"
    private const val LOCKED_UNTIL = "locked_until"
    private const val ITERATIONS = 310_000
    private const val MAX_FAILURES = 5

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(WRAPPED)

    fun enable(context: Context, pin: CharArray) {
        require(pin.size == 6 && pin.all(Char::isDigit)) { "PIN must be exactly 6 digits" }
        val (vaultSalt, vaultKey) = LedgerRepository.keyMaterial()
        val random = SecureRandom()
        val pinSalt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val pinKey = derive(pin, pinSalt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pinKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(vaultSalt)
            val wrapped = cipher.doFinal(vaultKey)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(SALT, encode(pinSalt)).putString(IV, encode(iv))
                .putString(WRAPPED, encode(vaultSalt + wrapped))
                .putInt(FAILURES, 0).putLong(LOCKED_UNTIL, 0).apply()
        } finally {
            pinKey.fill(0); vaultKey.fill(0)
        }
    }

    fun unlock(context: Context, pin: CharArray) {
        require(pin.size == 6 && pin.all(Char::isDigit)) { "Enter your 6-digit PIN" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lockedUntil = prefs.getLong(LOCKED_UNTIL, 0)
        require(System.currentTimeMillis() >= lockedUntil) { "PIN unlock is temporarily locked; use your master password" }
        try {
            val pinSalt = decode(requireNotNull(prefs.getString(SALT, null)))
            val iv = decode(requireNotNull(prefs.getString(IV, null)))
            val payload = decode(requireNotNull(prefs.getString(WRAPPED, null)))
            val vaultSalt = payload.copyOfRange(0, 16)
            val wrapped = payload.copyOfRange(16, payload.size)
            val pinKey = derive(pin, pinSalt)
            val vaultKey = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(pinKey, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(vaultSalt)
                cipher.doFinal(wrapped)
            } finally { pinKey.fill(0) }
            try { LedgerRepository.unlockWithKey(context, vaultSalt, vaultKey) }
            finally { vaultKey.fill(0) }
            prefs.edit().putInt(FAILURES, 0).putLong(LOCKED_UNTIL, 0).apply()
        } catch (error: Exception) {
            val failures = prefs.getInt(FAILURES, 0) + 1
            val lockUntil = if (failures >= MAX_FAILURES) System.currentTimeMillis() + 5 * 60_000 else 0
            prefs.edit().putInt(FAILURES, failures).putLong(LOCKED_UNTIL, lockUntil).apply()
            throw IllegalArgumentException(if (lockUntil > 0) "Too many attempts; PIN locked for 5 minutes" else "Incorrect PIN")
        }
    }

    fun disable(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
