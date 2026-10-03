package app.phoneledger.android.model

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class PhoneStatus { ACTIVE, RETIRED }
enum class TargetKind { WEB_ORIGIN, ANDROID_APP, PERSON, ORGANIZATION, PLACE, OTHER }
enum class DisclosureMethod { AUTOFILL, MANUAL }
enum class OriginTrust { VERIFIED, UNVERIFIED, ORIGIN_MISSING, NOT_APPLICABLE }

data class MutationStamp(val counter: Long, val writerId: String) : Comparable<MutationStamp> {
    override fun compareTo(other: MutationStamp): Int =
        compareValuesBy(this, other, MutationStamp::counter, MutationStamp::writerId)
}

data class PhoneNumberRecord(
    val id: String = UUID.randomUUID().toString(), val label: String, val e164: String, val region: String,
    val status: PhoneStatus = PhoneStatus.ACTIVE, val favorite: Boolean = false, val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt, val deletedAt: Long? = null,
    val version: MutationStamp = MutationStamp(updatedAt.coerceAtLeast(0), LEGACY_WRITER),
)

data class DisclosureTarget(
    val id: String, val kind: TargetKind, val canonicalKey: String, val displayName: String,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt, val deletedAt: Long? = null,
    val version: MutationStamp = MutationStamp(updatedAt.coerceAtLeast(0), LEGACY_WRITER),
) {
    companion object {
        fun deterministicId(kind: TargetKind, key: String): String =
            UUID.nameUUIDFromBytes("${kind.name}:$key".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}

data class DisclosureEvent(
    val id: String = UUID.randomUUID().toString(), val phoneId: String, val targetId: String,
    val method: DisclosureMethod, val occurredAt: Long = System.currentTimeMillis(), val detailUri: String? = null,
    val note: String = "", val clientPackage: String? = null, val clientSignerSha256: String? = null,
    val originTrust: OriginTrust = OriginTrust.NOT_APPLICABLE,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt, val deletedAt: Long? = null,
    val version: MutationStamp = MutationStamp(updatedAt.coerceAtLeast(0), LEGACY_WRITER),
)

data class SyncConfig(
    val serverUrl: String, val instanceId: String, val accountId: String, val recoveryRootBase64: String,
    val deviceId: String, val devicePrivateKeyBase64: String, val devicePublicKeyBase64: String,
    val accessToken: String, val refreshToken: String, val accessExpiresAt: Long, val refreshExpiresAt: Long,
    val remoteRevision: Long = 0, val lastSyncedLocalRevision: Long = -1,
    val lastSyncAt: Long? = null, val lastError: String? = null,
    val pendingRecoveryRootBase64: String? = null,
)

data class LegacySyncConfig(
    val serverUrl: String, val username: String, val accessToken: String, val refreshToken: String,
    val payloadKeyBase64: String, val deviceId: String, val remoteRevision: Long = 0,
    val lastSyncedLocalRevision: Long = -1, val lastSyncAt: Long? = null, val lastError: String? = null,
)

data class LedgerState(
    val phones: List<PhoneNumberRecord> = emptyList(), val targets: List<DisclosureTarget> = emptyList(),
    val events: List<DisclosureEvent> = emptyList(), val revision: Long = 0, val logicalClock: Long = 0,
    val installationId: String = newInstallationId(), val sync: SyncConfig? = null,
    val legacySync: LegacySyncConfig? = null,
)

data class TargetDescriptor(
    val kind: TargetKind, val canonicalKey: String, val displayName: String, val detailUri: String? = null,
    val clientPackage: String? = null, val clientSignerSha256: String? = null,
    val originTrust: OriginTrust = OriginTrust.NOT_APPLICABLE,
)

fun newInstallationId(): String = UUID.randomUUID().toString().replace("-", "")

object LedgerJson {
    fun encode(state: LedgerState): ByteArray = encode(state, includeLocalMetadata = true)
    fun encodeForSync(state: LedgerState): ByteArray = encode(state, includeLocalMetadata = false)

    private fun encode(state: LedgerState, includeLocalMetadata: Boolean): ByteArray {
        val root = JSONObject().put("schema", SCHEMA).put("revision", state.revision)
            .put("logicalClock", state.logicalClock)
            .put("phones", JSONArray().apply { state.phones.forEach { put(phoneToJson(it)) } })
            .put("targets", JSONArray().apply { state.targets.forEach { put(targetToJson(it)) } })
            .put("events", JSONArray().apply { state.events.forEach { put(eventToJson(it)) } })
        if (includeLocalMetadata) {
            root.put("installationId", state.installationId)
            root.putNullable("sync", state.sync?.let(::syncToJson))
            root.putNullable("legacySync", state.legacySync?.let(::legacySyncToJson))
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): LedgerState {
        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        val schema = root.optInt("schema", 1)
        require(schema in 1..SCHEMA) { "Unsupported vault schema" }
        val phones = root.optJSONArray("phones").mapObjects { phoneFromJson(it, schema) }
        val targets = root.optJSONArray("targets").mapObjects { targetFromJson(it, schema) }
        val events = root.optJSONArray("events").mapObjects { eventFromJson(it, schema) }
        val observedClock = (phones.map { it.version.counter } + targets.map { it.version.counter } +
            events.map { it.version.counter }).maxOrNull() ?: 0
        val oldSync = if (schema <= 2) root.optJSONObject("sync")?.let(::legacySyncFromJson) else null
        return LedgerState(
            phones, targets, events, root.optLong("revision", 0),
            maxOf(root.optLong("logicalClock", 0), observedClock),
            root.optString("installationId").takeIf(::validInstallationId) ?: newInstallationId(),
            if (schema >= 3) root.optJSONObject("sync")?.let(::syncFromJson) else null,
            oldSync ?: root.optJSONObject("legacySync")?.let(::legacySyncFromJson),
        )
    }

    private fun phoneToJson(value: PhoneNumberRecord) = JSONObject()
        .put("id", value.id).put("label", value.label).put("e164", value.e164).put("region", value.region)
        .put("status", value.status.name).put("favorite", value.favorite).put("notes", value.notes)
        .put("createdAt", value.createdAt).put("updatedAt", value.updatedAt)
        .putNullable("deletedAt", value.deletedAt).putStamp(value.version)

    private fun phoneFromJson(value: JSONObject, schema: Int): PhoneNumberRecord {
        val updatedAt = value.getLong("updatedAt")
        return PhoneNumberRecord(
            value.getString("id"), value.getString("label"), value.getString("e164"), value.optString("region"),
            PhoneStatus.valueOf(value.optString("status", "ACTIVE")), value.optBoolean("favorite"),
            value.optString("notes"), value.getLong("createdAt"), updatedAt, value.optNullableLong("deletedAt"),
            value.readStamp(schema, updatedAt),
        )
    }

    private fun targetToJson(value: DisclosureTarget) = JSONObject()
        .put("id", value.id).put("kind", value.kind.name).put("canonicalKey", value.canonicalKey)
        .put("displayName", value.displayName).put("createdAt", value.createdAt)
        .put("updatedAt", value.updatedAt).putNullable("deletedAt", value.deletedAt).putStamp(value.version)

    private fun targetFromJson(value: JSONObject, schema: Int): DisclosureTarget {
        val updatedAt = value.getLong("updatedAt")
        return DisclosureTarget(
            value.getString("id"), TargetKind.valueOf(value.getString("kind")), value.getString("canonicalKey"),
            value.getString("displayName"), value.getLong("createdAt"), updatedAt,
            value.optNullableLong("deletedAt"), value.readStamp(schema, updatedAt),
        )
    }

    private fun eventToJson(value: DisclosureEvent) = JSONObject()
        .put("id", value.id).put("phoneId", value.phoneId).put("targetId", value.targetId)
        .put("method", value.method.name).put("occurredAt", value.occurredAt)
        .putNullable("detailUri", value.detailUri).put("note", value.note)
        .putNullable("clientPackage", value.clientPackage).putNullable("clientSignerSha256", value.clientSignerSha256)
        .put("originTrust", value.originTrust.name).put("createdAt", value.createdAt)
        .put("updatedAt", value.updatedAt).putNullable("deletedAt", value.deletedAt).putStamp(value.version)

    private fun eventFromJson(value: JSONObject, schema: Int): DisclosureEvent {
        val updatedAt = value.getLong("updatedAt")
        return DisclosureEvent(
            value.getString("id"), value.getString("phoneId"), value.getString("targetId"),
            DisclosureMethod.valueOf(value.getString("method")), value.getLong("occurredAt"),
            value.optNullableString("detailUri"), value.optString("note"), value.optNullableString("clientPackage"),
            value.optNullableString("clientSignerSha256"),
            OriginTrust.valueOf(value.optString("originTrust", "NOT_APPLICABLE")), value.getLong("createdAt"), updatedAt,
            value.optNullableLong("deletedAt"), value.readStamp(schema, updatedAt),
        )
    }

    private fun JSONObject.putStamp(stamp: MutationStamp) =
        put("versionCounter", stamp.counter).put("versionWriter", stamp.writerId)

    private fun JSONObject.readStamp(schema: Int, updatedAt: Long): MutationStamp = if (schema >= 3) {
        MutationStamp(getLong("versionCounter"), getString("versionWriter"))
    } else {
        val digest = MessageDigest.getInstance("SHA-256").digest(toString().toByteArray(StandardCharsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
        MutationStamp(updatedAt.coerceAtLeast(0), "legacy-$digest")
    }

    private fun syncToJson(value: SyncConfig) = JSONObject()
        .put("protocol", 1).put("serverUrl", value.serverUrl).put("instanceId", value.instanceId)
        .put("accountId", value.accountId).put("recoveryRootBase64", value.recoveryRootBase64)
        .put("deviceId", value.deviceId).put("devicePrivateKeyBase64", value.devicePrivateKeyBase64)
        .put("devicePublicKeyBase64", value.devicePublicKeyBase64)
        .put("accessToken", value.accessToken).put("refreshToken", value.refreshToken)
        .put("accessExpiresAt", value.accessExpiresAt).put("refreshExpiresAt", value.refreshExpiresAt)
        .put("remoteRevision", value.remoteRevision).put("lastSyncedLocalRevision", value.lastSyncedLocalRevision)
        .putNullable("lastSyncAt", value.lastSyncAt).putNullable("lastError", value.lastError)
        .putNullable("pendingRecoveryRootBase64", value.pendingRecoveryRootBase64)

    private fun syncFromJson(value: JSONObject) = SyncConfig(
        value.getString("serverUrl"), value.getString("instanceId"), value.getString("accountId"),
        value.getString("recoveryRootBase64"), value.getString("deviceId"),
        value.getString("devicePrivateKeyBase64"), value.getString("devicePublicKeyBase64"),
        value.getString("accessToken"), value.getString("refreshToken"), value.optLong("accessExpiresAt"),
        value.optLong("refreshExpiresAt"), value.optLong("remoteRevision", 0),
        value.optLong("lastSyncedLocalRevision", -1), value.optNullableLong("lastSyncAt"),
        value.optNullableString("lastError"), value.optNullableString("pendingRecoveryRootBase64"),
    )

    private fun legacySyncToJson(value: LegacySyncConfig) = JSONObject()
        .put("serverUrl", value.serverUrl).put("username", value.username)
        .put("accessToken", value.accessToken).put("refreshToken", value.refreshToken)
        .put("payloadKeyBase64", value.payloadKeyBase64).put("deviceId", value.deviceId)
        .put("remoteRevision", value.remoteRevision).put("lastSyncedLocalRevision", value.lastSyncedLocalRevision)
        .putNullable("lastSyncAt", value.lastSyncAt).putNullable("lastError", value.lastError)

    private fun legacySyncFromJson(value: JSONObject) = LegacySyncConfig(
        value.getString("serverUrl"), value.getString("username"), value.getString("accessToken"),
        value.getString("refreshToken"), value.getString("payloadKeyBase64"),
        value.optString("deviceId").ifBlank(::newInstallationId), value.optLong("remoteRevision", 0),
        value.optLong("lastSyncedLocalRevision", -1), value.optNullableLong("lastSyncAt"),
        value.optNullableString("lastError"),
    )

    private fun validInstallationId(value: String) =
        value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun <T> JSONArray?.mapObjects(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { mapper(getJSONObject(it)) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)

    private const val SCHEMA = 3
}

private const val LEGACY_WRITER = "legacy"
