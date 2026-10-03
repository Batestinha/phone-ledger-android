package app.phoneledger.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerJsonSyncTest {
    private val config = SyncConfig(
        serverUrl = "https://example.test/api",
        username = "alice",
        accessToken = "secret-access-token",
        refreshToken = "secret-refresh-token",
        payloadKeyBase64 = "secret-key",
        deviceId = "device",
    )

    @Test fun localEncodingRetainsButSyncEncodingExcludesDeviceCredentials() {
        val state = LedgerState(
            phones = listOf(PhoneNumberRecord(label = "Personal", e164 = "+351912345678", region = "PT")),
            sync = config,
        )
        val local = String(LedgerJson.encode(state))
        val synced = LedgerJson.encodeForSync(state)
        assertTrue(local.contains("secret-access-token"))
        assertFalse(String(synced).contains("secret-access-token"))
        assertNull(LedgerJson.decode(synced).sync)
        assertEquals(state.phones, LedgerJson.decode(synced).phones)
    }
}
