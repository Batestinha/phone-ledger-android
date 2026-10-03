package app.phoneledger.android.sync

import app.phoneledger.android.model.LedgerJson
import app.phoneledger.android.model.MutationStamp
import app.phoneledger.android.model.PhoneNumberRecord
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

/** Opt-in interoperability test against a disposable HTTPS server; skipped in ordinary CI. */
class StandaloneSyncLiveTest {
    @Test fun enrollsIndependentDeviceDownloadsMutatesUploadsAndRevokes() {
        val serverUrl = System.getenv("PHONE_LEDGER_E2E_SERVER").orEmpty()
        val accountId = System.getenv("PHONE_LEDGER_E2E_ACCOUNT").orEmpty()
        val words = System.getenv("PHONE_LEDGER_E2E_WORDS").orEmpty()
        assumeTrue("live sync environment is not configured", serverUrl.isNotBlank() && accountId.isNotBlank() && words.isNotBlank())

        val status = ServerApi(serverUrl).status()
        val root = RecoveryWords.decode(words)
        val keys = ServerSyncCrypto.generateDeviceKeyPair()
        val deviceId = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val enrollment = ServerSyncCrypto.deriveEnrollmentSecret(root, status.instanceId, accountId)
        val tokens = try {
            ServerApi(serverUrl).enrollDevice(
                accountId, enrollment, deviceId, "JVM second-client test", keys.publicKey,
            )
        } finally {
            enrollment.fill(0)
        }
        val api = ServerApi(serverUrl, status.instanceId, accountId, deviceId, keys.privateKey, tokens)
        try {
            val remote = api.getVault()
            val encrypted = requireNotNull(remote.blob) { "live vault is empty" }
            val clear = ServerSyncCrypto.decryptPayload(encrypted, root, status.instanceId, accountId)
            val state = try { LedgerJson.decode(clear) } finally { clear.fill(0) }
            val now = System.currentTimeMillis()
            val changed = state.copy(
                phones = state.phones + PhoneNumberRecord(
                    id = UUID.randomUUID().toString(), label = "SecondClientE2E",
                    e164 = "+351930000001", region = "PT", createdAt = now, updatedAt = now,
                    version = MutationStamp(state.logicalClock + 1, deviceId),
                ),
                revision = state.revision + 1,
                logicalClock = state.logicalClock + 1,
            )
            val changedClear = LedgerJson.encodeForSync(changed)
            val changedEncrypted = try {
                ServerSyncCrypto.encryptPayload(changedClear, root, status.instanceId, accountId)
            } finally {
                changedClear.fill(0)
            }
            val upload = api.uploadVault(changedEncrypted, remote.revision)
            assertTrue("server rejected second-client upload", upload.accepted)
            val stored = requireNotNull(api.getVault().blob)
            val storedClear = ServerSyncCrypto.decryptPayload(stored, root, status.instanceId, accountId)
            try {
                assertTrue(LedgerJson.decode(storedClear).phones.any { it.label == "SecondClientE2E" })
            } finally {
                storedClear.fill(0)
            }
        } finally {
            runCatching { api.revokeCurrentDevice() }
            api.close()
            root.fill(0)
            keys.privateKey.fill(0)
        }
    }
}
