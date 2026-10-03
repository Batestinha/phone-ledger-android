package app.phoneledger.android.sync

import app.phoneledger.android.model.DisclosureEvent
import app.phoneledger.android.model.DisclosureTarget
import app.phoneledger.android.model.LedgerState
import app.phoneledger.android.model.PhoneNumberRecord

object LedgerMerger {
    fun merge(local: LedgerState, remote: LedgerState): LedgerState {
        validate(local)
        validate(remote)

        val phones = mergeById(local.phones, remote.phones, PhoneNumberRecord::id, PhoneNumberRecord::updatedAt)
        val targets = mergeById(local.targets, remote.targets, DisclosureTarget::id, DisclosureTarget::updatedAt)
        val events = mergeById(local.events, remote.events, DisclosureEvent::id, DisclosureEvent::updatedAt)
        val contentChanged = phones != local.phones.sortedBy { it.id } ||
            targets != local.targets.sortedBy { it.id } || events != local.events.sortedBy { it.id }

        val merged = LedgerState(
            phones = phones,
            targets = targets,
            events = events,
            revision = if (contentChanged) maxOf(local.revision, remote.revision) + 1 else local.revision,
            sync = local.sync,
        )
        validate(merged)
        return merged
    }

    fun validate(state: LedgerState) {
        require(state.phones.size <= 10_000) { "Synced vault contains too many phone records" }
        require(state.targets.size <= 100_000) { "Synced vault contains too many targets" }
        require(state.events.size <= 250_000) { "Synced vault contains too many events" }
        require(state.phones.map { it.id }.toSet().size == state.phones.size) { "Duplicate phone record ID" }
        require(state.targets.map { it.id }.toSet().size == state.targets.size) { "Duplicate target ID" }
        require(state.events.map { it.id }.toSet().size == state.events.size) { "Duplicate event ID" }
        state.phones.forEach {
            require(it.id.length <= 80 && it.label.length <= 500 && it.e164.length <= 32 && it.notes.length <= 20_000) {
                "Invalid phone record"
            }
        }
        state.targets.forEach {
            require(it.id.length <= 80 && it.canonicalKey.length <= 4_096 && it.displayName.length <= 4_096) {
                "Invalid disclosure target"
            }
        }
        state.events.forEach {
            require(it.id.length <= 80 && it.phoneId.length <= 80 && it.targetId.length <= 80 && it.note.length <= 20_000) {
                "Invalid disclosure event"
            }
            require((it.detailUri?.length ?: 0) <= 16_384) { "Disclosure URL is too long" }
        }
    }

    private fun <T> mergeById(
        local: List<T>,
        remote: List<T>,
        id: (T) -> String,
        updatedAt: (T) -> Long,
    ): List<T> {
        val merged = LinkedHashMap<String, T>()
        (local + remote).forEach { candidate ->
            val key = id(candidate)
            val existing = merged[key]
            if (existing == null || updatedAt(candidate) > updatedAt(existing) ||
                (updatedAt(candidate) == updatedAt(existing) && candidate.toString() > existing.toString())
            ) {
                merged[key] = candidate
            }
        }
        return merged.values.sortedBy(id)
    }
}
