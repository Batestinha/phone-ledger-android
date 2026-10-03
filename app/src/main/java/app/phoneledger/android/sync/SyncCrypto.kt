package app.phoneledger.android.sync

import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.math.BigInteger
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SrpEphemeral(val public: String, val secret: String)
data class SrpSession(val proof: String, val key: String)

object SyncCrypto {
    const val ENCRYPTION_TYPE = "Argon2Id"
    const val ENCRYPTION_SETTINGS = "{\"DegreeOfParallelism\":1,\"MemorySize\":19456,\"Iterations\":2}"

    private val group = SRP6StandardGroups.rfc5054_2048
    private val n: BigInteger = group.getN()
    private val g: BigInteger = group.getG()
    private val random = SecureRandom()
    private val payloadMagic = byteArrayOf('P'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 1)
    private val payloadAad = "phone-ledger-sync-v1".toByteArray(StandardCharsets.UTF_8)

    fun generateSalt(): String = ByteArray(32).also(random::nextBytes).toHex()

    fun derivePasswordHash(
        password: CharArray,
        salt: String,
        encryptionType: String = ENCRYPTION_TYPE,
        encryptionSettings: String = ENCRYPTION_SETTINGS,
    ): ByteArray {
        require(encryptionType == ENCRYPTION_TYPE) { "Server uses unsupported password encryption: $encryptionType" }
        require(salt.length in 32..256 && salt.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Server returned an invalid SRP salt"
        }
        val settings = JSONObject(encryptionSettings)
        val iterations = settings.getInt("Iterations")
        val memory = settings.getInt("MemorySize")
        val parallelism = settings.getInt("DegreeOfParallelism")
        require(iterations in 1..10 && memory in 8_192..65_536 && parallelism in 1..4) {
            "Server returned unsafe Argon2 settings"
        }

        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val passwordBytes = ByteArray(encoded.remaining()).also(encoded::get)
        return try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memory)
                .withParallelism(parallelism)
                .withSalt(salt.toByteArray(StandardCharsets.UTF_8))
                .build()
            val output = ByteArray(32)
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passwordBytes, output)
            output
        } finally {
            passwordBytes.fill(0)
        }
    }

    fun derivePrivateKey(salt: String, identity: String, passwordHashHex: String): String {
        val identityHash = sha256("${identity.lowercase()}:$passwordHashHex".toByteArray(StandardCharsets.UTF_8))
        return sha256(salt.hexToBytes(), identityHash).toHex()
    }

    fun deriveVerifier(privateKey: String): String {
        val verifier = g.modPow(BigInteger(1, privateKey.hexToBytes()), n)
        return verifier.toPaddedBytes().toHex()
    }

    fun generateEphemeral(): SrpEphemeral {
        val secretBytes = ByteArray(64).also(random::nextBytes)
        val secret = BigInteger(1, secretBytes)
        val public = g.modPow(secret, n)
        return SrpEphemeral(public.toPaddedBytes().toHex(), secretBytes.toHex())
    }

    fun deriveSession(
        clientSecret: String,
        serverPublic: String,
        salt: String,
        identity: String,
        privateKey: String,
    ): SrpSession {
        val a = BigInteger(1, clientSecret.hexToBytes())
        val bPublic = BigInteger(1, serverPublic.hexToBytes())
        require(bPublic.mod(n) != BigInteger.ZERO) { "Server returned an invalid SRP challenge" }
        val x = BigInteger(1, privateKey.hexToBytes())
        val aPublic = g.modPow(a, n)
        val aBytes = aPublic.toPaddedBytes()
        val bBytes = bPublic.toPaddedBytes()
        val u = BigInteger(1, sha256(aBytes, bBytes))
        val k = BigInteger(1, sha256(n.toUnsignedBytes(), g.toPaddedBytes()))
        val base = bPublic.subtract(k.multiply(g.modPow(x, n))).mod(n)
        val premaster = base.modPow(a.add(u.multiply(x)), n)
        val key = sha256(premaster.toPaddedBytes())
        val proof = computeM1(aBytes, bBytes, salt.hexToBytes(), identity.lowercase(), key)
        return SrpSession(proof.toHex(), key.toHex())
    }

    fun verifyServerSession(clientPublic: String, session: SrpSession, serverProof: String) {
        val expected = sha256(clientPublic.hexToBytes(), session.proof.hexToBytes(), session.key.hexToBytes())
        require(MessageDigest.isEqual(expected, serverProof.hexToBytes())) { "Server failed SRP authentication" }
    }

    fun derivePayloadKey(passwordHash: ByteArray): ByteArray {
        require(passwordHash.size == 32) { "Password hash must be 256 bits" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordHash, "HmacSHA256"))
        return mac.doFinal(payloadAad)
    }

    fun encryptPayload(plainText: ByteArray, payloadKey: ByteArray): String {
        require(plainText.size <= MAX_PLAINTEXT_BYTES) { "Ledger is too large to sync" }
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(payloadAad)
        val encrypted = payloadMagic + iv + cipher.doFinal(plainText)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun decryptPayload(encoded: String, payloadKey: ByteArray): ByteArray {
        require(encoded.length <= MAX_ENCODED_BYTES) { "Synced ledger is too large" }
        val encrypted = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Server returned an invalid encrypted ledger", error)
        }
        require(encrypted.size >= payloadMagic.size + 12 + 16 && encrypted.copyOfRange(0, payloadMagic.size).contentEquals(payloadMagic)) {
            "Server data is not a Phone Ledger sync payload"
        }
        val ivStart = payloadMagic.size
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, encrypted, ivStart, 12))
        cipher.updateAAD(payloadAad)
        return cipher.doFinal(encrypted, ivStart + 12, encrypted.size - ivStart - 12)
    }

    private fun computeM1(a: ByteArray, b: ByteArray, salt: ByteArray, identity: String, key: ByteArray): ByteArray {
        val hN = sha256(n.toUnsignedBytes())
        val hG = sha256(g.toUnsignedBytes())
        val xor = ByteArray(hN.size) { hN[it].toInt().xor(hG[it].toInt()).toByte() }
        return sha256(xor, sha256(identity.toByteArray(StandardCharsets.UTF_8)), salt, a, b, key)
    }

    private fun sha256(vararg inputs: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        inputs.forEach(::update)
        digest()
    }

    private fun BigInteger.toUnsignedBytes(): ByteArray = toByteArray().let {
        if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }

    private fun BigInteger.toPaddedBytes(): ByteArray {
        val bytes = toUnsignedBytes()
        require(bytes.size <= 256) { "SRP value exceeds group size" }
        return ByteArray(256 - bytes.size) + bytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private fun String.hexToBytes(): ByteArray {
        val clean = removePrefix("0x").removePrefix("0X")
        require(clean.isNotEmpty() && clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid hexadecimal SRP value"
        }
        return ByteArray(clean.length / 2) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
    private const val MAX_ENCODED_BYTES = 90 * 1024 * 1024
}
