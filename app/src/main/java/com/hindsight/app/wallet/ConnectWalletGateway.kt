package com.hindsight.app.wallet

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.hindsight.app.money.SecureKeyValueStore
import com.hindsight.app.money.ShellVerifier
import com.hindsight.app.money.TxFailureKind
import com.hindsight.app.util.Base58
import com.solanamobile.seekerconnect.DappIdentity
import com.solanamobile.seekerconnect.SeekerConnect
import com.solanamobile.seekerconnect.SeekerConnectConfig
import com.solanamobile.seekerconnect.SeekerConnectException
import com.solanamobile.seekerconnect.StoredAuthorization
import com.solanamobile.seekerconnect.WalletAvailability
import com.solanamobile.seekerconnect.android.MwaSeekerLink
import com.solanamobile.seekerconnect.compose.SeekerConnectDialogState
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one wallet gateway (Gate 0 §3.0 seam, C-17): every wallet
 * operation in the app — native surface and game bridge alike —
 * goes through this class, which wraps the single [SeekerConnect]
 * instance. It replaces the clientlib-direct WalletManager at the
 * v0.2.10 migration; the internal operation vocabulary the
 * consumers already speak (connect / signShellChallenge /
 * signAndSend / disconnect / uiState) is preserved so the money
 * machinery above the seam does not move.
 *
 * Construction (C-17): exactly one instance, constructed in
 * `MainActivity.onCreate` BEFORE `setContent` (guarded by
 * PaperMoonApp so an activity recreation within the process reuses
 * it), Application-scoped thereafter. No ViewModel. The SDK's
 * calls take a `ComponentActivity`; the gateway obtains it through
 * the current-activity provider below — a [WeakReference] set in
 * `onResume`, cleared in `onPause` only when identity-equal —
 * resolved at call time and failing closed (`wallet_unavailable`)
 * when there is no live activity. An Application-scoped strong
 * Activity reference anywhere here would be a review-blocking
 * defect.
 *
 * Execution contract (C-20 / ARCHITECT U-4): every wallet-opening
 * call executes inside the host activity's `lifecycleScope`; a
 * blocking caller (the bridge) waits on the deferred that scope
 * completes, never on the SDK session itself. `CancellationException`
 * propagates unsealed.
 *
 * Transport (C-11): the client is constructed with [MwaSeekerLink]
 * only — the local association WebSocket. No other SeekerLink
 * implementation is instantiated, and no transport configuration
 * is read from the page, the bridge, the network, or any config.
 *
 * Identity (C-24): frozen — name "Paper Moon", URI
 * `https://github.com/raaruofficial/paper-moon`, and the icon as a
 * RELATIVE reference under that same origin ("favicon.ico", the
 * HAUNT-proven form the WAL-002 cure shipped in v0.2.9). The URI
 * never moves to accommodate the icon.
 *
 * Errors (C-14): only [WalletErrorMapping]'s sealed kinds and
 * plain copy leave this class. Nothing here logs.
 */
