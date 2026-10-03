package app.phoneledger.android.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class SyncCryptoTest {
    @Test fun matchesAliasVaultPrivateKeyAndVerifierVectors() {
        val salt = "0A0B0C0D0E0F10111213141516171819"
        val privateKey = SyncCrypto.derivePrivateKey(salt, "testuser", "AABBCCDD")
        assertEquals("ACD81DF26882B20336CF2A8CDE3CABA35BA359805FDFC4567EA7BD74E8302473", privateKey)
        assertEquals(
            "378FAC69B16F469FB21294F7C74429CD288F47E331E8BA02FFD7C36F2914472A9F2A8C69FFEA434C9F78FCA7E7E41CBBF591FFA589460F023EF3A6F7F6B84366458893C52F8A3304E2247C50BDAE13F4463281B8CDCC519DD563A926C93D9A33E08C1DE2EFB6102BD4BFFE97D9DA9A20354393FA041C8C0459D9D11907E11B75DE4F74990CD0364BA3884C697CF548E31707162D033576B96756A9C8B622332AC9631F62D170445CF33A5EF7E1BE82EC949A5F1FD4AAF1767EE861C729E348FD4209F552BEA5A2F059C64985F4DD2495896AE33315F54329192715AB27EA32B0AF56AC8991C9F708260EF3B5D263FA55B6380CDD294F272FFD1DD86116F0C06C",
            SyncCrypto.deriveVerifier(privateKey),
        )
    }

    @Test fun matchesAliasVaultSessionVector() {
        val salt = "0A0B0C0D0E0F101112131415161718191A1B1C1D1E1F202122232425262728292A2B2C2D2E2F303132333435363738393A3B3C3D3E3F"
        val privateKey = SyncCrypto.derivePrivateKey(
            salt,
            "testuser",
            "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
        )
        val session = SyncCrypto.deriveSession(
            "89697cc13c1cea1f44c5f6b3f8f0cb7ce28246c80de10ca5d4976575dbcb0318",
            "523d0e314fccaace5ad5007357b07bb2fb2c5f566be0b812cbe4ffa65adc5bdd5cd59d9ca921b7491481d2963733513968e7bea637a733665f8e9fb7a18ba613a03740eed9ea3795489659a486cd87352054ed49f0636bb2605b8d836a459151cb670d35e8377202d9e1569bf88d0c86bd83d303d8775a65867b68fc7f9a9d5d59c76c413cb1b4d33f1d5eb784d1d18a5705800729a5d566548297c3b84ec1077c4546ab3c9b159a6d6c7265cdc784f36f731fa371e14bc506a544713591579d0a6952c2539746963434f0e97a024c0e93701008e4c54b620a9259d071b88c0a4cf102eaa22732ecfcd1fd23a81ee180074db1b5cee1b3e9172f76153f8d46bc",
            salt,
            "testuser",
            privateKey,
        )
        assertEquals("AD713F5D8F520B7B9413CDD9EF6D9B5FE37F23A9B62C5E2B90D2291F8C3A9E6F", session.key)
        assertEquals("698D0DA7137A0FC4A55B49525C1312ADCD07788E8CD5FFF5BD195B3C17B6B3DF", session.proof)
    }

    @Test fun payloadEncryptionRoundTripsAndRejectsTampering() {
        val key = SyncCrypto.derivePayloadKey(ByteArray(32) { it.toByte() })
        val clear = "private ledger".toByteArray()
        val encrypted = SyncCrypto.encryptPayload(clear, key)
        assertArrayEquals(clear, SyncCrypto.decryptPayload(encrypted, key))

        val damaged = Base64.getDecoder().decode(encrypted).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) {
            SyncCrypto.decryptPayload(Base64.getEncoder().encodeToString(damaged), key)
        }
    }
}
