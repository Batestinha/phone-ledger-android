package app.phoneledger.android.autofill

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.phoneledger.android.model.DisclosureMethod
import app.phoneledger.android.model.PhoneNumberRecord
import app.phoneledger.android.storage.LedgerRepository
import app.phoneledger.android.storage.PinUnlock
import app.phoneledger.android.storage.BiometricUnlock

class NumberPickerActivity : Activity() {
    private var fields: List<PhoneField> = emptyList()
    private lateinit var target: app.phoneledger.android.model.TargetDescriptor
    private var allPhones: List<PhoneNumberRecord> = emptyList()
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var shownPhones: List<PhoneNumberRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fields = AutofillIntents.fields(intent).orEmpty()
        target = AutofillIntents.target(intent) ?: run { cancel(); return }
        if (fields.isEmpty()) { cancel(); return }
        LedgerRepository.initialize(this)
        when {
            LedgerRepository.isUnlocked() -> showPicker()
            !LedgerRepository.isConfigured(this) -> {
                Toast.makeText(this, "Open Phone Ledger to create your vault first", Toast.LENGTH_LONG).show()
                cancel()
            }
            else -> promptUnlock()
        }
    }

    private fun promptUnlock() {
        val input = EditText(this).apply {
            hint = "Master password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Unlock Phone Ledger")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setPositiveButton("Unlock", null)
            .setOnCancelListener { cancel() }
        if (PinUnlock.isEnabled(this) || BiometricUnlock.isEnabled(this)) builder.setNeutralButton("Quick unlock", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chars = input.text.toString().toCharArray()
                try {
                    LedgerRepository.unlock(this, chars)
                    dialog.dismiss()
                    showPicker()
                } catch (error: Exception) {
                    input.error = error.message ?: "Could not unlock"
                } finally {
                    chars.fill('\u0000')
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dialog.dismiss()
                chooseQuickUnlock()
            }
        }
        dialog.show()
    }

    private fun chooseQuickUnlock() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (BiometricUnlock.isEnabled(this)) {
            labels += "Use biometric"
            actions += {
                BiometricUnlock.unlock(this, success = { showPicker() }, failure = { toastAndPassword(it) })
            }
        }
        if (PinUnlock.isEnabled(this)) {
            labels += "Use PIN"
            actions += { promptPinUnlock() }
        }
        if (actions.size == 1) actions.single().invoke()
        else AlertDialog.Builder(this).setTitle("Quick unlock").setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("Use password") { _, _ -> promptUnlock() }.show()
    }

    private fun toastAndPassword(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        promptUnlock()
    }

    private fun promptPinUnlock() {
        val input = EditText(this).apply {
            hint = "6-digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Quick unlock").setView(input)
            .setNegativeButton("Cancel") { _, _ -> cancel() }.setPositiveButton("Unlock", null)
            .setOnCancelListener { cancel() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chars = input.text.toString().toCharArray()
                try {
                    PinUnlock.unlock(this, chars)
                    dialog.dismiss(); showPicker()
                } catch (error: Exception) { input.error = error.message }
                finally { chars.fill('\u0000') }
            }
        }
        dialog.show()
    }

    private fun showPicker() {
        allPhones = LedgerRepository.rankedPhones(target)
        if (allPhones.isEmpty()) {
            Toast.makeText(this, "No active phone numbers", Toast.LENGTH_LONG).show()
            cancel()
            return
        }
        title = "Choose a number"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Share with ${target.displayName}"
            textSize = 18f
            setPadding(0, 0, 0, dp(12))
        })
        val search = EditText(this).apply {
            hint = "Search numbers"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showFiltered("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = showFiltered(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ -> choose(shownPhones[position]) }
    }

    private fun showFiltered(query: String) {
        val needle = query.trim().lowercase()
        shownPhones = allPhones.filter { needle.isBlank() || it.label.lowercase().contains(needle) || it.e164.contains(needle) }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, shownPhones.map { "${it.label}\n${it.e164}" })
        list.adapter = adapter
    }

    private fun choose(phone: PhoneNumberRecord) {
        try {
            // The durable disclosure write deliberately happens before the Dataset is returned.
            LedgerRepository.recordDisclosure(phone.id, target, DisclosureMethod.AUTOFILL)
            val result = Intent().putExtra(
                AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                DatasetFactory.filled(this, fields, phone),
            )
            setResult(RESULT_OK, result)
            finish()
        } catch (error: Exception) {
            Toast.makeText(this, "Nothing was filled: ${error.message}", Toast.LENGTH_LONG).show()
            cancel()
        }
    }

    private fun cancel() { setResult(RESULT_CANCELED); finish() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
