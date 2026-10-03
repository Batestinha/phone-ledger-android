package app.phoneledger.android.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.view.autofill.AutofillId
import app.phoneledger.android.model.OriginTrust
import app.phoneledger.android.model.PhoneComponent
import app.phoneledger.android.model.TargetDescriptor
import app.phoneledger.android.model.TargetKind
import java.security.MessageDigest

data class PhoneField(val id: AutofillId, val component: PhoneComponent)

class PhoneFieldFinder(private val structure: AssistStructure) {
    val fields = mutableListOf<PhoneField>()

    fun parse() {
        for (index in 0 until structure.windowNodeCount) parseNode(structure.getWindowNodeAt(index).rootViewNode)
    }

    fun target(context: Context): TargetDescriptor {
        val clientPackage = structure.activityComponent?.packageName
        var web: Pair<String?, String>? = null
        for (index in 0 until structure.windowNodeCount) {
            web = findWeb(structure.getWindowNodeAt(index).rootViewNode)
            if (web != null) break
        }
        val signer = clientPackage?.let { signerDigest(context, it) }
        if (web != null) {
            val (scheme, domain) = web
            val normalizedDomain = domain.lowercase().trim().trimEnd('/')
            val origin = if (scheme.isNullOrBlank()) normalizedDomain else "${scheme.lowercase()}://$normalizedDomain"
            return TargetDescriptor(
                kind = TargetKind.WEB_ORIGIN,
                canonicalKey = origin,
                displayName = origin,
                detailUri = origin,
                clientPackage = clientPackage,
                clientSignerSha256 = signer,
                originTrust = if (scheme.isNullOrBlank()) OriginTrust.ORIGIN_MISSING else OriginTrust.UNVERIFIED,
            )
        }
        val packageName = clientPackage ?: "unknown-app"
        return TargetDescriptor(
            kind = TargetKind.ANDROID_APP,
            canonicalKey = packageName,
            displayName = applicationLabel(context, packageName),
            clientPackage = clientPackage,
            clientSignerSha256 = signer,
            originTrust = OriginTrust.NOT_APPLICABLE,
        )
    }

    private fun parseNode(node: AssistStructure.ViewNode) {
        val id = node.autofillId
        if (id != null && isEditable(node)) detect(node)?.let { fields += PhoneField(id, it) }
        for (index in 0 until node.childCount) parseNode(node.getChildAt(index))
    }

    private fun detect(node: AssistStructure.ViewNode): PhoneComponent? {
        val attributes = node.htmlInfo?.attributes.orEmpty().associate { it.first.lowercase() to it.second.lowercase() }
        val autocomplete = attributes["autocomplete"].orEmpty()
        val combined = listOfNotNull(
            node.idEntry, node.hint, node.contentDescription?.toString(), attributes["name"], attributes["id"], autocomplete,
        ).joinToString(" ").lowercase()
        if (EXCLUDED.any(combined::contains)) return null

        when {
            autocomplete.contains("tel-country-code") -> return PhoneComponent.COUNTRY_CODE
            autocomplete.contains("tel-national") -> return PhoneComponent.NATIONAL
            autocomplete.contains("tel-area-code") -> return PhoneComponent.AREA_CODE
            autocomplete.contains("tel-local-prefix") -> return PhoneComponent.LOCAL_PREFIX
            autocomplete.contains("tel-local-suffix") -> return PhoneComponent.LOCAL_SUFFIX
            autocomplete.split(Regex("\\s+")).contains("tel") -> return PhoneComponent.FULL
        }
        val hints = node.autofillHints.orEmpty().joinToString(" ").lowercase()
        if (hints.contains("phone") || hints.contains("tel") || hints.contains("mobile")) return PhoneComponent.FULL
        if (attributes["type"] == "tel") return PhoneComponent.FULL
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_PHONE) return PhoneComponent.FULL
        if (PHONE_TERMS.any(combined::contains)) return PhoneComponent.FULL
        return null
    }

    private fun findWeb(node: AssistStructure.ViewNode): Pair<String?, String>? {
        if (!node.webDomain.isNullOrBlank()) return node.webScheme to node.webDomain!!
        for (index in 0 until node.childCount) findWeb(node.getChildAt(index))?.let { return it }
        return null
    }

    private fun isEditable(node: AssistStructure.ViewNode): Boolean =
        node.inputType > 0 || node.className?.contains("EditText", true) == true ||
            node.className?.contains("Input", true) == true || node.htmlInfo?.tag?.equals("input", true) == true

    @Suppress("DEPRECATION")
    private fun signerDigest(context: Context, packageName: String): String? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            certificate?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) } }
        } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private fun applicationLabel(context: Context, packageName: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            context.packageManager.getApplicationInfo(packageName, 0)
        }
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) { packageName }

    companion object {
        private val PHONE_TERMS = listOf("phone", "telephone", "mobile", "cellphone", "cell_number", "contactnumber", "contact_number")
        private val EXCLUDED = listOf("otp", "one-time", "one_time", "verification", "security code", "sms code", "pin", "fax")
    }
}
