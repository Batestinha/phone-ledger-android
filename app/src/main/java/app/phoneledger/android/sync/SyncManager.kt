package app.phoneledger.android.sync

import android.content.Context
import android.os.Build
import app.phoneledger.android.model.LedgerJson
import app.phoneledger.android.model.LedgerState
import app.phoneledger.android.model.SyncConfig
import app.phoneledger.android.storage.LedgerRepository
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SyncOutcome(val action: String, val revision: Long)
data class SetupOutcome(val sync: SyncOutcome, val recoveryWords: String, val accountId: String)
data class RotationOutcome(val revision: Long, val recoveryWords: String)

object SyncManager {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phone-ledger-sync").apply { isDaemon = true }
    }
    private val scheduled = AtomicBoolean(false)
    private val random = SecureRandom()

    fun schedule(context: Context) {
        if (!LedgerRepository.isUnlocked() || runCatching { LedgerRepository.syncConfig() }.getOrNull() == null) return
        if (!scheduled.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        executor.execute {
            var completed = false
            try {
                runCatching { syncInternal(appContext) }.onSuccess { completed = true }.onFailure(::recordError)
            } finally {
                scheduled.set(false)
                val dirty = completed && runCatching {
                    val state = LedgerRepository.snapshot()
                    val config = state.sync
                    config != null && state.revision != config.lastSyncedLocalRevision
                }.getOrDefault(false)
                if (dirty) schedule(appContext)
            }
        }
    }

    fun syncNow(context: Context, callback: (Result<SyncOutcome>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching { syncInternal(appContext) }
            result.exceptionOrNull()?.let(::recordError)
            callback(result)
        }
    }

    fun createAccount(
        context: Context,
        serverUrl: String,
        inviteCode: String,
        callback: (Result<SetupOutcome>) -> Unit,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                val normalizedUrl = ServerApi.normalizeServerUrl(serverUrl)
                require(inviteCode.trim().length >= 20) { "Enter the server's one-use invite code" }
                val api = ServerApi(normalizedUrl)
                val status = api.status()
                val accountId = newOpaqueId()
                val deviceId = newOpaqueId()
                val root = ServerSyncCrypto.generateRecoveryRoot()
                val keys = ServerSyncCrypto.generateDeviceKeyPair()
                try {
                    val enrollmentSecret = ServerSyncCrypto.deriveEnrollmentSecret(root, status.instanceId, accountId)
                    val tokens = try {
                        api.createAccount(
                            inviteCode, accountId, ServerSyncCrypto.enrollmentVerifier(enrollmentSecret),
                            deviceId, deviceName(), keys.publicKey,
                        )
                    } finally {
                        enrollmentSecret.fill(0)
                    }
                    installConfig(normalizedUrl, status.instanceId, accountId, root, deviceId, keys, tokens)
                    val sync = runCatching { syncInternal(appContext) }.getOrElse { error ->
                        recordError(error)
                        SyncOutcome("account created; initial upload pending", 0)
                    }
                    SetupOutcome(sync, RecoveryWords.encode(root), accountId)
                } finally {
                    root.fill(0)
                    keys.privateKey.fill(0)
                }
            }
            result.exceptionOrNull()?.let(::recordError)
            callback(result)
        }
    }

    fun connectExisting(
        context: Context,
        serverUrl: String,
        accountId: String,
        recoveryWords: String,
        callback: (Result<SyncOutcome>) -> Unit,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                val normalizedUrl = ServerApi.normalizeServerUrl(serverUrl)
                val normalizedAccount = accountId.trim().lowercase()
                require(normalizedAccount.length == 32 && normalizedAccount.all { it.isDigit() || it in 'a'..'f' }) {
                    "Account ID must be 32 hexadecimal characters"
                }
                val api = ServerApi(normalizedUrl)
                val status = api.status()
                val root = RecoveryWords.decode(recoveryWords)
                val deviceId = newOpaqueId()
                val keys = ServerSyncCrypto.generateDeviceKeyPair()
                try {
                    val enrollmentSecret = ServerSyncCrypto.deriveEnrollmentSecret(root, status.instanceId, normalizedAccount)
                    val tokens = try {
                        api.enrollDevice(normalizedAccount, enrollmentSecret, deviceId, deviceName(), keys.publicKey)
                    } finally {
                        enrollmentSecret.fill(0)
                    }
                    installConfig(normalizedUrl, status.instanceId, normalizedAccount, root, deviceId, keys, tokens)
                    syncInternal(appContext)
                } finally {
                    root.fill(0)
                    keys.privateKey.fill(0)
                }
            }
            result.exceptionOrNull()?.let(::recordError)
            callback(result)
        }
    }

    fun rotateRecovery(context: Context, callback: (Result<RotationOutcome>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                LedgerRepository.initialize(appContext)
                var config = LedgerRepository.syncConfig() ?: error("Sync is not configured")
                if (config.pendingRecoveryRootBase64 == null) {
                    syncInternal(appContext)
                    config = LedgerRepository.syncConfig() ?: error("Sync is not configured")
                    val generated = ServerSyncCrypto.generateRecoveryRoot()
                    try {
                        LedgerRepository.updateSyncSession { it.copy(pendingRecoveryRootBase64 = encode(generated)) }
                    } finally {
                        generated.fill(0)
                    }
                    config = LedgerRepository.syncConfig() ?: error("Sync is not configured")
                }
                val newRoot = decode(requireNotNull(config.pendingRecoveryRootBase64), 32, "Pending recovery key")
                try {
                    val api = api(config)
                    try {
                        val remote = api.getVault()
                        if (remote.blob != null && decryptsWith(remote.blob, newRoot, config)) {
                            finalizeRecoveryRotation(api, remote.revision, newRoot)
                            return@runCatching RotationOutcome(remote.revision, RecoveryWords.encode(newRoot))
                        }
                        if (remote.blob != null) {
                            val oldRoot = decode(config.recoveryRootBase64, 32, "Stored recovery key")
                            try {
                                require(decryptsWith(remote.blob, oldRoot, config)) {
                                    "Server vault cannot be decrypted with either recovery key"
                                }
                            } finally {
                                oldRoot.fill(0)
                            }
                        }
                        val state = LedgerRepository.snapshot()
                        val blob = encryptState(state, newRoot, config)
                        val enrollment = ServerSyncCrypto.deriveEnrollmentSecret(newRoot, config.instanceId, config.accountId)
                        val upload = try {
                            api.rotateRecovery(blob, remote.revision, ServerSyncCrypto.enrollmentVerifier(enrollment))
                        } finally {
                            enrollment.fill(0)
                        }
                        require(upload.accepted) { "The server changed during recovery-key rotation; try again" }
                        finalizeRecoveryRotation(api, upload.revision, newRoot)
                        RotationOutcome(upload.revision, RecoveryWords.encode(newRoot))
                    } finally {
                        api.close()
                    }
                } finally {
                    newRoot.fill(0)
                }
            }
            result.exceptionOrNull()?.let(::recordError)
            callback(result)
        }
    }

    fun disconnect(context: Context, revokeServerDevice: Boolean, callback: (Result<Unit>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                LedgerRepository.initialize(appContext)
                val config = LedgerRepository.syncConfig() ?: return@runCatching
                if (revokeServerDevice) api(config).use(ServerApi::revokeCurrentDevice)
                LedgerRepository.clearSync()
            }
            callback(result)
        }
    }

    fun fetchLegacyOnce(context: Context, callback: (Result<SyncOutcome>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                LedgerRepository.initialize(appContext)
                val config = LedgerRepository.legacySyncConfig() ?: error("No AliasVault alpha sync connection was found")
                val api = SyncApi(
                    config.serverUrl,
                    config.deviceId,
                    SyncTokens(config.accessToken, config.refreshToken),
                ) { tokens ->
                    LedgerRepository.updateLegacySyncSession {
                        it.copy(accessToken = tokens.access, refreshToken = tokens.refresh)
                    }
                }
                val remote = api.getVault()
                if (remote.blob.isBlank()) return@runCatching SyncOutcome("legacy vault was empty", remote.revision)
                val key = Base64.getDecoder().decode(config.payloadKeyBase64)
                try {
                    val clear = SyncCrypto.decryptPayload(remote.blob, key)
                    val state = try { LedgerJson.decode(clear) } finally { clear.fill(0) }
                    val observed = LedgerRepository.snapshot()
                    val integrated = LedgerRepository.integrateRemote(
                        state, observed.revision, config.lastSyncedLocalRevision, allowCleanAdoption = false,
                    ).state
                    LedgerRepository.updateLegacySyncSession {
                        it.copy(
                            remoteRevision = remote.revision, lastSyncedLocalRevision = integrated.revision,
                            lastSyncAt = System.currentTimeMillis(), lastError = null,
                        )
                    }
                    SyncOutcome("fetched legacy data", remote.revision)
                } finally {
                    key.fill(0)
                }
            }
            callback(result)
        }
    }

    private fun syncInternal(context: Context): SyncOutcome {
        LedgerRepository.initialize(context)
        val initial = LedgerRepository.syncConfig() ?: throw IllegalStateException("Sync is not configured")
        require(initial.pendingRecoveryRootBase64 == null) {
            "Recovery-key rotation is pending; choose Rotate recovery key again to finish it"
        }
        val root = decode(initial.recoveryRootBase64, 32, "Stored recovery key")
        try {
            val api = api(initial)
            try {
                repeat(MAX_SYNC_ATTEMPTS) {
                    val config = LedgerRepository.syncConfig() ?: throw IllegalStateException("Sync was disconnected")
                    val observed = LedgerRepository.snapshot()
                    val remote = api.getVault()
                    val rollback = remote.revision < config.remoteRevision
                    var action = "already in sync"
                    val candidate = if (remote.blob == null) {
                        observed
                    } else if (remote.revision != config.remoteRevision || rollback) {
                        val clear = ServerSyncCrypto.decryptPayload(remote.blob, root, config.instanceId, config.accountId)
                        val remoteState = try {
                            require(clear.size <= MAX_LEDGER_BYTES) { "Synced ledger is too large" }
                            LedgerJson.decode(clear).copy(sync = null, legacySync = null)
                        } finally {
                            clear.fill(0)
                        }
                        val integration = LedgerRepository.integrateRemote(
                            remoteState, observed.revision, config.lastSyncedLocalRevision,
                            allowCleanAdoption = !rollback,
                        )
                        action = when {
                            rollback -> "recovered server rollback"
                            integration.adoptedWithoutMerge -> "downloaded"
                            else -> "merged"
                        }
                        integration.state
                    } else {
                        observed
                    }

                    val needsUpload = remote.blob == null || rollback || candidate.revision != config.lastSyncedLocalRevision
                    if (!needsUpload) {
                        saveSuccess(api, remote.revision, candidate.revision)
                        return SyncOutcome(action, remote.revision)
                    }

                    val blob = encryptState(candidate, root, config)
                    val baseRevision = if (rollback) config.remoteRevision else remote.revision
                    val upload = api.uploadVault(blob, baseRevision)
                    if (upload.accepted) {
                        verifyUpload(api, upload.revision, root, config)
                        saveSuccess(api, upload.revision, candidate.revision)
                        runCatching { LedgerRepository.clearLegacySync() }
                        return SyncOutcome(if (action == "already in sync") "uploaded" else action, upload.revision)
                    }
                }
                throw IllegalStateException("The server changed repeatedly during sync; try again")
            } finally {
                api.close()
            }
        } finally {
            root.fill(0)
        }
    }

    private fun api(config: SyncConfig): ServerApi {
        val privateKey = decode(config.devicePrivateKeyBase64, 32, "Stored device key")
        return ServerApi(
            config.serverUrl, config.instanceId, config.accountId, config.deviceId, privateKey,
            ServerTokens(
                config.accessToken, config.refreshToken, config.accessExpiresAt, config.refreshExpiresAt,
            ),
        ) { tokens ->
            LedgerRepository.updateSyncSession {
                it.copy(
                    accessToken = tokens.access, refreshToken = tokens.refresh,
                    accessExpiresAt = tokens.accessExpiresAt, refreshExpiresAt = tokens.refreshExpiresAt,
                )
            }
        }
    }

    fun currentRecoveryWords(): String {
        val config = LedgerRepository.syncConfig() ?: error("Sync is not configured")
        val root = decode(config.pendingRecoveryRootBase64 ?: config.recoveryRootBase64, 32, "Stored recovery key")
        return try { RecoveryWords.encode(root) } finally { root.fill(0) }
    }

    private fun verifyUpload(api: ServerApi, revision: Long, root: ByteArray, config: SyncConfig) {
        val stored = api.getVault()
        require(stored.revision == revision && stored.blob != null) { "Server did not retain the uploaded vault" }
        ServerSyncCrypto.decryptPayload(stored.blob, root, config.instanceId, config.accountId).fill(0)
    }

    private fun encryptState(state: LedgerState, root: ByteArray, config: SyncConfig): ByteArray {
        val clear = LedgerJson.encodeForSync(state)
        return try {
            ServerSyncCrypto.encryptPayload(clear, root, config.instanceId, config.accountId)
        } finally {
            clear.fill(0)
        }
    }

    private fun decryptsWith(blob: ByteArray, root: ByteArray, config: SyncConfig): Boolean = runCatching {
        ServerSyncCrypto.decryptPayload(blob, root, config.instanceId, config.accountId).also { it.fill(0) }
    }.isSuccess

    private fun finalizeRecoveryRotation(api: ServerApi, revision: Long, root: ByteArray) {
        val tokens = api.tokens ?: error("Sync session expired")
        LedgerRepository.updateSyncSession {
            it.copy(
                recoveryRootBase64 = encode(root), pendingRecoveryRootBase64 = null,
                accessToken = tokens.access, refreshToken = tokens.refresh,
                accessExpiresAt = tokens.accessExpiresAt, refreshExpiresAt = tokens.refreshExpiresAt,
                remoteRevision = revision, lastSyncedLocalRevision = -1,
                lastSyncAt = System.currentTimeMillis(), lastError = null,
            )
        }
    }

    private fun saveSuccess(api: ServerApi, remoteRevision: Long, localRevision: Long) {
        val tokens = api.tokens ?: throw IllegalStateException("Sync session expired")
        LedgerRepository.updateSyncSession {
            it.copy(
                accessToken = tokens.access, refreshToken = tokens.refresh,
                accessExpiresAt = tokens.accessExpiresAt, refreshExpiresAt = tokens.refreshExpiresAt,
                remoteRevision = remoteRevision, lastSyncedLocalRevision = localRevision,
                lastSyncAt = System.currentTimeMillis(), lastError = null,
            )
        }
    }

    private fun installConfig(
        serverUrl: String,
        instanceId: String,
        accountId: String,
        root: ByteArray,
        deviceId: String,
        keys: DeviceKeyPair,
        tokens: ServerTokens,
    ) {
        val localRevision = LedgerRepository.snapshot().revision
        LedgerRepository.configureSync(
            SyncConfig(
                serverUrl, instanceId, accountId, encode(root), deviceId,
                encode(keys.privateKey), encode(keys.publicKey), tokens.access, tokens.refresh,
                tokens.accessExpiresAt, tokens.refreshExpiresAt, lastSyncedLocalRevision = localRevision,
            ),
        )
    }

    private fun recordError(error: Throwable) {
        runCatching {
            LedgerRepository.updateSyncSession {
                it.copy(lastError = (error.message ?: "Sync failed").take(500))
            }
        }
    }

    private fun newOpaqueId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android device" }
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String, size: Int, label: String): ByteArray = try {
        Base64.getUrlDecoder().decode(value).also { require(it.size == size) { "$label has an invalid size" } }
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("$label is damaged", error)
    }

    private const val MAX_SYNC_ATTEMPTS = 3
    private const val MAX_LEDGER_BYTES = 64 * 1024 * 1024
}
