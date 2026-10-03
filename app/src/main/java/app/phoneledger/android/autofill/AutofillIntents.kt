package app.phoneledger.android.autofill

import android.content.Intent
import android.os.Build
import android.view.autofill.AutofillId
import app.phoneledger.android.model.OriginTrust
import app.phoneledger.android.model.PhoneComponent
import app.phoneledger.android.model.TargetDescriptor
import app.phoneledger.android.model.TargetKind

object AutofillIntents {
    const val PHONE_ID = "phone_id"
    const val IDS = "autofill_ids"
    const val COMPONENTS = "phone_components"
    private const val TARGET_KIND = "target_kind"
    private const val TARGET_KEY = "target_key"
    private const val TARGET_NAME = "target_name"
    private const val DETAIL_URI = "detail_uri"
    private const val CLIENT_PACKAGE = "client_package"
    private const val CLIENT_SIGNER = "client_signer"
    private const val ORIGIN_TRUST = "origin_trust"

    fun put(intent: Intent, fields: List<PhoneField>, target: TargetDescriptor): Intent = intent.apply {
        putExtra(IDS, fields.map { it.id }.toTypedArray())
        putExtra(COMPONENTS, fields.map { it.component.ordinal }.toIntArray())
        putExtra(TARGET_KIND, target.kind.name)
        putExtra(TARGET_KEY, target.canonicalKey)
        putExtra(TARGET_NAME, target.displayName)
        putExtra(DETAIL_URI, target.detailUri)
        putExtra(CLIENT_PACKAGE, target.clientPackage)
        putExtra(CLIENT_SIGNER, target.clientSignerSha256)
        putExtra(ORIGIN_TRUST, target.originTrust.name)
    }

    @Suppress("DEPRECATION")
    fun fields(intent: Intent): List<PhoneField>? {
        val ids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(IDS, AutofillId::class.java)
        } else {
            intent.getParcelableArrayExtra(IDS)?.mapNotNull { it as? AutofillId }?.toTypedArray()
        } ?: return null
        val components = intent.getIntArrayExtra(COMPONENTS) ?: return null
        if (ids.size != components.size) return null
        return ids.mapIndexed { index, id -> PhoneField(id, PhoneComponent.entries.getOrElse(components[index]) { PhoneComponent.FULL }) }
    }

    fun target(intent: Intent): TargetDescriptor? {
        return try {
            val kind = intent.getStringExtra(TARGET_KIND) ?: return null
            val key = intent.getStringExtra(TARGET_KEY) ?: return null
            val name = intent.getStringExtra(TARGET_NAME) ?: return null
            TargetDescriptor(
                kind = TargetKind.valueOf(kind),
                canonicalKey = key,
                displayName = name,
                detailUri = intent.getStringExtra(DETAIL_URI),
                clientPackage = intent.getStringExtra(CLIENT_PACKAGE),
                clientSignerSha256 = intent.getStringExtra(CLIENT_SIGNER),
                originTrust = OriginTrust.valueOf(intent.getStringExtra(ORIGIN_TRUST) ?: OriginTrust.NOT_APPLICABLE.name),
            )
        } catch (_: Exception) { null }
    }
}
