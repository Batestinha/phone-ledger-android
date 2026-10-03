package app.phoneledger.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvCodecTest {
    @Test fun parsesQuotedCsv() {
        val rows = CsvCodec.parsePhones("label,number,region,favorite,notes\n\"Work, public\",+351912345678,PT,true,\"Used for forms\"\n")
        assertEquals(1, rows.size)
        assertEquals("Work, public", rows.single().label)
        assertTrue(rows.single().favorite)
    }

    @Test fun exportsEscapedValues() {
        val csv = CsvCodec.exportPhones(listOf(PhoneNumberRecord(label = "Work \"line\"", e164 = "+351912345678", region = "PT")))
        assertTrue(csv.contains("\"Work \"\"line\"\"\""))
        assertTrue(csv.startsWith("label,number,region,favorite,notes,status"))
    }
}
