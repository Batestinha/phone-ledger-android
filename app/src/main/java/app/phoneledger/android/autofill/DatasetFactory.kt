package app.phoneledger.android.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.autofill.Dataset
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import app.phoneledger.android.R
import app.phoneledger.android.model.PhoneNumberRecord
import app.phoneledger.android.model.PhoneNumbers
import app.phoneledger.android.model.TargetDescriptor

object DatasetFactory {
    fun phone(context: Context, fields: List<PhoneField>, target: TargetDescriptor, phone: PhoneNumberRecord): Dataset {
        val presentation = presentation(context, "${phone.label}  ${phone.e164}")
        val builder = Dataset.Builder(presentation)
        fields.forEach { builder.setValue(it.id, AutofillValue.forText(PhoneNumbers.component(phone.e164, it.component))) }
        val intent = AutofillIntents.put(Intent(context, AutofillFillActivity::class.java), fields, target)
            .putExtra(AutofillIntents.PHONE_ID, phone.id)
        val pending = PendingIntent.getActivity(
            context, phone.id.hashCode() xor target.canonicalKey.hashCode(), intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        builder.setAuthentication(pending.intentSender)
        return builder.build()
    }

    fun picker(context: Context, fields: List<PhoneField>, target: TargetDescriptor, locked: Boolean): Dataset {
        val label = context.getString(if (locked) R.string.autofill_unlock else R.string.autofill_all_numbers)
        val presentation = presentation(context, label)
        val builder = Dataset.Builder(presentation)
        fields.forEach { builder.setValue(it.id, AutofillValue.forText("")) }
        val intent = AutofillIntents.put(Intent(context, NumberPickerActivity::class.java), fields, target)
        val pending = PendingIntent.getActivity(
            context, target.canonicalKey.hashCode(), intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        builder.setAuthentication(pending.intentSender)
        return builder.build()
    }

    fun filled(context: Context, fields: List<PhoneField>, phone: PhoneNumberRecord): Dataset {
        val builder = Dataset.Builder(presentation(context, phone.label))
        fields.forEach { builder.setValue(it.id, AutofillValue.forText(PhoneNumbers.component(phone.e164, it.component))) }
        return builder.build()
    }

    private fun presentation(context: Context, text: String) = RemoteViews(context.packageName, R.layout.autofill_row).apply {
        setTextViewText(R.id.autofill_text, text)
    }
}
