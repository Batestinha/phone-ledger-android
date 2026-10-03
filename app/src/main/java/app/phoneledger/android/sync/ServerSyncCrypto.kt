package app.phoneledger.android.sync

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DeviceKeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

object ServerSyncCrypto {
    private val random = SecureRandom()
    private val magic = byteArrayOf('P'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 2)
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()

    fun generateRecoveryRoot(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun generateDeviceKeyPair(): DeviceKeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return DeviceKeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun derivePayloadKey(root: ByteArray, instanceId: String, accountId: String): ByteArray =
        derive(root, instanceId, accountId, "phone-ledger/v1/payload")

    fun deriveEnrollmentSecret(root: ByteArray, instanceId: String, accountId: String): ByteArray =
        derive(root, instanceId, accountId, "phone-ledger/v1/enrollment")

    fun enrollmentVerifier(secret: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(secret)

    fun encryptPayload(plainText: ByteArray, root: ByteArray, instanceId: String, accountId: String): ByteArray {
        require(plainText.size <= MAX_PLAINTEXT_BYTES) { "Ledger is too large to sync" }
        val key = derivePayloadKey(root, instanceId, accountId)
        val iv = ByteArray(12).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(instanceId, accountId))
            magic + iv + cipher.doFinal(plainText)
        } finally {
            key.fill(0)
        }
    }

    fun decryptPayload(encrypted: ByteArray, root: ByteArray, instanceId: String, accountId: String): ByteArray {
        require(encrypted.size in (magic.size + 12 + 16)..MAX_ENCRYPTED_BYTES) { "Synced ledger has an invalid size" }
        require(encrypted.copyOfRange(0, magic.size).contentEquals(magic)) { "Server data is not a Phone Ledger v1 vault" }
        val key = derivePayloadKey(root, instanceId, accountId)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted, magic.size, 12))
            cipher.updateAAD(aad(instanceId, accountId))
            cipher.doFinal(encrypted, magic.size + 12, encrypted.size - magic.size - 12)
        } finally {
            key.fill(0)
        }
    }

    fun signChallenge(
        privateKey: ByteArray,
        instanceId: String,
        accountId: String,
        deviceId: String,
        challengeId: String,
        nonce: ByteArray,
    ): ByteArray {
        require(privateKey.size == Ed25519PrivateKeyParameters.KEY_SIZE) { "Stored device key is damaged" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        val message = authMessage(instanceId, accountId, deviceId, challengeId, nonce)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verifySignature(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519PublicKeyParameters.KEY_SIZE) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    fun authMessage(
        instanceId: String,
        accountId: String,
        deviceId: String,
        challengeId: String,
        nonce: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            listOf(
                "phone-ledger-auth-v1".toByteArray(StandardCharsets.UTF_8),
                instanceId.toByteArray(StandardCharsets.UTF_8),
                accountId.toByteArray(StandardCharsets.UTF_8),
                deviceId.toByteArray(StandardCharsets.UTF_8),
                challengeId.toByteArray(StandardCharsets.UTF_8),
                nonce,
            ).forEach { value -> data.writeInt(value.size); data.write(value) }
        }
    }.toByteArray()

    fun encode(value: ByteArray): String = base64Encoder.encodeToString(value)

    fun decode(value: String, expectedSize: Int, label: String): ByteArray {
        val decoded = try { base64Decoder.decode(value) } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$label is not valid base64url", error)
        }
        require(decoded.size == expectedSize) { "$label has an invalid size" }
        return decoded
    }

    private fun derive(root: ByteArray, instanceId: String, accountId: String, info: String): ByteArray {
        require(root.size == 32) { "Recovery key must be 256 bits" }
        require(instanceId.isNotBlank() && accountId.length == 32) { "Invalid sync account identity" }
        val salt = MessageDigest.getInstance("SHA-256").digest(
            "$instanceId\u0000$accountId".toByteArray(StandardCharsets.UTF_8),
        )
        val extract = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(root)
        }
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(extract, "HmacSHA256"))
                doFinal(info.toByteArray(StandardCharsets.UTF_8) + 1.toByte())
            }
        } finally {
            extract.fill(0)
        }
    }

    private fun aad(instanceId: String, accountId: String): ByteArray =
        "phone-ledger-vault-v1\u0000$instanceId\u0000$accountId".toByteArray(StandardCharsets.UTF_8)

    private const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
    private const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + 64
}
