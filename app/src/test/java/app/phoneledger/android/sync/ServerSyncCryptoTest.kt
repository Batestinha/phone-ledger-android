package app.phoneledger.android.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ServerSyncCryptoTest {
    private val instanceId = "11111111-1111-4111-8111-111111111111"
    private val accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    @Test fun recoveryWordsMatchBip39VectorAndRoundTrip() {
        val root = ByteArray(32)
        val expected = List(23) { "abandon" }.plus("art").joinToString(" ")

        assertEquals(expected, RecoveryWords.encode(root))
        assertArrayEquals(root, RecoveryWords.decode("  ${expected.uppercase().replace(" ", "  ")}  "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun recoveryWordsRejectInvalidChecksum() {
        RecoveryWords.decode(List(24) { "abandon" }.joinToString(" "))
    }

    @Test fun payloadEncryptionRoundTripsAndAuthenticatesContext() {
        val root = ByteArray(32) { it.toByte() }
        val clear = "private ledger".toByteArray()
        val encrypted = ServerSyncCrypto.encryptPayload(clear, root, instanceId, accountId)

        assertFalse(encrypted.contentEquals(clear))
        assertArrayEquals(clear, ServerSyncCrypto.decryptPayload(encrypted, root, instanceId, accountId))
        val tampered = encrypted.clone().also { it[it.lastIndex] = (it.last() xor 1) }
        assertTrue(runCatching {
            ServerSyncCrypto.decryptPayload(tampered, root, instanceId, accountId)
        }.isFailure)
        assertTrue(runCatching {
            ServerSyncCrypto.decryptPayload(encrypted, root, "different-instance", accountId)
        }.isFailure)
    }

    @Test fun separatedKeysAndEd25519SignaturesWork() {
        val root = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals(
            ServerSyncCrypto.encode(ServerSyncCrypto.derivePayloadKey(root, instanceId, accountId)),
            ServerSyncCrypto.encode(ServerSyncCrypto.deriveEnrollmentSecret(root, instanceId, accountId)),
        )

        val pair = ServerSyncCrypto.generateDeviceKeyPair()
        val message = ServerSyncCrypto.authMessage(instanceId, accountId, "b".repeat(32), "challenge", byteArrayOf(1, 2))
        val signature = ServerSyncCrypto.signChallenge(
            pair.privateKey, instanceId, accountId, "b".repeat(32), "challenge", byteArrayOf(1, 2),
        )
        assertTrue(ServerSyncCrypto.verifySignature(pair.publicKey, message, signature))
        assertFalse(ServerSyncCrypto.verifySignature(pair.publicKey, message + 3, signature))
    }

    @Test fun authenticationMessageHasStableCrossLanguageEncoding() {
        val message = ServerSyncCrypto.authMessage(
            "inst", "a".repeat(32), "b".repeat(32), "challenge", byteArrayOf(0, 1, 2, 3),
        )
        assertEquals(
            "AAAAFHBob25lLWxlZGdlci1hdXRoLXYxAAAABGluc3QAAAAgYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAAAAgYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmIAAAAJY2hhbGxlbmdlAAAABAABAgM",
            Base64.getUrlEncoder().withoutPadding().encodeToString(message),
        )
    }

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}
