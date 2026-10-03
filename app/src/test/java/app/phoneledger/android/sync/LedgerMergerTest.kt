package app.phoneledger.android.sync

import app.phoneledger.android.model.DisclosureEvent
import app.phoneledger.android.model.DisclosureMethod
import app.phoneledger.android.model.DisclosureTarget
import app.phoneledger.android.model.LedgerState
import app.phoneledger.android.model.PhoneNumberRecord
import app.phoneledger.android.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LedgerMergerTest {
    @Test fun mergesDisjointRecordsAndKeepsNewestTombstone() {
        val localPhone = PhoneNumberRecord(id = "phone-a", label = "Personal", e164 = "+351912345678", region = "PT", updatedAt = 10)
        val remotePhone = PhoneNumberRecord(id = "phone-b", label = "Work", e164 = "+14155552671", region = "US", updatedAt = 20)
        val localTarget = DisclosureTarget("target", TargetKind.PERSON, "alice", "Alice", updatedAt = 10)
        val live = DisclosureEvent(
            id = "event", phoneId = "phone-a", targetId = "target", method = DisclosureMethod.MANUAL,
            updatedAt = 20,
        )
        val deleted = live.copy(updatedAt = 30, deletedAt = 30)

        val merged = LedgerMerger.merge(
            LedgerState(listOf(localPhone), listOf(localTarget), listOf(live), revision = 3),
            LedgerState(listOf(remotePhone), listOf(localTarget), listOf(deleted), revision = 5),
        )

        assertEquals(listOf("phone-a", "phone-b"), merged.phones.map { it.id })
        assertNotNull(merged.events.single().deletedAt)
        assertEquals(6, merged.revision)
    }
}
