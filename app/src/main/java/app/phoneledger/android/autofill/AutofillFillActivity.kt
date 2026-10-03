package app.phoneledger.android.autofill

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillManager
import app.phoneledger.android.model.DisclosureMethod
import app.phoneledger.android.storage.LedgerRepository

class AutofillFillActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phoneId = intent.getStringExtra(AutofillIntents.PHONE_ID)
        val fields = AutofillIntents.fields(intent)
        val target = AutofillIntents.target(intent)
        try {
            requireNotNull(phoneId)
            requireNotNull(fields)
            requireNotNull(target)
            val phone = LedgerRepository.activePhones().firstOrNull { it.id == phoneId }
                ?: throw IllegalStateException("Number is unavailable")
            LedgerRepository.recordDisclosure(phoneId, target, DisclosureMethod.AUTOFILL)
            val result = Intent().putExtra(
                AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                DatasetFactory.filled(this, fields, phone),
            )
            setResult(RESULT_OK, result)
        } catch (_: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
