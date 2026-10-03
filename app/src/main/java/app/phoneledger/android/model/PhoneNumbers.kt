package app.phoneledger.android.model

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber

object PhoneNumbers {
    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    fun normalize(raw: String, region: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Enter a phone number" }
        val normalizedInput = if (trimmed.startsWith("00")) "+${trimmed.drop(2)}" else trimmed
        val normalizedRegion = region.trim().uppercase()
        require(normalizedInput.startsWith("+") || normalizedRegion.length == 2) {
            "A 2-letter region is required when the number has no country code"
        }
        val parsed = try {
            util.parse(normalizedInput, normalizedRegion.ifBlank { "ZZ" })
        } catch (error: Exception) {
            throw IllegalArgumentException("Could not parse this phone number", error)
        }
        require(util.isValidNumber(parsed)) { "Enter a valid phone number for ${normalizedRegion.ifBlank { "its country code" }}" }
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    fun component(e164: String, component: PhoneComponent): String {
        val parsed = parseE164(e164)
        val countryCode = parsed.countryCode.toString()
        val national = util.getNationalSignificantNumber(parsed)
        val areaLength = areaLength(parsed, national)
        return when (component) {
            PhoneComponent.FULL -> e164
            PhoneComponent.COUNTRY_CODE -> "+$countryCode"
            PhoneComponent.NATIONAL -> national
            PhoneComponent.AREA_CODE -> national.take(areaLength)
            PhoneComponent.LOCAL_PREFIX -> {
                val local = national.drop(areaLength)
                local.dropLast(4).ifEmpty { local.take((local.length - 4).coerceAtLeast(0)) }
            }
            PhoneComponent.LOCAL_SUFFIX -> national.takeLast(4)
        }
    }

    private fun parseE164(value: String): PhoneNumber = try {
        util.parse(value, "ZZ")
    } catch (error: Exception) {
        throw IllegalArgumentException("Stored number is not valid E.164", error)
    }

    private fun areaLength(number: PhoneNumber, national: String): Int {
        val geographic = util.getLengthOfGeographicalAreaCode(number)
        if (geographic > 0) return geographic.coerceAtMost(national.length)
        return util.getLengthOfNationalDestinationCode(number).coerceAtMost(national.length)
    }
}

enum class PhoneComponent { FULL, COUNTRY_CODE, NATIONAL, AREA_CODE, LOCAL_PREFIX, LOCAL_SUFFIX }