class ConnectWalletGateway(
    context: Context,
    private val store: SecureKeyValueStore,
    private val shellVerifier: ShellVerifier,
    val dialogState: SeekerConnectDialogState
) {
    private val cache = ConnectAuthorizationCache(store)

    private val client = SeekerConnect(
        SeekerConnectConfig(
            identity = DappIdentity(IDENTITY_NAME, IDENTITY_URI, IDENTITY_ICON)
        ),
        MwaSeekerLink(context.applicationContext),
        cache,
        dialogState
    )

    private val ui = WalletUiStateHolder()
    val uiState: StateFlow<WalletUiState> = ui.uiState

    /** The SDK's held authorization (connected state). Restored is
     *  CONNECTED only — never bound (§4.4): no entitlement, claim,
     *  or purchase path reads this as authority. */
    val authorization: StateFlow<StoredAuthorization?> = client.authorization

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var currentActivity: WeakReference<ComponentActivity>? = null

    // HAVOC-4 discipline, carried across the migration: connects
    // are serialized at the gateway. A second caller fails fast
    // (never queues, so it can never wedge or pop a stale approval
    // later) and does not touch uiState — the in-flight connect
    // owns the state. (The SDK additionally guarantees concurrent
    // connect() calls launch the wallet at most once; that claim
    // is exercised, not trusted, at the A4 runtime cell.)
    private val connectInFlight = AtomicBoolean(false)

    private val _migrationNotice = MutableStateFlow(false)

    /** C-26a: a legacy token was deleted at migration and the user
     *  is owed the one-time point-of-need reconnect prompt. */
    val migrationNotice: StateFlow<Boolean> = _migrationNotice.asStateFlow()

    init {
        // The store's one migration seam (C-10): runs once,
        // idempotently, before any session use.
        ConnectMigration.runOnce(store)
        _migrationNotice.value = ConnectMigration.noticePending(store)
        // Restore (C-13): cache-only by SDK construction — one
        // cache read, one state update, no wallet launch, no
        // network. Called exactly once, here at gateway init.
        scope.launch {
            runCatching { client.restore() }
            syncUiWithAuthorization()
        }
        scope.launch {
            var last: StoredAuthorization? = null
            client.authorization.collect { auth ->
                if (auth != last) {
                    last = auth
                    if (ui.value !is WalletUiState.Working) syncUiWithAuthorization()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Current-activity provider (C-17)
    // ------------------------------------------------------------------

    fun activityResumed(activity: ComponentActivity) {
        currentActivity = WeakReference(activity)
    }

    fun activityPaused(activity: ComponentActivity) {
        // Cleared ONLY if identity-equal: a predecessor's onPause
        // can follow a successor's onResume across recreation and
        // must not clobber it.
        if (currentActivity?.get() === activity) currentActivity = null
    }

    private fun liveActivity(): ComponentActivity? =
        currentActivity?.get()?.takeIf { !it.isDestroyed }

    // ------------------------------------------------------------------
    // State reads
    // ------------------------------------------------------------------

    val isConnected: Boolean
        get() = client.authorization.value != null

    fun connectedPublicKey(): String? =
        client.authorization.value?.accounts?.firstOrNull()?.address

    /** C-12: probed ONLY when the wallet surface is opened (the
     *  caller is WalletScreen); the result is never logged, never
     *  bridged, never persisted — it lives in the surface's
     *  composition state and dies with it. */
    fun walletAvailability(): WalletAvailability = client.walletAvailability()

    fun dismissMigrationNotice() {
        ConnectMigration.dismissNotice(store)
        _migrationNotice.value = false
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /** Connect (or silently restore) and return the connected
     *  account's Base58 address. */
    suspend fun connect(): Result<String> {
        if (!connectInFlight.compareAndSet(false, true)) {
            return Result.failure(WalletUiException(CONNECT_IN_PROGRESS_MESSAGE))
        }
        return try {
            ui.value = WalletUiState.Working
            val auth = withWalletActivity { activity -> client.connect(activity) }
            val pubkey = auth.accounts.firstOrNull()?.address
            if (pubkey == null) {
                fail("The wallet returned no accounts.")
            } else {
                ui.value = WalletUiState.Connected(pubkey)
                Result.success(pubkey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WalletUnavailableException) {
            fail(WALLET_UNAVAILABLE_MESSAGE)
        } catch (e: SeekerConnectException) {
            val row = WalletErrorMapping.row(e.code)
            fail(row.userMessage, row.failureKind)
        } finally {
            connectInFlight.set(false)
        }
    }

    /**
     * Sign the shell-built sign-in challenge (AC-01) through
     * Connect's detached message signing. This remains the ONLY
     * message-signing path in the app, it is not exposed to the
     * page, and no page-supplied text can reach it — the bridge
     * calls it exclusively with [ShellVerifier]'s payload. The
     * signature returned is for the CONNECTED account; an account
     * mismatch refuses the bind downstream in the verifier (C-25)
     * — the gateway never substitutes accounts.
     * Returns the Base58 signature.
     */
    suspend fun signShellChallenge(challengePayload: String): Result<String> {
        val account = client.authorization.value?.accounts?.firstOrNull()
            ?: return Result.failure(WalletUiException(NOT_CONNECTED_MESSAGE))
        val priorPublicKey = connectedPublicKey()
        ui.value = WalletUiState.Working
        return try {
            val payloadBytes = challengePayload.toByteArray(Charsets.UTF_8)
            val signed = withWalletActivity { activity ->
                client.signMessages(activity, listOf(account.address), listOf(payloadBytes))
            }
            val signedPayload = signed.firstOrNull()
            val signature = signedPayload?.let { extractSignature(it, payloadBytes) }
            if (signature != null) {
                Result.success(Base58.encode(signature))
            } else {
                Result.failure(WalletUiException(SIGN_FAILED_MESSAGE))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WalletUnavailableException) {
            Result.failure(WalletUiException(WALLET_UNAVAILABLE_MESSAGE))
        } catch (e: SeekerConnectException) {
            val row = WalletErrorMapping.row(e.code)
            Result.failure(WalletUiException(row.userMessage, null, row.failureKind))
        } finally {
            restoreConnectedState(priorPublicKey)
        }
    }

    /** The outcome of a sign-and-send through the gateway. */
    sealed interface SendResult {
        data class Sent(val signatureBase58: String) : SendResult
        data class Failed(val kind: TxFailureKind) : SendResult

        /** A signature-less failure in the ambiguous class
         *  (C-21): the transaction may already have been
         *  submitted. [mappedKindName] is the sealed SDK code
         *  name — never raw cause text. */
        data class Ambiguous(val mappedKindName: String) : SendResult
    }

    /**
     * Sign and send one shell-built transaction (AC-03). The
     * transaction bytes are built shell-side only
     * (TransactionBuilder) and cannot be altered here. Failures
     * are sealed through [WalletErrorMapping]; an ambiguous
     * signature-less failure is reported as [SendResult.Ambiguous]
     * so the coordinator can open the UNRESOLVED_NO_SIGNATURE
     * machinery instead of closing the intent as failed (C-21).
     */
    suspend fun signAndSendTransactions(serializedTransaction: ByteArray): SendResult {
        val priorPublicKey = connectedPublicKey()
        ui.value = WalletUiState.Working
        return try {
            val signatures = withWalletActivity { activity ->
                client.signAndSendTransactions(activity, listOf(serializedTransaction))
            }
            val signature = signatures.firstOrNull()
            if (signature != null && signatures.size == 1) {
                SendResult.Sent(Base58.encode(signature))
            } else {
                SendResult.Failed(TxFailureKind.OTHER)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WalletUnavailableException) {
            SendResult.Failed(TxFailureKind.OTHER)
        } catch (e: SeekerConnectException) {
            val row = WalletErrorMapping.row(e.code)
            if (WalletErrorMapping.isAmbiguous(e.code)) {
                SendResult.Ambiguous(e.code.name)
            } else {
                SendResult.Failed(row.failureKind ?: TxFailureKind.OTHER)
            }
        } finally {
            restoreConnectedState(priorPublicKey)
        }
    }

    /** The truth of a disconnect (C-09): whether the wallet-side
     *  revocation succeeded. The local forget and the unbind
     *  happen regardless — see [DisconnectSequence]. */
    data class DisconnectOutcome(val deauthorizeSucceeded: Boolean)

    /**
     * Disconnect, per the C-09 contract: (1) attempt
     * `transact { deauthorize }` while a session can be opened;
     * (2) REGARDLESS of (1)'s outcome — clear the envelope (token,
     * accounts, and the learned URI), forget locally, and
     * `ShellVerifier.unbind()` (this wiring also cures the
     * pre-existing gap the brief found: unbind() existed with no
     * call site, so disconnect never signed the user out); (3)
     * the caller surfaces the honest residue copy on failure.
     * A failed deauthorize is never re-attempted silently in the
     * background.
     */
    suspend fun disconnect(): DisconnectOutcome {
        val token = client.authorization.value?.authToken
        val activity = liveActivity()
        val revoked = DisconnectSequence.run(
            attemptDeauthorize = {
                when {
                    // No token held: no wallet-side residue exists
                    // to revoke — the revoke is vacuously complete.
                    token == null -> true
                    activity == null -> false
                    else -> runCatching {
                        activity.lifecycleScope.async {
                            client.transact(activity) { deauthorize(token) }
                        }.await()
                        true
                    }.getOrDefault(false)
                }
            },
            forgetLocally = {
                runCatching { client.disconnect() }
                // Belt and braces under the same lock discipline:
                // the envelope key is gone even if the SDK's own
                // forget path is interrupted.
                runCatching { cache.clear() }
            },
            unbind = { shellVerifier.unbind() }
        )
        ui.value = WalletUiState.Disconnected
        return DisconnectOutcome(revoked)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private class WalletUnavailableException : Exception()

    /**
     * The C-20 execution contract: the wallet-opening call runs
     * inside the host activity's `lifecycleScope`; the caller
     * waits on THAT scope's deferred, never on the SDK session.
     * Fails closed when no live activity exists.
     */
    private suspend fun <T> withWalletActivity(
        block: suspend (ComponentActivity) -> T
    ): T {
        val activity = liveActivity() ?: throw WalletUnavailableException()
        val deferred: Deferred<T> = activity.lifecycleScope.async { block(activity) }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel()
            throw e
        }
    }

    private fun fail(message: String, kind: TxFailureKind? = null): Result<String> {
        ui.value = WalletUiState.Error(message)
        return Result.failure(WalletUiException(message, null, kind))
    }

    private fun syncUiWithAuthorization() {
        val pubkey = connectedPublicKey()
        ui.value = if (pubkey != null) {
            WalletUiState.Connected(pubkey)
        } else {
            WalletUiState.Disconnected
        }
    }

    private fun restoreConnectedState(priorPublicKey: String?) {
        ui.value = if (priorPublicKey != null) {
            WalletUiState.Connected(priorPublicKey)
        } else {
            WalletUiState.Disconnected
        }
    }

    companion object {
        // Identity, frozen (C-24). The icon is a path RELATIVE to
        // the identity URI — the only movable part, moved exactly
        // as the WAL-002 cure (v0.2.9) proved on the clientlib
        // stack. The URI itself never moves.
        const val IDENTITY_NAME: String = "Paper Moon"
        const val IDENTITY_URI: String = "https://github.com/raaruofficial/paper-moon"
        const val IDENTITY_ICON: String = "favicon.ico"

        const val CONNECT_FAILED_MESSAGE: String = "Wallet connection failed. Please try again."
        const val CONNECT_IN_PROGRESS_MESSAGE: String = "A wallet connection is already in progress."
        const val SIGN_FAILED_MESSAGE: String = "Wallet signing failed. Please try again."
        const val NOT_CONNECTED_MESSAGE: String = "Connect a wallet before sign-in."
        const val WALLET_UNAVAILABLE_MESSAGE: String =
            "The wallet surface is not available right now. Please try again."
    }
}

/**
 * Extract the 64-byte ed25519 signature from an MWA
 * `sign_messages` signed payload. The wire layout is proven from
 * clientlib's own result processing (MobileWalletAdapterClient
 * .SignMessagesFuture): the signed payload is the original
 * payload with the signature(s) APPENDED — message first, then
 * 64 bytes of signature per signing address. The mirror layout
 * is accepted defensively. Anything else is not a signature
 * this shell will use — the caller fails closed, and the
 * runtime cell verifies the extraction cryptographically
 * against the wallet's key.
 */
internal fun extractSignature(signedPayload: ByteArray, payload: ByteArray): ByteArray? = when {
    signedPayload.size == payload.size + 64 &&
        signedPayload.copyOfRange(64, signedPayload.size).contentEquals(payload) ->
        signedPayload.copyOfRange(0, 64)
    signedPayload.size == payload.size + 64 &&
        signedPayload.copyOfRange(0, payload.size).contentEquals(payload) ->
        signedPayload.copyOfRange(payload.size, signedPayload.size)
    else -> null
}
