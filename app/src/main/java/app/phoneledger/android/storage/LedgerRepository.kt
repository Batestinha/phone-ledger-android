package app.phoneledger.android.storage

import android.content.Context
import app.phoneledger.android.model.CsvPhoneRow
import app.phoneledger.android.model.DisclosureEvent
import app.phoneledger.android.model.DisclosureMethod
import app.phoneledger.android.model.DisclosureTarget
import app.phoneledger.android.model.LedgerState
import app.phoneledger.android.model.LegacySyncConfig
import app.phoneledger.android.model.MutationStamp
import app.phoneledger.android.model.PhoneNumberRecord
import app.phoneledger.android.model.PhoneNumbers
import app.phoneledger.android.model.PhoneStatus
import app.phoneledger.android.model.SyncConfig
import app.phoneledger.android.model.TargetDescriptor
import app.phoneledger.android.model.newInstallationId
import app.phoneledger.android.sync.LedgerMerger
import app.phoneledger.android.sync.SyncManager
import java.util.Arrays

data class ImportReport(val added: Int, val duplicates: Int, val errors: List<String>)
data class RemoteIntegration(val state: LedgerState, val adoptedWithoutMerge: Boolean)

object LedgerRepository {
    private val monitor = Any()
    @Volatile private var opened: OpenedVault? = null
    private lateinit var store: EncryptedLedgerStore
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!::store.isInitialized) {
            synchronized(monitor) {
                if (!::store.isInitialized) store = EncryptedLedgerStore(appContext)
            }
        }
    }

    fun isConfigured(context: Context): Boolean {
        initialize(context)
        return store.exists()
    }

    fun isUnlocked(): Boolean = opened != null

    fun create(context: Context, password: CharArray) {
        initialize(context)
        synchronized(monitor) { opened = store.create(password) }
    }

    fun unlock(context: Context, password: CharArray) {
        initialize(context)
        synchronized(monitor) {
            lockInternal()
            opened = store.open(password)
        }
    }

    internal fun unlockWithKey(context: Context, salt: ByteArray, key: ByteArray) {
        initialize(context)
        synchronized(monitor) {
            lockInternal()
            opened = store.openWithKey(salt, key)
        }
    }

    internal fun keyMaterial(): Pair<ByteArray, ByteArray> = synchronized(monitor) {
        val current = requireOpen()
        current.salt.copyOf() to current.key.copyOf()
    }

    fun lock() = synchronized(monitor) { lockInternal() }

    private fun lockInternal() {
        opened?.key?.let { Arrays.fill(it, 0) }
        opened = null
    }

    fun snapshot(): LedgerState = synchronized(monitor) { requireOpen().state.copy() }

    fun syncConfig(): SyncConfig? = synchronized(monitor) { requireOpen().state.sync?.copy() }

    fun legacySyncConfig(): LegacySyncConfig? = synchronized(monitor) { requireOpen().state.legacySync?.copy() }

    fun configureSync(config: SyncConfig) = saveMetadata { it.copy(sync = config) }

    fun clearSync() = saveMetadata { it.copy(sync = null) }

    fun clearLegacySync() = saveMetadata { it.copy(legacySync = null) }

    fun updateSyncSession(transform: (SyncConfig) -> SyncConfig) = saveMetadata { state ->
        val config = state.sync ?: return@saveMetadata state
        state.copy(sync = transform(config))
    }

    fun updateLegacySyncSession(transform: (LegacySyncConfig) -> LegacySyncConfig) = saveMetadata { state ->
        val config = state.legacySync ?: return@saveMetadata state
        state.copy(legacySync = transform(config))
    }

    fun integrateRemote(
        remote: LedgerState,
        observedLocalRevision: Long,
        observedLastSyncedRevision: Long,
        allowCleanAdoption: Boolean = true,
    ): RemoteIntegration = synchronized(monitor) {
        val current = requireOpen()
        val canAdopt = allowCleanAdoption && current.state.revision == observedLocalRevision &&
            current.state.sync?.lastSyncedLocalRevision == observedLastSyncedRevision &&
            observedLocalRevision == observedLastSyncedRevision
        val integrated = if (canAdopt) {
            LedgerMerger.validate(remote)
            remote.copy(
                logicalClock = maxOf(remote.logicalClock, current.state.logicalClock),
                installationId = current.state.installationId,
                sync = current.state.sync,
                legacySync = current.state.legacySync,
            )
        } else {
            LedgerMerger.merge(current.state, remote).copy(
                installationId = current.state.installationId,
                sync = current.state.sync,
                legacySync = current.state.legacySync,
            )
        }
        val next = current.copy(state = integrated)
        store.save(next)
        opened = next
        RemoteIntegration(integrated.copy(), canAdopt)
    }

    fun activePhones(): List<PhoneNumberRecord> = synchronized(monitor) {
        requireOpen().state.phones.filter { it.deletedAt == null && it.status == PhoneStatus.ACTIVE }
    }

    fun rankedPhones(target: TargetDescriptor): List<PhoneNumberRecord> = synchronized(monitor) {
        val state = requireOpen().state
        val targetId = DisclosureTarget.deterministicId(target.kind, target.canonicalKey)
        val uses = state.events.filter { it.deletedAt == null && it.targetId == targetId }
            .groupBy { it.phoneId }
        state.phones.filter { it.deletedAt == null && it.status == PhoneStatus.ACTIVE }
            .sortedWith(
                compareByDescending<PhoneNumberRecord> { uses[it.id]?.size ?: 0 }
                    .thenByDescending { it.favorite }
                    .thenByDescending { uses[it.id]?.maxOfOrNull(DisclosureEvent::occurredAt) ?: 0 }
                    .thenByDescending { it.updatedAt },
            )
    }

    fun addPhone(label: String, rawNumber: String, region: String, favorite: Boolean, notes: String): PhoneNumberRecord =
        mutate { state, stamp ->
            val e164 = PhoneNumbers.normalize(rawNumber, region)
            require(state.phones.none { it.deletedAt == null && it.e164 == e164 }) { "$e164 is already stored" }
            val record = PhoneNumberRecord(
                label = label.trim().ifBlank { e164 }, e164 = e164, region = region.trim().uppercase(),
                favorite = favorite, notes = notes.trim(), version = stamp,
            )
            state.copy(phones = state.phones + record) to record
        }

    fun updatePhone(id: String, label: String, favorite: Boolean, notes: String) = mutate { state, stamp ->
        val existing = state.phones.firstOrNull { it.id == id && it.deletedAt == null }
            ?: throw IllegalArgumentException("Phone number not found")
        val updated = existing.copy(
            label = label.trim().ifBlank { existing.e164 }, favorite = favorite, notes = notes.trim(),
            updatedAt = now(), version = stamp,
        )
        state.copy(phones = state.phones.map { if (it.id == id) updated else it }) to Unit
    }

    fun setPhoneRetired(id: String, retired: Boolean) = mutate { state, stamp ->
        val updated = state.phones.map {
            if (it.id == id && it.deletedAt == null) it.copy(
                status = if (retired) PhoneStatus.RETIRED else PhoneStatus.ACTIVE,
                updatedAt = now(), version = stamp,
            ) else it
        }
        state.copy(phones = updated) to Unit
    }

    fun recordDisclosure(phoneId: String, target: TargetDescriptor, method: DisclosureMethod, note: String = ""): DisclosureEvent =
        mutate { state, stamp ->
            val phone = state.phones.firstOrNull { it.id == phoneId && it.deletedAt == null }
                ?: throw IllegalArgumentException("Phone number not found")
            require(method == DisclosureMethod.MANUAL || phone.status == PhoneStatus.ACTIVE) { "Retired numbers cannot be autofilled" }
            val timestamp = now()
            val targetId = DisclosureTarget.deterministicId(target.kind, target.canonicalKey)
            val existingTarget = state.targets.firstOrNull { it.id == targetId }
            val storedTarget = existingTarget?.copy(
                displayName = target.displayName, updatedAt = timestamp, deletedAt = null, version = stamp,
            ) ?: DisclosureTarget(
                id = targetId, kind = target.kind, canonicalKey = target.canonicalKey,
                displayName = target.displayName, createdAt = timestamp, updatedAt = timestamp, version = stamp,
            )
            val event = DisclosureEvent(
                phoneId = phoneId, targetId = targetId, method = method, occurredAt = timestamp,
                detailUri = target.detailUri, note = note.trim(), clientPackage = target.clientPackage,
                clientSignerSha256 = target.clientSignerSha256, originTrust = target.originTrust,
                createdAt = timestamp, updatedAt = timestamp, version = stamp,
            )
            state.copy(
                targets = if (existingTarget == null) state.targets + storedTarget else state.targets.map { if (it.id == targetId) storedTarget else it },
                events = state.events + event,
            ) to event
        }

    fun updateEvent(id: String, detailUri: String?, note: String) = mutate { state, stamp ->
        val updated = state.events.map {
            if (it.id == id && it.deletedAt == null) it.copy(
                detailUri = detailUri?.trim()?.ifBlank { null }, note = note.trim(), updatedAt = now(), version = stamp,
            ) else it
        }
        state.copy(events = updated) to Unit
    }

    fun deleteEvent(id: String) = mutate { state, stamp ->
        val timestamp = now()
        state.copy(events = state.events.map {
            if (it.id == id && it.deletedAt == null) it.copy(deletedAt = timestamp, updatedAt = timestamp, version = stamp) else it
        }) to Unit
    }

    fun importPhones(rows: List<CsvPhoneRow>): ImportReport {
        var added = 0
        var duplicates = 0
        val errors = mutableListOf<String>()
        rows.forEachIndexed { index, row ->
            try {
                addPhone(row.label, row.number, row.region, row.favorite, row.notes)
                added++
            } catch (error: Exception) {
                if (error.message?.contains("already stored") == true) duplicates++
                else errors += "Row ${index + 2}: ${error.message ?: "invalid value"}"
            }
        }
        return ImportReport(added, duplicates, errors)
    }

    fun encryptedBackup(): ByteArray = synchronized(monitor) {
        val current = requireOpen()
        store.encode(current.copy(state = current.state.copy(sync = null, legacySync = null)))
    }

    fun restoreBackup(context: Context, bytes: ByteArray, password: CharArray) {
        initialize(context)
        synchronized(monitor) {
            val candidate = store.decode(bytes, password)
            val restored = candidate.copy(state = candidate.state.copy(
                installationId = newInstallationId(), sync = null, legacySync = null,
            ))
            store.save(restored)
            lockInternal()
            opened = restored
            PinUnlock.disable(context)
            BiometricUnlock.disable(context)
        }
    }

    private fun <T> mutate(block: (LedgerState, MutationStamp) -> Pair<LedgerState, T>): T {
        val result = synchronized(monitor) {
            val current = requireOpen()
            require(current.state.logicalClock < Long.MAX_VALUE) { "Logical clock is exhausted" }
            val stamp = MutationStamp(current.state.logicalClock + 1, current.state.installationId)
            val (changed, result) = block(current.state, stamp)
            val next = current.copy(state = changed.copy(
                revision = current.state.revision + 1,
                logicalClock = stamp.counter,
            ))
            store.save(next)
            opened = next
            result
        }
        if (::appContext.isInitialized) SyncManager.schedule(appContext)
        return result
    }

    private fun saveMetadata(block: (LedgerState) -> LedgerState) = synchronized(monitor) {
        val current = requireOpen()
        val next = current.copy(state = block(current.state))
        store.save(next)
        opened = next
    }

    private fun requireOpen(): OpenedVault = opened ?: throw IllegalStateException("Vault is locked")
    private fun now(): Long = System.currentTimeMillis()
}
