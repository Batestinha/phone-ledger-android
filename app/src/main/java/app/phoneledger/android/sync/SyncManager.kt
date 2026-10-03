package app.phoneledger.android.sync

import android.content.Context
import app.phoneledger.android.model.LedgerJson
import app.phoneledger.android.model.SyncConfig
import app.phoneledger.android.storage.LedgerRepository
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SyncOutcome(val action: String, val revision: Long)

object SyncManager {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phone-ledger-sync").apply { isDaemon = true }
    }
    private val scheduled = AtomicBoolean(false)

    fun schedule(context: Context) {
        if (!LedgerRepository.isUnlocked() || runCatching { LedgerRepository.syncConfig() }.getOrNull() == null) return
        if (!scheduled.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        executor.execute {
            var completed = false
            try {
                runCatching { syncInternal(appContext) }
                    .onSuccess { completed = true }
                    .onFailure(::recordError)
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
        username: String,
        password: CharArray,
        callback: (Result<SyncOutcome>) -> Unit,
    ) {
        val passwordCopy = password.copyOf()
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                val normalizedUrl = SyncApi.normalizeServerUrl(serverUrl)
                val normalizedUsername = normalizeUsername(username)
                require(passwordCopy.size >= 10) { "Sync password must be at least 10 characters" }
                val salt = SyncCrypto.generateSalt()
                val passwordHash = SyncCrypto.derivePasswordHash(passwordCopy, salt)
                try {
                    val identity = UUID.randomUUID().toString()
                    val privateKey = SyncCrypto.derivePrivateKey(salt, identity, passwordHash.toHex())
                    val verifier = SyncCrypto.deriveVerifier(privateKey)
                    val deviceId = newDeviceId()
                    val api = SyncApi(normalizedUrl, deviceId)
                    val tokens = api.register(normalizedUsername, salt, verifier, identity)
                    installConfig(normalizedUrl, normalizedUsername, tokens, passwordHash, deviceId)
                    syncInternal(appContext)
                } finally {
                    passwordHash.fill(0)
                }
            }
            passwordCopy.fill('\u0000')
            result.exceptionOrNull()?.let(::recordError)
            callback(result)
        }
    }

    fun connectExisting(
        context: Context,
        serverUrl: String,
        username: String,
        password: CharArray,
        twoFactorCode: String?,
        callback: (Result<SyncOutcome>) -> Unit,
    ) {
        val passwordCopy = password.copyOf()
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                val normalizedUrl = SyncApi.normalizeServerUrl(serverUrl)
                val normalizedUsername = normalizeUsername(username)
                val deviceId = newDeviceId()
                val api = SyncApi(normalizedUrl, deviceId)
                val challenge = api.initiateLogin(normalizedUsername)
                val identity = challenge.srpIdentity ?: normalizedUsername
                val passwordHash = SyncCrypto.derivePasswordHash(
                    passwordCopy,
                    challenge.salt,
                    challenge.encryptionType,
                    challenge.encryptionSettings,
                )
                try {
                    val privateKey = SyncCrypto.derivePrivateKey(challenge.salt, identity, passwordHash.toHex())
                    val ephemeral = SyncCrypto.generateEphemeral()
                    val session = SyncCrypto.deriveSession(
                        ephemeral.secret,
                        challenge.serverEphemeral,
                        challenge.salt,
                        identity,
                        privateKey,
                    )
                    val validation = api.validateLogin(
                        normalizedUsername,
                        ephemeral.public,
                        session.proof,
                        twoFactorCode,
                    )
                    if (validation.requiresTwoFactor) {
                        throw IllegalStateException("This account requires a 6-digit two-factor code; enter it and connect again")
                    }
                    val tokens = validation.tokens ?: throw IllegalStateException("Server did not issue a sync session")
                    SyncCrypto.verifyServerSession(ephemeral.public, session, validation.serverProof)
                    installConfig(normalizedUrl, normalizedUsername, tokens, passwordHash, deviceId)
                    syncInternal(appContext)
                } finally {
                    passwordHash.fill(0)
                }
            }
            passwordCopy.fill('\u0000')
            result.exceptionOrNull()?.let(::recordError)
            callback(result)
        }
    }

    fun disconnect(context: Context, revokeServerSession: Boolean, callback: (Result<Unit>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                val config = LedgerRepository.syncConfig() ?: return@runCatching
                if (revokeServerSession) {
                    SyncApi(
                        config.serverUrl,
                        config.deviceId,
                        SyncTokens(config.accessToken, config.refreshToken),
                    ).revoke()
                }
                LedgerRepository.clearSync()
            }
            callback(result)
        }
    }

    private fun syncInternal(context: Context): SyncOutcome {
        LedgerRepository.initialize(context)
        val initial = LedgerRepository.syncConfig() ?: throw IllegalStateException("Sync is not configured")
        val api = SyncApi(
            initial.serverUrl,
            initial.deviceId,
            SyncTokens(initial.accessToken, initial.refreshToken),
        ) { tokens ->
            LedgerRepository.updateSyncSession {
                it.copy(accessToken = tokens.access, refreshToken = tokens.refresh)
            }
        }
        val payloadKey = try {
            Base64.getDecoder().decode(initial.payloadKeyBase64)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Stored sync key is damaged", error)
        }
        require(payloadKey.size == 32) { "Stored sync key is damaged" }
        try {
            repeat(MAX_SYNC_ATTEMPTS) {
                val config = LedgerRepository.syncConfig() ?: throw IllegalStateException("Sync was disconnected")
                val observed = LedgerRepository.snapshot()
                val remote = api.getVault()
                var action = "already in sync"
                val candidate = if (remote.blob.isBlank()) {
                    observed
                } else if (remote.revision != config.remoteRevision) {
                    val bytes = SyncCrypto.decryptPayload(remote.blob, payloadKey)
                    require(bytes.size <= MAX_LEDGER_BYTES) { "Synced ledger is too large" }
                    val remoteState = LedgerJson.decode(bytes).copy(sync = null)
                    val integration = LedgerRepository.integrateRemote(
                        remoteState,
                        observed.revision,
                        config.lastSyncedLocalRevision,
                    )
                    action = if (integration.adoptedWithoutMerge) "downloaded" else "merged"
                    integration.state
                } else {
                    observed
                }

                val needsUpload = remote.blob.isBlank() || candidate.revision != config.lastSyncedLocalRevision
                if (!needsUpload) {
                    saveSuccess(api, remote.revision, candidate.revision)
                    return SyncOutcome(action, remote.revision)
                }

                val clearPayload = LedgerJson.encodeForSync(candidate)
                val encryptedPayload = SyncCrypto.encryptPayload(clearPayload, payloadKey)
                val upload = api.uploadVault(config.username, encryptedPayload, remote.revision)
                if (upload.accepted) {
                    saveSuccess(api, upload.revision, candidate.revision)
                    return SyncOutcome(if (action == "already in sync") "uploaded" else action, upload.revision)
                }
            }
            throw IllegalStateException("The server changed repeatedly during sync; try again")
        } finally {
            payloadKey.fill(0)
        }
    }

    private fun saveSuccess(api: SyncApi, remoteRevision: Long, localRevision: Long) {
        val tokens = api.tokens ?: throw IllegalStateException("Sync session expired")
        LedgerRepository.updateSyncSession {
            it.copy(
                accessToken = tokens.access,
                refreshToken = tokens.refresh,
                remoteRevision = remoteRevision,
                lastSyncedLocalRevision = localRevision,
                lastSyncAt = System.currentTimeMillis(),
                lastError = null,
            )
        }
    }

    private fun installConfig(
        serverUrl: String,
        username: String,
        tokens: SyncTokens,
        passwordHash: ByteArray,
        deviceId: String,
    ) {
        val payloadKey = SyncCrypto.derivePayloadKey(passwordHash)
        try {
            LedgerRepository.configureSync(
                SyncConfig(
                    serverUrl = serverUrl,
                    username = username,
                    accessToken = tokens.access,
                    refreshToken = tokens.refresh,
                    payloadKeyBase64 = Base64.getEncoder().encodeToString(payloadKey),
                    deviceId = deviceId,
                ),
            )
        } finally {
            payloadKey.fill(0)
        }
    }

    private fun recordError(error: Throwable) {
        runCatching {
            LedgerRepository.updateSyncSession {
                it.copy(lastError = (error.message ?: "Sync failed").take(500))
            }
        }
    }

    private fun normalizeUsername(raw: String): String {
        val username = raw.trim().lowercase()
        require(username.length in 3..40) { "Username must contain 3 to 40 characters" }
        return username
    }

    private fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")
    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private const val MAX_SYNC_ATTEMPTS = 3
    private const val MAX_LEDGER_BYTES = 64 * 1024 * 1024
}
