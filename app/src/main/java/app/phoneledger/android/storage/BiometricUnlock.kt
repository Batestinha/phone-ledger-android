package app.phoneledger.android.storage

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object BiometricUnlock {
    private const val PREFS = "phone_ledger_biometric"
    private const val IV = "iv"
    private const val WRAPPED = "wrapped"
    private const val KEY_ALIAS = "phone-ledger-biometric-v1"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(WRAPPED)

    fun canEnable(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun enable(activity: Activity, success: () -> Unit, failure: (String) -> Unit) {
        try {
            disable(activity)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            val key = generator.generateKey()
            val (vaultSalt, vaultKey) = LedgerRepository.keyMaterial()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(vaultSalt)
            authenticate(activity, "Enable biometric unlock", cipher, failure) { authorized ->
                try {
                    val wrapped = authorized.doFinal(vaultKey)
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(IV, encode(authorized.iv))
                        .putString(WRAPPED, encode(vaultSalt + wrapped))
                        .apply()
                    success()
                } catch (error: Exception) {
                    disable(activity)
                    failure(error.message ?: "Could not enable biometric unlock")
                } finally {
                    vaultKey.fill(0)
                }
            }
        } catch (error: Exception) {
            disable(activity)
            failure(error.message ?: "Biometric unlock is unavailable")
        }
    }

    fun unlock(activity: Activity, success: () -> Unit, failure: (String) -> Unit) {
        try {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val payload = decode(requireNotNull(prefs.getString(WRAPPED, null)))
            val vaultSalt = payload.copyOfRange(0, 16)
            val wrapped = payload.copyOfRange(16, payload.size)
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw IllegalStateException("Biometric key is no longer available")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(requireNotNull(prefs.getString(IV, null)))))
            cipher.updateAAD(vaultSalt)
            authenticate(activity, "Unlock Phone Ledger", cipher, failure) { authorized ->
                var vaultKey: ByteArray? = null
                try {
                    vaultKey = authorized.doFinal(wrapped)
                    LedgerRepository.unlockWithKey(activity, vaultSalt, vaultKey)
                    success()
                } catch (error: Exception) {
                    failure(error.message ?: "Biometric key does not match this vault")
                } finally {
                    vaultKey?.fill(0)
                }
            }
        } catch (error: Exception) {
            failure(error.message ?: "Biometric unlock is unavailable")
        }
    }

    fun disable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try { keyStore().deleteEntry(KEY_ALIAS) } catch (_: Exception) { }
    }

    private fun authenticate(
        activity: Activity,
        title: String,
        cipher: Cipher,
        failure: (String) -> Unit,
        success: (Cipher) -> Unit,
    ) {
        val cancellation = CancellationSignal()
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Use a strong enrolled biometric")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> cancellation.cancel() }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized == null) failure("Biometric authorization did not return a key") else success(authorized)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    failure(errString.toString())
                }

                override fun onAuthenticationFailed() = Unit
            },
        )
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
