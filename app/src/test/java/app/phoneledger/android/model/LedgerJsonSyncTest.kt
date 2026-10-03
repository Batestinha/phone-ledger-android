package app.phoneledger.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerJsonSyncTest {
    private val config = SyncConfig(
        serverUrl = "https://example.test",
        instanceId = "11111111-1111-4111-8111-111111111111",
        accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        recoveryRootBase64 = "secret-recovery-root",
        deviceId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        devicePrivateKeyBase64 = "secret-device-key",
        devicePublicKeyBase64 = "public-device-key",
        accessToken = "secret-access-token",
        refreshToken = "secret-refresh-token",
        accessExpiresAt = 1,
        refreshExpiresAt = 2,
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
        assertFalse(String(synced).contains("secret-recovery-root"))
        assertFalse(String(synced).contains("secret-device-key"))
        assertNull(LedgerJson.decode(synced).sync)
        assertEquals(state.phones, LedgerJson.decode(synced).phones)
    }

    @Test fun schemaTwoVaultMigratesAliasVaultSyncAndMutationStamps() {
        val schemaTwo = """
            {
              "schema": 2,
              "revision": 7,
              "phones": [{
                "id": "phone-a", "label": "Personal", "e164": "+351912345678", "region": "PT",
                "status": "ACTIVE", "favorite": true, "notes": "", "createdAt": 10,
                "updatedAt": 20, "deletedAt": null
              }],
              "targets": [], "events": [],
              "sync": {
                "serverUrl": "https://legacy.example", "username": "alice",
                "accessToken": "old-access", "refreshToken": "old-refresh",
                "payloadKeyBase64": "old-key", "deviceId": "old-device",
                "remoteRevision": 6, "lastSyncedLocalRevision": 7
              }
            }
        """.trimIndent().toByteArray()

        val migrated = LedgerJson.decode(schemaTwo)

        assertNull(migrated.sync)
        assertNotNull(migrated.legacySync)
        assertEquals("alice", migrated.legacySync?.username)
        assertEquals(20, migrated.logicalClock)
        assertEquals(20, migrated.phones.single().version.counter)
        assertTrue(migrated.phones.single().version.writerId.startsWith("legacy-"))
        assertEquals(32, migrated.installationId.length)

        val local = String(LedgerJson.encode(migrated))
        val synced = String(LedgerJson.encodeForSync(migrated))
        assertTrue(local.contains("old-refresh"))
        assertFalse(synced.contains("old-refresh"))
        assertEquals(3, org.json.JSONObject(synced).getInt("schema"))
    }
}
