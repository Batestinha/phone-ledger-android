package app.phoneledger.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.phoneledger.android.model.CsvCodec
import app.phoneledger.android.model.DisclosureEvent
import app.phoneledger.android.model.DisclosureMethod
import app.phoneledger.android.model.LedgerState
import app.phoneledger.android.model.OriginTrust
import app.phoneledger.android.model.PhoneNumberRecord
import app.phoneledger.android.model.PhoneStatus
import app.phoneledger.android.model.TargetDescriptor
import app.phoneledger.android.model.TargetKind
import app.phoneledger.android.storage.LedgerRepository
import app.phoneledger.android.storage.PinUnlock
import app.phoneledger.android.storage.BiometricUnlock
import app.phoneledger.android.sync.EnrollmentBundle
import app.phoneledger.android.sync.EnrollmentQr
import app.phoneledger.android.sync.SyncManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private enum class Tab { NUMBERS, LEDGER, SETTINGS }
    private var currentTab = Tab.NUMBERS
    private lateinit var titleView: TextView
    private lateinit var actionButton: Button
    private lateinit var list: ListView
    private var pendingExport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedgerRepository.initialize(this)
        if (LedgerRepository.isUnlocked()) renderShell() else promptVaultAccess()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized && LedgerRepository.isUnlocked()) {
            renderTab()
            SyncManager.schedule(this)
        }
    }

    private fun promptVaultAccess() {
        val creating = !LedgerRepository.isConfigured(this)
        val wrapper = vertical(20)
        val password = passwordInput(if (creating) "New master password (10+ characters)" else "Master password")
        wrapper.addView(password)
        val confirmation = if (creating) passwordInput("Confirm master password").also(wrapper::addView) else null
        val builder = AlertDialog.Builder(this)
            .setTitle(if (creating) "Create your local vault" else "Unlock Phone Ledger")
            .setMessage(if (creating) "Your numbers and disclosure ledger are encrypted on this device. Optional self-hosted sync remains off until you configure it." else null)
            .setView(wrapper)
            .setCancelable(false)
            .setPositiveButton(if (creating) "Create vault" else "Unlock", null)
            .setNegativeButton("Close") { _, _ -> finish() }
        if (!creating && (PinUnlock.isEnabled(this) || BiometricUnlock.isEnabled(this))) builder.setNeutralButton("Quick unlock", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = password.text.toString().toCharArray()
                val second = confirmation?.text?.toString()?.toCharArray()
                try {
                    if (creating) {
                        require(second != null && first.contentEquals(second)) { "Passwords do not match" }
                        LedgerRepository.create(this, first)
                    } else {
                        LedgerRepository.unlock(this, first)
                    }
                    dialog.dismiss()
                    renderShell()
                } catch (error: Exception) {
                    password.error = error.message ?: "Could not open vault"
                } finally {
                    first.fill('\u0000')
                    second?.fill('\u0000')
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
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (BiometricUnlock.isEnabled(this)) {
            options += "Use biometric"
            actions += { promptBiometricUnlock() }
        }
        if (PinUnlock.isEnabled(this)) {
            options += "Use PIN"
            actions += { promptPinUnlock() }
        }
        if (actions.size == 1) actions.single().invoke()
        else AlertDialog.Builder(this).setTitle("Quick unlock").setItems(options.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("Use password") { _, _ -> promptVaultAccess() }.show()
    }

    private fun promptBiometricUnlock() {
        BiometricUnlock.unlock(
            this,
            success = { renderShell() },
            failure = { message -> toast(message); promptVaultAccess() },
        )
    }

    private fun promptPinUnlock() {
        val pin = input("6-digit PIN", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this).setTitle("Quick unlock").setView(pin).setCancelable(false)
            .setNegativeButton("Use password") { _, _ -> promptVaultAccess() }
            .setPositiveButton("Unlock", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chars = pin.text.toString().toCharArray()
                try {
                    PinUnlock.unlock(this, chars)
                    dialog.dismiss(); renderShell()
                } catch (error: Exception) { pin.error = error.message }
                finally { chars.fill('\u0000') }
            }
        }
        dialog.show()
    }

    private fun renderShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
            setBackgroundColor(0xFFF5F7FB.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(16) + bars.left, dp(18) + bars.top, dp(16) + bars.right, dp(10) + bars.bottom)
                insets
            }
        }
        titleView = TextView(this).apply { textSize = 25f; setTextColor(0xFF172033.toInt()) }
        root.addView(titleView)
        actionButton = Button(this)
        root.addView(actionButton, matchWrap())
        list = ListView(this).apply {
            dividerHeight = dp(1)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(Tab.NUMBERS to "Numbers", Tab.LEDGER to "Ledger", Tab.SETTINGS to "Settings").forEach { (tab, label) ->
            tabs.addView(Button(this).apply {
                text = label
                setOnClickListener { currentTab = tab; renderTab() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)
        setContentView(root)
        renderTab()
        SyncManager.schedule(this)
    }

    private fun renderTab() {
        when (currentTab) {
            Tab.NUMBERS -> renderNumbers()
            Tab.LEDGER -> renderLedger()
            Tab.SETTINGS -> renderSettings()
        }
    }

    private fun renderNumbers() {
        titleView.text = "Your phone numbers"
        actionButton.visibility = View.VISIBLE
        actionButton.text = "Add number"
        actionButton.setOnClickListener { showPhoneDialog() }
        val phones = LedgerRepository.snapshot().phones.filter { it.deletedAt == null }
            .sortedWith(compareBy<PhoneNumberRecord> { it.status }.thenByDescending { it.favorite }.thenBy { it.label.lowercase() })
        list.adapter = rows(phones.map {
            val marker = if (it.favorite) "★ " else ""
            val retired = if (it.status == PhoneStatus.RETIRED) "  · retired" else ""
            "$marker${it.label}\n${it.e164}$retired"
        })
        list.setOnItemClickListener { _, _, position, _ -> showPhoneDialog(phones[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val phone = phones[position]
            val retire = phone.status == PhoneStatus.ACTIVE
            AlertDialog.Builder(this)
                .setTitle(if (retire) "Retire ${phone.label}?" else "Restore ${phone.label}?")
                .setMessage(if (retire) "It will disappear from autofill, but its disclosure history is preserved." else "It will be available for autofill again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(if (retire) "Retire" else "Restore") { _, _ ->
                    runAction { LedgerRepository.setPhoneRetired(phone.id, retire); renderNumbers() }
                }.show()
            true
        }
    }

    private fun showPhoneDialog(existing: PhoneNumberRecord? = null) {
        val layout = vertical(20)
        val label = input("Label", existing?.label.orEmpty())
        val number = input("Number, preferably +E.164", existing?.e164.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            isEnabled = existing == null
        }
        val region = input("2-letter region (e.g. PT, US)", existing?.region.orEmpty()).apply { isEnabled = existing == null }
        val favorite = CheckBox(this).apply { text = "Favorite"; isChecked = existing?.favorite == true }
        val notes = input("Notes", existing?.notes.orEmpty())
        listOf(label, number, region, favorite, notes).forEach(layout::addView)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add phone number" else "Edit phone number")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    if (existing == null) LedgerRepository.addPhone(label.text.toString(), number.text.toString(), region.text.toString(), favorite.isChecked, notes.text.toString())
                    else LedgerRepository.updatePhone(existing.id, label.text.toString(), favorite.isChecked, notes.text.toString())
                    dialog.dismiss(); renderNumbers()
                } catch (error: Exception) { number.error = error.message }
            }
        }
        dialog.show()
    }

    private fun renderLedger() {
        val state = LedgerRepository.snapshot()
        val events = state.events.filter { it.deletedAt == null }.sortedByDescending { it.occurredAt }
        val phones = state.phones.associateBy { it.id }
        val targets = state.targets.associateBy { it.id }
        titleView.text = "Disclosure ledger · ${events.size} events"
        actionButton.visibility = View.VISIBLE
        actionButton.text = "Record in-person / manual disclosure"
        actionButton.setOnClickListener { showManualDisclosure() }
        list.adapter = rows(events.map { event ->
            val phone = phones[event.phoneId]
            val target = targets[event.targetId]
            "${phone?.label ?: "Unknown number"} → ${target?.displayName ?: "Unknown target"}\n" +
                "${event.method.name.lowercase().replaceFirstChar(Char::uppercase)} · ${DateFormat.getDateTimeInstance().format(Date(event.occurredAt))}" +
                if (event.originTrust == OriginTrust.UNVERIFIED) " · reported origin" else ""
        })
        list.setOnItemClickListener { _, _, position, _ -> showEventDialog(events[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this).setTitle("Delete ledger event?")
                .setMessage("Deletion is recorded as a tombstone so it can propagate during future sync.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    runAction { LedgerRepository.deleteEvent(events[position].id); renderLedger() }
                }.show()
            true
        }
    }

    private fun showManualDisclosure() {
        val phones = LedgerRepository.activePhones()
        if (phones.isEmpty()) { toast("Add an active phone number first"); return }
        val layout = vertical(20)
        val phoneSpinner = Spinner(this).apply { adapter = rows(phones.map { "${it.label} · ${it.e164}" }) }
        val kinds = listOf(TargetKind.PERSON, TargetKind.ORGANIZATION, TargetKind.PLACE, TargetKind.OTHER, TargetKind.WEB_ORIGIN)
        val kindSpinner = Spinner(this).apply { adapter = rows(kinds.map { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) }) }
        val recipient = input("Recipient, place, organization, or URL", "")
        val note = input("Optional note", "")
        layout.addView(labeled("Phone number", phoneSpinner))
        layout.addView(labeled("Disclosure target type", kindSpinner))
        layout.addView(recipient)
        layout.addView(note)
        val dialog = AlertDialog.Builder(this).setTitle("Record disclosure").setView(layout)
            .setNegativeButton("Cancel", null).setPositiveButton("Record", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val target = manualTarget(kinds[kindSpinner.selectedItemPosition], recipient.text.toString())
                    LedgerRepository.recordDisclosure(phones[phoneSpinner.selectedItemPosition].id, target, DisclosureMethod.MANUAL, note.text.toString())
                    dialog.dismiss(); renderLedger()
                } catch (error: Exception) { recipient.error = error.message }
            }
        }
        dialog.show()
    }

    private fun manualTarget(kind: TargetKind, raw: String): TargetDescriptor {
        val value = raw.trim()
        require(value.isNotEmpty()) { "Enter who received the number" }
        if (kind == TargetKind.WEB_ORIGIN) {
            val parsed = Uri.parse(if (value.contains("://")) value else "https://$value")
            val host = parsed.host?.lowercase() ?: throw IllegalArgumentException("Enter a valid URL")
            val port = if (parsed.port == -1) "" else ":${parsed.port}"
            val origin = "${parsed.scheme?.lowercase() ?: "https"}://$host$port"
            return TargetDescriptor(kind, origin, origin, detailUri = value, originTrust = OriginTrust.UNVERIFIED)
        }
        return TargetDescriptor(kind, value.lowercase(), value, originTrust = OriginTrust.NOT_APPLICABLE)
    }

    private fun showEventDialog(event: DisclosureEvent) {
        val layout = vertical(20)
        val detail = input("Exact URL / detail (optional)", event.detailUri.orEmpty())
        val note = input("Note", event.note)
        layout.addView(detail); layout.addView(note)
        val state = LedgerRepository.snapshot()
        val target = state.targets.firstOrNull { it.id == event.targetId }
        AlertDialog.Builder(this).setTitle(target?.displayName ?: "Ledger event").setView(layout)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                runAction { LedgerRepository.updateEvent(event.id, detail.text.toString(), note.text.toString()); renderLedger() }
            }.show()
    }

    private fun renderSettings() {
        titleView.text = "Settings & data"
        actionButton.visibility = View.GONE
        val sync = LedgerRepository.syncConfig()
        val options = listOf(
            "Enable Android Autofill",
            if (PinUnlock.isEnabled(this)) "Change or disable quick-unlock PIN" else "Set a 6-digit quick-unlock PIN",
            if (BiometricUnlock.isEnabled(this)) "Disable biometric unlock" else "Enable biometric unlock",
            if (sync == null) "Configure self-hosted sync" else "Self-hosted sync · ${sync.accountId.take(8)}…",
            "Import phone numbers from CSV",
            "Export phone numbers as plaintext CSV",
            "Export disclosure ledger as plaintext CSV",
            "Create encrypted .phoneledger backup",
            "Restore encrypted .phoneledger backup",
            "Lock vault now",
            "About security",
        )
        list.adapter = rows(options)
        list.setOnItemLongClickListener(null)
        list.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:$packageName")))
                1 -> configurePin()
                2 -> configureBiometric()
                3 -> showSyncSettings()
                4 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "text/*"; addCategory(Intent.CATEGORY_OPENABLE) }, IMPORT_CSV)
                5 -> createExport("phone-ledger-numbers.csv", CsvCodec.exportPhones(LedgerRepository.snapshot().phones), EXPORT_NUMBERS)
                6 -> createExport("phone-ledger-disclosures.csv", CsvCodec.exportEvents(LedgerRepository.snapshot()), EXPORT_EVENTS)
                7 -> createBackup()
                8 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE) }, RESTORE_BACKUP)
                9 -> { LedgerRepository.lock(); recreate() }
                10 -> AlertDialog.Builder(this).setTitle("Local-first security")
                    .setMessage("The local vault is encrypted with AES-256-GCM. Its key is derived from your master password using PBKDF2-HMAC-SHA256 (310,000 rounds). Optional biometric unlock uses an authentication-per-use Android Keystore key. The optional PIN wraps the vault key locally and locks for five minutes after five failures. Optional sync uses a separate 256-bit recovery key, per-device Ed25519 authentication, and an independently AES-256-GCM encrypted ledger. The server never receives the recovery key or plaintext ledger. Plaintext CSV exports are not encrypted.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun showSyncSettings() {
        val config = LedgerRepository.syncConfig()
        if (config == null) {
            val legacy = LedgerRepository.legacySyncConfig()
            if (legacy != null) {
                AlertDialog.Builder(this)
                    .setTitle("Migrate alpha sync")
                    .setMessage("This vault contains an AliasVault-based alpha sync connection. Fetch its latest encrypted ledger once, or continue with the data already on this phone. The old remote blob will not be deleted.")
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Use local data") { _, _ -> showSyncSetupChoice() }
                    .setPositiveButton("Fetch latest") { _, _ -> fetchLegacyThenConfigure() }
                    .show()
                return
            }
            showSyncSetupChoice()
            return
        }
        val lastSync = config.lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "never"
        val error = config.lastError?.let { "\nLast error: $it" }.orEmpty()
        val rotationPending = config.pendingRecoveryRootBase64 != null
        val status = TextView(this).apply {
            text = "Account ${config.accountId}\n${config.serverUrl}\nLast sync: $lastSync\nServer revision: ${config.remoteRevision}" +
                (if (rotationPending) "\nRecovery-key rotation is pending" else "") + error
            setTextIsSelectable(true)
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Self-hosted sync")
            .setView(status)
            .setItems(arrayOf(
                "Sync now",
                "Show recovery kit",
                if (rotationPending) "Finish recovery-key rotation" else "Rotate recovery key",
                "Disconnect this device",
            )) { _, which ->
                when (which) {
                    0 -> runSyncNow()
                    1 -> showRecoveryKit(config.accountId, SyncManager.currentRecoveryWords(), "Recovery kit")
                    2 -> confirmRotateRecovery()
                    3 -> confirmDisconnectSync()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSyncSetupChoice() {
        AlertDialog.Builder(this)
            .setTitle("Phone Ledger sync")
            .setMessage("Connect to a standalone Phone Ledger server over HTTPS. A new account needs a one-use invite. Existing accounts use the account ID and 24 recovery words—there is no server password.")
            .setNegativeButton("Close", null)
            .setNeutralButton("Create account") { _, _ -> showCreateSyncAccount() }
            .setPositiveButton("Connect existing") { _, _ -> showConnectSyncAccount() }
            .show()
    }

    private fun showCreateSyncAccount() {
        val layout = vertical(20)
        val server = input("https://sync.example.org", "")
        val invite = input("One-use invite code", "")
        layout.addView(server); layout.addView(invite)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Create sync account")
            .setMessage("Phone Ledger will generate the encryption and recovery key on this phone. The server must use trusted HTTPS.")
            .setView(layout).setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (invite.text.toString().trim().length < 20) {
                    invite.error = "Enter the invite from the server administrator"
                    return@setOnClickListener
                }
                dialog.dismiss()
                val progress = progressDialog("Creating encrypted sync account…")
                SyncManager.createAccount(this, server.text.toString(), invite.text.toString()) { result ->
                    runOnUiThread {
                        progress.dismiss()
                        if (!isFinishing && !isDestroyed) {
                            result.onSuccess {
                                renderSettings()
                                showRecoveryKit(it.accountId, it.recoveryWords, "Save your recovery kit")
                            }.onFailure { showMessage("Could not create sync account", it.message ?: "Unknown error") }
                        }
                    }
                }
                invite.setText("")
            }
        }
        dialog.show()
    }

    private fun showConnectSyncAccount(prefill: EnrollmentBundle? = null) {
        val layout = vertical(20)
        val server = input("https://sync.example.org", prefill?.serverUrl.orEmpty())
        val account = input("32-character account ID", prefill?.accountId.orEmpty())
        val recovery = input("24 recovery words", prefill?.recoveryWords.orEmpty()).apply {
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layout.addView(server); layout.addView(account); layout.addView(recovery)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect existing account")
            .setMessage("The recovery words are processed on this phone and are never uploaded to the server. You can also scan a recovery QR shown by an already enrolled device.")
            .setView(layout).setNegativeButton("Cancel", null)
            .setNeutralButton("Scan QR") { _, _ -> startRecoveryQrScan() }
            .setPositiveButton("Connect", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (recovery.text.toString().trim().split(Regex("\\s+")).size != 24) {
                    recovery.error = "Enter all 24 words"
                    return@setOnClickListener
                }
                dialog.dismiss()
                val words = recovery.text.toString()
                recovery.text.clear()
                val progress = progressDialog("Enrolling this device and syncing…")
                SyncManager.connectExisting(this, server.text.toString(), account.text.toString(), words) { result ->
                    runOnUiThread {
                        progress.dismiss()
                        if (!isFinishing && !isDestroyed) {
                            result.onSuccess {
                                renderSettings()
                                showMessage("Sync ready", "${it.action.replaceFirstChar(Char::uppercase)} · server revision ${it.revision}")
                            }.onFailure { showMessage("Could not connect sync", it.message ?: "Unknown error") }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun fetchLegacyThenConfigure() {
        val progress = progressDialog("Fetching the final AliasVault alpha ledger…")
        SyncManager.fetchLegacyOnce(this) { result ->
            runOnUiThread {
                progress.dismiss()
                if (!isFinishing && !isDestroyed) {
                    result.onSuccess {
                        toast("${it.action.replaceFirstChar(Char::uppercase)} · revision ${it.revision}")
                        showSyncSetupChoice()
                    }.onFailure { showMessage("Could not fetch alpha sync", it.message ?: "Use local data or check the old server") }
                }
            }
        }
    }

    private fun showRecoveryKit(accountId: String, words: String, title: String) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val message = "Account ID\n$accountId\n\nRecovery words\n$words\n\nWrite these down offline. Do not take a screenshot or store them with your phone. Losing every enrolled device and these words makes server data unrecoverable."
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message).setCancelable(false)
            .setNeutralButton("Show QR", null).setPositiveButton("I saved it", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val serverUrl = LedgerRepository.syncConfig()?.serverUrl
                if (serverUrl == null) {
                    toast("Sync settings are unavailable")
                } else {
                    dialog.dismiss()
                    window.decorView.post { showRecoveryQr(EnrollmentBundle(serverUrl, accountId, words)) }
                }
            }
        }
        dialog.setOnDismissListener { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        dialog.show()
    }

    private fun showRecoveryQr(bundle: EnrollmentBundle) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val bitmap = recoveryQrBitmap(EnrollmentQr.encode(bundle))
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = "Recovery enrollment QR code"
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Recovery QR")
            .setMessage("This QR contains the server address, account ID, and all recovery words. Treat it exactly like the written recovery kit and scan it only with a device you trust.")
            .setView(image).setCancelable(false).setPositiveButton("Done", null).create().also { dialog ->
                dialog.setOnDismissListener {
                    image.setImageDrawable(null)
                    bitmap.eraseColor(0)
                    bitmap.recycle()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                dialog.show()
            }
    }

    private fun recoveryQrBitmap(payload: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 720, 720, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    private fun startRecoveryQrScan() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan a Phone Ledger recovery QR")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    private fun confirmRotateRecovery() {
        val pending = LedgerRepository.syncConfig()?.pendingRecoveryRootBase64 != null
        AlertDialog.Builder(this).setTitle(if (pending) "Finish recovery-key rotation?" else "Rotate recovery key?")
            .setMessage(if (pending) {
                "A previous rotation may have reached the server. Phone Ledger will safely detect its state and finish using the same pending recovery key."
            } else {
                "This creates a new 24-word recovery kit, re-encrypts the server vault, and revokes every other device. Those devices must be enrolled again."
            })
            .setNegativeButton("Cancel", null).setPositiveButton("Rotate") { _, _ ->
                val progress = progressDialog("Rotating recovery key…")
                SyncManager.rotateRecovery(this) { result ->
                    runOnUiThread {
                        progress.dismiss()
                        if (!isFinishing && !isDestroyed) {
                            result.onSuccess {
                                val accountId = LedgerRepository.syncConfig()?.accountId.orEmpty()
                                showRecoveryKit(accountId, it.recoveryWords, "New recovery kit")
                            }.onFailure { showMessage("Rotation failed", it.message ?: "Unknown error") }
                        }
                    }
                }
            }.show()
    }

    private fun runSyncNow() {
        val progress = progressDialog("Syncing encrypted ledger…")
        SyncManager.syncNow(this) { result ->
            runOnUiThread {
                progress.dismiss()
                if (!isFinishing && !isDestroyed) {
                    renderSettings()
                    result.onSuccess { toast("${it.action.replaceFirstChar(Char::uppercase)} · revision ${it.revision}") }
                        .onFailure { showMessage("Sync failed", it.message ?: "Unknown error") }
                }
            }
        }
    }

    private fun confirmDisconnectSync() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect sync?")
            .setMessage("Revoking signs this device out on the server. If the server is unavailable, you can forget the local connection without claiming that the server session was revoked.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Forget locally") { _, _ -> disconnectSync(revoke = false) }
            .setPositiveButton("Revoke and disconnect") { _, _ -> disconnectSync(revoke = true) }
            .show()
    }

    private fun disconnectSync(revoke: Boolean) {
        val progress = progressDialog(if (revoke) "Revoking sync session…" else "Removing local sync settings…")
        SyncManager.disconnect(this, revoke) { result ->
            runOnUiThread {
                progress.dismiss()
                if (!isFinishing && !isDestroyed) {
                    result.onSuccess { renderSettings(); toast("Sync disconnected") }
                        .onFailure { showMessage("Could not disconnect", it.message ?: "Unknown error") }
                }
            }
        }
    }

    private fun progressDialog(message: String): AlertDialog = AlertDialog.Builder(this)
        .setMessage(message).setCancelable(false).create().also(AlertDialog::show)

    private fun configureBiometric() {
        if (BiometricUnlock.isEnabled(this)) {
            AlertDialog.Builder(this).setTitle("Disable biometric unlock?")
                .setNegativeButton("Cancel", null).setPositiveButton("Disable") { _, _ ->
                    BiometricUnlock.disable(this); renderSettings(); toast("Biometric unlock disabled")
                }.show()
            return
        }
        if (!BiometricUnlock.canEnable(this)) {
            showMessage("Biometric unavailable", "Enroll a strong biometric in Android settings, then try again.")
            return
        }
        BiometricUnlock.enable(
            this,
            success = { renderSettings(); toast("Biometric unlock enabled") },
            failure = { showMessage("Could not enable biometric", it) },
        )
    }

    private fun configurePin() {
        if (PinUnlock.isEnabled(this)) {
            AlertDialog.Builder(this).setTitle("Quick-unlock PIN")
                .setItems(arrayOf("Change PIN", "Disable PIN")) { _, which ->
                    if (which == 0) promptPinSetup() else {
                        PinUnlock.disable(this); toast("Quick-unlock PIN disabled"); renderSettings()
                    }
                }.setNegativeButton("Cancel", null).show()
        } else promptPinSetup()
    }

    private fun promptPinSetup() {
        val layout = vertical(20)
        val first = input("New 6-digit PIN", "").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val second = input("Confirm PIN", "").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        layout.addView(first); layout.addView(second)
        val dialog = AlertDialog.Builder(this).setTitle("Set quick-unlock PIN").setView(layout)
            .setMessage("A short PIN is less resistant to offline guessing than your master password. Five failed in-app attempts trigger a five-minute lockout.")
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = first.text.toString().toCharArray()
                val confirmation = second.text.toString().toCharArray()
                try {
                    require(pin.contentEquals(confirmation)) { "PINs do not match" }
                    PinUnlock.enable(this, pin)
                    dialog.dismiss(); renderSettings(); toast("Quick-unlock PIN enabled")
                } catch (error: Exception) { first.error = error.message }
                finally { pin.fill('\u0000'); confirmation.fill('\u0000') }
            }
        }
        dialog.show()
    }

    private fun createExport(filename: String, body: String, request: Int) {
        AlertDialog.Builder(this).setTitle("Plaintext export")
            .setMessage("Anyone who can read the exported file can see this sensitive data.")
            .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ ->
                pendingExport = body
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/csv"; putExtra(Intent.EXTRA_TITLE, filename); addCategory(Intent.CATEGORY_OPENABLE)
                }, request)
            }.show()
    }

    private fun createBackup() {
        pendingExport = null
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, "phone-ledger.phoneledger"); addCategory(Intent.CATEGORY_OPENABLE)
        }, EXPORT_BACKUP)
    }

    @Deprecated("Activity result API is sufficient for this platform-only app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            if (resultCode == RESULT_OK && !scan.contents.isNullOrBlank()) {
                runCatching { EnrollmentQr.decode(scan.contents) }
                    .onSuccess(::showConnectSyncAccount)
                    .onFailure { showMessage("Could not read recovery QR", it.message ?: "Invalid QR data") }
            }
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        runAction {
            when (requestCode) {
                IMPORT_CSV -> {
                    val csv = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    val report = LedgerRepository.importPhones(CsvCodec.parsePhones(csv))
                    showMessage("Import complete", "Added ${report.added}; skipped ${report.duplicates} duplicates." + if (report.errors.isEmpty()) "" else "\n\n${report.errors.take(10).joinToString("\n")}")
                }
                EXPORT_NUMBERS, EXPORT_EVENTS -> contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { it.write(pendingExport.orEmpty()) }.also { toast("Export written") }
                EXPORT_BACKUP -> contentResolver.openOutputStream(uri, "w")!!.use { it.write(LedgerRepository.encryptedBackup()) }.also { toast("Encrypted backup written") }
                RESTORE_BACKUP -> {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    promptRestore(bytes)
                }
            }
        }
    }

    private fun promptRestore(bytes: ByteArray) {
        val password = passwordInput("Backup master password")
        AlertDialog.Builder(this).setTitle("Replace this vault from backup?")
            .setMessage("The current local vault will be replaced only after the backup and password are verified.")
            .setView(password).setNegativeButton("Cancel", null).setPositiveButton("Restore", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val chars = password.text.toString().toCharArray()
                        try {
                            LedgerRepository.restoreBackup(this, bytes, chars)
                            dialog.dismiss(); currentTab = Tab.NUMBERS; renderTab(); toast("Backup restored")
                        } catch (error: Exception) { password.error = error.message }
                        finally { chars.fill('\u0000') }
                    }
                }
                dialog.show()
            }
    }

    private fun rows(values: List<String>): ArrayAdapter<String> = ArrayAdapter(this, android.R.layout.simple_list_item_1, values)
    private fun input(hintText: String, value: String) = EditText(this).apply { hint = hintText; setText(value); setSelectAllOnFocus(false) }
    private fun passwordInput(hintText: String) = input(hintText, "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
    private fun vertical(padding: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(4), dp(padding), 0) }
    private fun labeled(label: String, child: View) = vertical(0).apply { addView(TextView(this@MainActivity).apply { text = label }); addView(child) }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun showMessage(title: String, message: String) = AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    private fun runAction(block: () -> Unit) { try { block() } catch (error: Exception) { showMessage("Could not complete action", error.message ?: "Unknown error") } }

    companion object {
        private const val IMPORT_CSV = 101
        private const val EXPORT_NUMBERS = 102
        private const val EXPORT_EVENTS = 103
        private const val EXPORT_BACKUP = 104
        private const val RESTORE_BACKUP = 105
    }
}
