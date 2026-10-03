package app.phoneledger.android.storage

import android.content.Context
import android.util.AtomicFile
import app.phoneledger.android.model.LedgerJson
import app.phoneledger.android.model.LedgerState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class OpenedVault(val salt: ByteArray, val key: ByteArray, val state: LedgerState)

internal class EncryptedLedgerStore(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve(FILE_NAME))
    private val random = SecureRandom()

    fun exists(): Boolean = file.baseFile.isFile

    fun create(password: CharArray): OpenedVault {
        check(!exists()) { "A vault already exists" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt)
        val opened = OpenedVault(salt, key, LedgerState())
        save(opened)
        return opened
    }

    fun open(password: CharArray): OpenedVault = decode(file.readFully(), password)

    fun decode(bytes: ByteArray, password: CharArray): OpenedVault {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "Not a Phone Ledger backup" }
                val salt = ByteArray(SALT_BYTES).also(input::readFully)
                val iv = ByteArray(IV_BYTES).also(input::readFully)
                val cipherTextSize = input.readInt()
                require(cipherTextSize in 17..MAX_VAULT_BYTES) { "Invalid encrypted vault size" }
                val cipherText = ByteArray(cipherTextSize).also(input::readFully)
                require(input.read() == -1) { "Unexpected trailing backup data" }
                return decrypt(salt, iv, cipherText, deriveKey(password, salt))
            }
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect master password or damaged backup", error)
        }
    }

    fun openWithKey(expectedSalt: ByteArray, key: ByteArray): OpenedVault {
        DataInputStream(ByteArrayInputStream(file.readFully())).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Not a Phone Ledger vault" }
            val salt = ByteArray(SALT_BYTES).also(input::readFully)
            require(salt.contentEquals(expectedSalt)) { "Quick-unlock key does not match this vault" }
            val iv = ByteArray(IV_BYTES).also(input::readFully)
            val cipherTextSize = input.readInt()
            require(cipherTextSize in 17..MAX_VAULT_BYTES) { "Invalid encrypted vault size" }
            val cipherText = ByteArray(cipherTextSize).also(input::readFully)
            return decrypt(salt, iv, cipherText, key.copyOf())
        }
    }

    fun save(opened: OpenedVault) {
        writeAtomically(encode(opened))
    }

    fun encode(opened: OpenedVault): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(opened.key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC + opened.salt)
        val cipherText = cipher.doFinal(LedgerJson.encode(opened.state))
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.write(MAGIC)
            it.write(opened.salt)
            it.write(iv)
            it.writeInt(cipherText.size)
            it.write(cipherText)
        }
        return output.toByteArray()
    }

    private fun writeAtomically(bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) { "Master password must be at least $MIN_PASSWORD_LENGTH characters" }
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decrypt(salt: ByteArray, iv: ByteArray, cipherText: ByteArray, key: ByteArray): OpenedVault {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(MAGIC + salt)
            return OpenedVault(salt, key, LedgerJson.decode(cipher.doFinal(cipherText)))
        } catch (error: Exception) {
            Arrays.fill(key, 0)
            throw error
        }
    }

    companion object {
        const val FILE_NAME = "phone-ledger.vault"
        const val MIN_PASSWORD_LENGTH = 10
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1)
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val PBKDF2_ITERATIONS = 310_000
        private const val MAX_VAULT_BYTES = 64 * 1024 * 1024
    }
}
