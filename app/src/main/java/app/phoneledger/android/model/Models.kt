package app.phoneledger.android.model

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class PhoneStatus { ACTIVE, RETIRED }
enum class TargetKind { WEB_ORIGIN, ANDROID_APP, PERSON, ORGANIZATION, PLACE, OTHER }
enum class DisclosureMethod { AUTOFILL, MANUAL }
enum class OriginTrust { VERIFIED, UNVERIFIED, ORIGIN_MISSING, NOT_APPLICABLE }

data class PhoneNumberRecord(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val e164: String,
    val region: String,
    val status: PhoneStatus = PhoneStatus.ACTIVE,
    val favorite: Boolean = false,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

data class DisclosureTarget(
    val id: String,
    val kind: TargetKind,
    val canonicalKey: String,
    val displayName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
) {
    companion object {
        fun deterministicId(kind: TargetKind, key: String): String =
            UUID.nameUUIDFromBytes("${kind.name}:$key".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}

data class DisclosureEvent(
    val id: String = UUID.randomUUID().toString(),
    val phoneId: String,
    val targetId: String,
    val method: DisclosureMethod,
    val occurredAt: Long = System.currentTimeMillis(),
    val detailUri: String? = null,
    val note: String = "",
    val clientPackage: String? = null,
    val clientSignerSha256: String? = null,
    val originTrust: OriginTrust = OriginTrust.NOT_APPLICABLE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

data class SyncConfig(
    val serverUrl: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
    val payloadKeyBase64: String,
    val deviceId: String,
    val remoteRevision: Long = 0,
    val lastSyncedLocalRevision: Long = -1,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

data class LedgerState(
    val phones: List<PhoneNumberRecord> = emptyList(),
    val targets: List<DisclosureTarget> = emptyList(),
    val events: List<DisclosureEvent> = emptyList(),
    val revision: Long = 0,
    val sync: SyncConfig? = null,
)

data class TargetDescriptor(
    val kind: TargetKind,
    val canonicalKey: String,
    val displayName: String,
    val detailUri: String? = null,
    val clientPackage: String? = null,
    val clientSignerSha256: String? = null,
    val originTrust: OriginTrust = OriginTrust.NOT_APPLICABLE,
)

object LedgerJson {
    fun encode(state: LedgerState): ByteArray = encode(state, includeSyncConfig = true)

    fun encodeForSync(state: LedgerState): ByteArray = encode(state, includeSyncConfig = false)

    private fun encode(state: LedgerState, includeSyncConfig: Boolean): ByteArray {
        val root = JSONObject()
            .put("schema", 2)
            .put("revision", state.revision)
            .put("phones", JSONArray().apply { state.phones.forEach { put(phoneToJson(it)) } })
            .put("targets", JSONArray().apply { state.targets.forEach { put(targetToJson(it)) } })
            .put("events", JSONArray().apply { state.events.forEach { put(eventToJson(it)) } })
        if (includeSyncConfig) root.putNullable("sync", state.sync?.let(::syncToJson))
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): LedgerState {
        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(root.optInt("schema", 1) in 1..2) { "Unsupported vault schema" }
        return LedgerState(
            phones = root.optJSONArray("phones").mapObjects(::phoneFromJson),
            targets = root.optJSONArray("targets").mapObjects(::targetFromJson),
            events = root.optJSONArray("events").mapObjects(::eventFromJson),
            revision = root.optLong("revision", 0),
            sync = root.optJSONObject("sync")?.let(::syncFromJson),
        )
    }

    private fun phoneToJson(value: PhoneNumberRecord) = JSONObject()
        .put("id", value.id).put("label", value.label).put("e164", value.e164)
        .put("region", value.region).put("status", value.status.name)
        .put("favorite", value.favorite).put("notes", value.notes)
        .put("createdAt", value.createdAt).put("updatedAt", value.updatedAt)
        .putNullable("deletedAt", value.deletedAt)

    private fun phoneFromJson(value: JSONObject) = PhoneNumberRecord(
        id = value.getString("id"), label = value.getString("label"), e164 = value.getString("e164"),
        region = value.optString("region"), status = PhoneStatus.valueOf(value.optString("status", "ACTIVE")),
        favorite = value.optBoolean("favorite"), notes = value.optString("notes"),
        createdAt = value.getLong("createdAt"), updatedAt = value.getLong("updatedAt"),
        deletedAt = value.optNullableLong("deletedAt"),
    )

    private fun targetToJson(value: DisclosureTarget) = JSONObject()
        .put("id", value.id).put("kind", value.kind.name).put("canonicalKey", value.canonicalKey)
        .put("displayName", value.displayName).put("createdAt", value.createdAt)
        .put("updatedAt", value.updatedAt).putNullable("deletedAt", value.deletedAt)

    private fun targetFromJson(value: JSONObject) = DisclosureTarget(
        id = value.getString("id"), kind = TargetKind.valueOf(value.getString("kind")),
        canonicalKey = value.getString("canonicalKey"), displayName = value.getString("displayName"),
        createdAt = value.getLong("createdAt"), updatedAt = value.getLong("updatedAt"),
        deletedAt = value.optNullableLong("deletedAt"),
    )

    private fun eventToJson(value: DisclosureEvent) = JSONObject()
        .put("id", value.id).put("phoneId", value.phoneId).put("targetId", value.targetId)
        .put("method", value.method.name).put("occurredAt", value.occurredAt)
        .putNullable("detailUri", value.detailUri).put("note", value.note)
        .putNullable("clientPackage", value.clientPackage)
        .putNullable("clientSignerSha256", value.clientSignerSha256)
        .put("originTrust", value.originTrust.name).put("createdAt", value.createdAt)
        .put("updatedAt", value.updatedAt).putNullable("deletedAt", value.deletedAt)

    private fun eventFromJson(value: JSONObject) = DisclosureEvent(
        id = value.getString("id"), phoneId = value.getString("phoneId"), targetId = value.getString("targetId"),
        method = DisclosureMethod.valueOf(value.getString("method")), occurredAt = value.getLong("occurredAt"),
        detailUri = value.optNullableString("detailUri"), note = value.optString("note"),
        clientPackage = value.optNullableString("clientPackage"),
        clientSignerSha256 = value.optNullableString("clientSignerSha256"),
        originTrust = OriginTrust.valueOf(value.optString("originTrust", "NOT_APPLICABLE")),
        createdAt = value.getLong("createdAt"), updatedAt = value.getLong("updatedAt"),
        deletedAt = value.optNullableLong("deletedAt"),
    )

    private fun syncToJson(value: SyncConfig) = JSONObject()
        .put("serverUrl", value.serverUrl).put("username", value.username)
        .put("accessToken", value.accessToken).put("refreshToken", value.refreshToken)
        .put("payloadKeyBase64", value.payloadKeyBase64).put("deviceId", value.deviceId)
        .put("remoteRevision", value.remoteRevision)
        .put("lastSyncedLocalRevision", value.lastSyncedLocalRevision)
        .putNullable("lastSyncAt", value.lastSyncAt).putNullable("lastError", value.lastError)

    private fun syncFromJson(value: JSONObject) = SyncConfig(
        serverUrl = value.getString("serverUrl"), username = value.getString("username"),
        accessToken = value.getString("accessToken"), refreshToken = value.getString("refreshToken"),
        payloadKeyBase64 = value.getString("payloadKeyBase64"),
        deviceId = value.optString("deviceId").ifBlank { UUID.randomUUID().toString().replace("-", "") },
        remoteRevision = value.optLong("remoteRevision", 0),
        lastSyncedLocalRevision = value.optLong("lastSyncedLocalRevision", -1),
        lastSyncAt = value.optNullableLong("lastSyncAt"), lastError = value.optNullableString("lastError"),
    )

    private fun <T> JSONArray?.mapObjects(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { mapper(getJSONObject(it)) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
}
