package app.phoneledger.android.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class EnrollmentQrTest {
    private val words = List(23) { "abandon" }.plus("art").joinToString(" ")

    @Test fun enrollmentBundleRoundTripsReservedCharacters() {
        val bundle = EnrollmentBundle(
            "https://sync.example.test:8443/a%20path?fixed=yes",
            "abcdef0123456789abcdef0123456789",
            words,
        )
        assertEquals(bundle, EnrollmentQr.decode(EnrollmentQr.encode(bundle)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedQrScheme() {
        EnrollmentQr.decode("https://example.test/?words=$words")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRecoveryPhraseWithBadChecksum() {
        EnrollmentQr.decode(
            "phoneledger://enroll/v1?server=https%3A%2F%2Fexample.test&" +
                "account=abcdef0123456789abcdef0123456789&words=${List(24) { "abandon" }.joinToString("%20")}",
        )
    }
}
