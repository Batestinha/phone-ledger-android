package app.phoneledger.android.sync

import app.phoneledger.android.model.DisclosureEvent
import app.phoneledger.android.model.DisclosureMethod
import app.phoneledger.android.model.DisclosureTarget
import app.phoneledger.android.model.LedgerState
import app.phoneledger.android.model.MutationStamp
import app.phoneledger.android.model.PhoneNumberRecord
import app.phoneledger.android.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerMergerTest {
    @Test fun mergesDisjointRecordsAndKeepsNewestTombstone() {
        val localPhone = PhoneNumberRecord(id = "phone-a", label = "Personal", e164 = "+351912345678", region = "PT", updatedAt = 10, version = MutationStamp(1, "a"))
        val remotePhone = PhoneNumberRecord(id = "phone-b", label = "Work", e164 = "+14155552671", region = "US", updatedAt = 20, version = MutationStamp(2, "b"))
        val localTarget = DisclosureTarget("target", TargetKind.PERSON, "alice", "Alice", updatedAt = 10, version = MutationStamp(1, "a"))
        val live = DisclosureEvent(
            id = "event", phoneId = "phone-a", targetId = "target", method = DisclosureMethod.MANUAL,
            updatedAt = 20, version = MutationStamp(2, "a"),
        )
        val deleted = live.copy(updatedAt = 30, deletedAt = 30, version = MutationStamp(3, "b"))

        val merged = LedgerMerger.merge(
            LedgerState(listOf(localPhone), listOf(localTarget), listOf(live), revision = 3, logicalClock = 2),
            LedgerState(listOf(remotePhone), listOf(localTarget), listOf(deleted), revision = 5, logicalClock = 3),
        )

        assertEquals(listOf("phone-a", "phone-b"), merged.phones.map { it.id })
        assertNotNull(merged.events.single().deletedAt)
        assertEquals(6, merged.revision)
    }

    @Test fun logicalStampWinsDespiteWallClockSkewAndMergeConverges() {
        val earlyClockNewerMutation = PhoneNumberRecord(
            id = "same", label = "Logical winner", e164 = "+351912345678", region = "PT",
            updatedAt = 10, version = MutationStamp(9, "pixel"),
        )
        val lateClockOlderMutation = earlyClockNewerMutation.copy(
            label = "Wall-clock loser", updatedAt = 9_999_999, version = MutationStamp(8, "galaxy"),
        )
        val first = LedgerState(phones = listOf(earlyClockNewerMutation), logicalClock = 9)
        val second = LedgerState(phones = listOf(lateClockOlderMutation), logicalClock = 8)

        val left = LedgerMerger.merge(first, second)
        val right = LedgerMerger.merge(second, first)

        assertEquals("Logical winner", left.phones.single().label)
        assertEquals(left.phones, right.phones)
    }

    @Test fun equalStampTieBreakIsDeterministicAndIdempotent() {
        val stamp = MutationStamp(4, "same-writer")
        val a = PhoneNumberRecord(
            id = "same", label = "A", e164 = "+351912345678", region = "PT", version = stamp,
        )
        val b = a.copy(label = "B")
        val left = LedgerState(phones = listOf(a), logicalClock = 4)
        val right = LedgerState(phones = listOf(b), logicalClock = 4)

        val first = LedgerMerger.merge(left, right)
        val second = LedgerMerger.merge(right, left)
        assertEquals(first.phones, second.phones)
        assertEquals(first.phones, LedgerMerger.merge(first, second).phones)
    }

    @Test fun tombstonesRemainInTheReplicatedState() {
        val deleted = PhoneNumberRecord(
            id = "deleted", label = "Old", e164 = "+14155552671", region = "US",
            deletedAt = 99, version = MutationStamp(2, "pixel"),
        )
        val merged = LedgerMerger.merge(
            LedgerState(logicalClock = 1),
            LedgerState(phones = listOf(deleted), logicalClock = 2),
        )
        assertTrue(merged.phones.single().deletedAt != null)
    }
}
