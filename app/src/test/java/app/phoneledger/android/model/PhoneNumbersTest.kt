package app.phoneledger.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneNumbersTest {
    @Test fun normalizesInternationalAndRegionalNumbers() {
        assertEquals("+351912345678", PhoneNumbers.normalize("+351 912 345 678", ""))
        assertEquals("+351912345678", PhoneNumbers.normalize("912 345 678", "PT"))
        assertEquals("+442071234567", PhoneNumbers.normalize("020 7123 4567", "GB"))
        assertEquals("+390212345678", PhoneNumbers.normalize("02 1234 5678", "IT"))
    }

    @Test fun rejectsUnqualifiedOrShortNumbers() {
        assertThrows(IllegalArgumentException::class.java) { PhoneNumbers.normalize("912345678", "") }
        assertThrows(IllegalArgumentException::class.java) { PhoneNumbers.normalize("+123", "US") }
    }

    @Test fun derivesAutofillComponents() {
        val value = "+14155552671"
        assertEquals("+1", PhoneNumbers.component(value, PhoneComponent.COUNTRY_CODE))
        assertEquals("4155552671", PhoneNumbers.component(value, PhoneComponent.NATIONAL))
        assertEquals("415", PhoneNumbers.component(value, PhoneComponent.AREA_CODE))
        assertEquals("555", PhoneNumbers.component(value, PhoneComponent.LOCAL_PREFIX))
        assertEquals("2671", PhoneNumbers.component(value, PhoneComponent.LOCAL_SUFFIX))
    }
}
