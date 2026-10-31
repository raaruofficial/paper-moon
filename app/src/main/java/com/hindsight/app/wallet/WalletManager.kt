package com.hindsight.app.wallet

import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.successPayload
import com.hindsight.app.util.Base58
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * UI-facing wallet state. Observed by Compose screens via [uiState].
 */
sealed interface WalletUiState {
    data object Disconnected : WalletUiState
    data object Working : WalletUiState
    data class Connected(val publicKeyBase58: String) : WalletUiState
    data class Error(val message: String) : WalletUiState
}

/**
 * A wallet failure whose [message] is plain user-facing copy, safe to
 * render anywhere — snackbar, screen, or the JS bridge. The raw SDK
 * cause rides in [cause] for logging only; its text is never the
 * message, so no consumer of a failed [Result] can leak raw exception
 * text by reading `e.message` (T1-S08 residue, closed at the Result
 * boundary).
 */
class WalletUiException(
    val userMessage: String,
    cause: Throwable? = null,
    /** Money build (AC-03): the internal failure kind, for the
     *  five-class taxonomy mapping. Null for non-transaction errors. */
    val failureKind: com.hindsight.app.money.TxFailureKind? = null
) : Exception(userMessage, cause)

/**
 * Owns the Solana Mobile Wallet Adapter session.
 *
 * Uses the official `mobile-wallet-adapter-clientlib-ktx` (MWA 2.0):
 * - [MobileWalletAdapter.connect] for the connect/session flow
 * - [MobileWalletAdapter.transact] + `signMessagesDetached` for message signing
 * - [MobileWalletAdapter.disconnect] to revoke the session
 *
 * API reference: solana-mobile-docs, "Using Mobile Wallet Adapter"
 * (android-native).
 *
 * The MWA auth token is persisted through [WalletTokenStore] (token
 * only, app-private storage): restored before the first connect so an
 * already-approved wallet can skip the approval dialog, refreshed
 * after every successful connect, and cleared on disconnect.
 *
 * Errors surfaced through [uiState] are always plain user-facing copy
 * written here — raw SDK/exception text never reaches a screen
 * (T1-S08). Failures returned in a [Result] are [WalletUiException]s
 * whose message is that same plain copy; the raw cause is attached
 * for logging only.
 */
class WalletManager(
    private val tokenStore: WalletTokenStore? = null
) {

    private val walletAdapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            // INTERIM identity (ruled 2026-10-19): until the estate's own
            // domain stands up, the wallet approval presents the
            // publisher-controlled project repo. v0.1.0 pointed this at
            // hindsight.app — a stranger's domain (T1-S04, CATASTROPHIC);
            // that string must never come back. The icon URI must be
            // RELATIVE: clientlib 2.0.8 validates iconRelativeUri and
            // throws before any wallet interaction when it is absolute,
            // which made every wallet connection fail (KFC-005 — the
            // WAL-002 rejection of v0.2.8, Ticket 345746509519). The
            // relative form resolves against the identity URI above,
            // a publisher-controlled root (WAL002_DIAGNOSIS.md §5).
            identityUri = Uri.parse("https://github.com/raaruofficial/paper-moon"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "Paper Moon"
        )
    )

    init {
        // Restore a previously issued auth token so the wallet can
        // re-authorize silently on the next connect.
        tokenStore?.authToken?.let { walletAdapter.authToken = it }
    }

    private val _uiState = MutableStateFlow<WalletUiState>(WalletUiState.Disconnected)
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    // HAVOC-4: connects are serialized at the manager. The UI disables
    // its button while Working, but the JS bridge can invoke connect
    // at any time; without this guard two connects would race on the
    // shared MobileWalletAdapter and the last to complete would own
    // the UI state. A second caller fails fast (never queues, so it
    // can never wedge or pop a stale approval later), and it does not
    // touch _uiState — the in-flight connect owns the state.
    private val connectInFlight = AtomicBoolean(false)

    val publicKeyBase58: String?
        get() = (_uiState.value as? WalletUiState.Connected)?.publicKeyBase58

    val isConnected: Boolean
        get() = _uiState.value is WalletUiState.Connected

    /** Get/set the MWA auth token so it can be persisted across restarts. */
    var persistedAuthToken: String?
        get() = walletAdapter.authToken
        set(value) {
            walletAdapter.authToken = value
        }

    /**
     * Connect to a wallet. Launches the MWA association intent via [sender]
     * (the calling Activity's single sender, created in its onCreate —
     * never construct an ActivityResultSender at call time or during
     * composition; registration is only legal before STARTED).
     * Returns the wallet's Base58 public key on success.
     */
    suspend fun connect(sender: ActivityResultSender): Result<String> {
        if (!connectInFlight.compareAndSet(false, true)) {
            return Result.failure(WalletUiException(CONNECT_IN_PROGRESS_MESSAGE))
        }
        return try {
            withContext(Dispatchers.IO) {
                _uiState.value = WalletUiState.Working
                val result = runCatching { walletAdapter.connect(sender) }.getOrElse { e ->
                    return@withContext fail(CONNECT_FAILED_MESSAGE, e)
                }
                when (result) {
                    is TransactionResult.Success -> {
                        val pubkeyBytes = result.authResult.accounts.firstOrNull()?.publicKey
                        if (pubkeyBytes == null) {
                            fail("The wallet returned no accounts.")
                        } else {
                            val base58 = Base58.encode(pubkeyBytes)
                            // Persist the token the wallet just issued so the
                            // next launch can re-authorize without the dialog.
                            tokenStore?.authToken = walletAdapter.authToken
                            _uiState.value = WalletUiState.Connected(base58)
                            Result.success(base58)
                        }
                    }
                    is TransactionResult.NoWalletFound ->
                        fail("No MWA-compatible wallet found on this device.")
                    is TransactionResult.Failure ->
                        fail(CONNECT_FAILED_MESSAGE, result.e)
                }
            }
        } finally {
            connectInFlight.set(false)
        }
    }

    /**
     * Ask the wallet to sign a SHELL-BUILT sign-in challenge payload
     * (AC-01). This is the only message-signing path in the app, and
     * it is not exposed to the page: the bridge calls it exclusively
     * with a payload constructed by the Shell Verifier (purpose
     * prefix + shell nonce + issued-at). No page-supplied text can be
     * passed here — the bridge method that once forwarded arbitrary
     * JS strings (`signMessage`) is removed.
     * Returns the Base58 signature.
     */
    suspend fun signShellChallenge(sender: ActivityResultSender, challengePayload: String): Result<String> =
        withContext(Dispatchers.IO) {
            // HAVOC-5: capture the session key BEFORE Working overwrites
            // the state. restoreConnectedState() used to read the key
            // back out of _uiState — already Working by then — so it
            // always resolved to null and every sign, successful or
            // not, landed the UI on Disconnected despite a live
            // session. Signing must never change the session state:
            // restore exactly what was here on entry.
            val priorPublicKey = publicKeyBase58
            _uiState.value = WalletUiState.Working
            val result = runCatching {
                walletAdapter.transact(sender) { authResult ->
                    val account = authResult.accounts.first().publicKey
                    signMessagesDetached(
                        arrayOf(challengePayload.toByteArray()),
                        arrayOf(account)
                    )
                }
            }.getOrElse { e ->
                restoreConnectedState(priorPublicKey)
                return@withContext Result.failure(WalletUiException(SIGN_FAILED_MESSAGE, e))
            }
            when (result) {
                is TransactionResult.Success -> {
                    val sig = result.successPayload?.messages
                        ?.firstOrNull()?.signatures?.firstOrNull()
                    restoreConnectedState(priorPublicKey)
                    if (sig != null) Result.success(Base58.encode(sig))
                    else Result.failure(WalletUiException("Wallet returned an empty signature"))
                }
                is TransactionResult.NoWalletFound -> {
                    restoreConnectedState(priorPublicKey)
                    Result.failure(WalletUiException("No MWA-compatible wallet found on this device."))
                }
                is TransactionResult.Failure -> {
                    restoreConnectedState(priorPublicKey)
                    Result.failure(WalletUiException(SIGN_FAILED_MESSAGE, result.e))
                }
            }
        }

    /**
     * Sign and send Solana transactions for real (AC-03): MWA 2.0
     * `signAndSendTransactions` inside a transact session. The
     * transactions are built shell-side only (see
     * [com.hindsight.app.money.TransactionBuilder]); this method
     * accepts already-built bytes and cannot alter them.
     *
     * Every failure is a [WalletUiException] carrying a
     * [com.hindsight.app.money.TxFailureKind], which the caller maps
     * onto the five-class taxonomy — raw SDK text never crosses.
     */
    suspend fun signAndSendTransactions(
        sender: ActivityResultSender,
        serializedTransactions: List<ByteArray>
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val priorPublicKey = publicKeyBase58
        _uiState.value = WalletUiState.Working
        val result = runCatching {
            walletAdapter.transact(sender) {
                signAndSendTransactions(serializedTransactions.toTypedArray())
            }
        }.getOrElse { e ->
            restoreConnectedState(priorPublicKey)
            return@withContext Result.failure(
                WalletUiException(
                    SEND_FAILED_MESSAGE, e,
                    com.hindsight.app.money.TxErrorMapper.kindFor(
                        noWalletFound = false,
                        userDeclinedHeuristic = isDeclined(e),
                        sessionCancelled = isCancelled(e)
                    )
                )
            )
        }
        when (result) {
            is TransactionResult.Success -> {
                val signatures = result.successPayload?.signatures
                restoreConnectedState(priorPublicKey)
                if (signatures != null && signatures.size == serializedTransactions.size) {
                    Result.success(signatures.map { Base58.encode(it) })
                } else {
                    Result.failure(
                        WalletUiException(
                            SEND_FAILED_MESSAGE, null,
                            com.hindsight.app.money.TxFailureKind.OTHER
                        )
                    )
                }
            }
            is TransactionResult.NoWalletFound -> {
                restoreConnectedState(priorPublicKey)
                Result.failure(
                    WalletUiException(
                        "No MWA-compatible wallet found on this device.", null,
                        com.hindsight.app.money.TxFailureKind.WALLET_NO_WALLET_FOUND
                    )
                )
            }
            is TransactionResult.Failure -> {
                restoreConnectedState(priorPublicKey)
                Result.failure(
                    WalletUiException(
                        SEND_FAILED_MESSAGE, result.e,
                        com.hindsight.app.money.TxErrorMapper.kindFor(
                            noWalletFound = false,
                            userDeclinedHeuristic = isDeclined(result.e),
                            sessionCancelled = isCancelled(result.e)
                        )
                    )
                )
            }
        }
    }

    private fun isDeclined(e: Throwable?): Boolean {
        val text = e?.toString()?.lowercase() ?: return false
        return "declin" in text || "reject" in text || "user denied" in text
    }

    private fun isCancelled(e: Throwable?): Boolean {
        val text = e?.toString()?.lowercase() ?: return false
        return "cancel" in text
    }

    /**
     * Revoke the session; the next connect shows the approval dialog again.
     *
     * T1-S10: the returned Boolean is the truth of the revoke. Only a
     * successful revoke clears the persisted token and drops the UI to
     * Disconnected; a failed revoke leaves the current state untouched
     * (the session is still active, and the UI must keep saying so).
     */
    suspend fun disconnect(sender: ActivityResultSender): Boolean {
        val result = runCatching { walletAdapter.disconnect(sender) }
        return if (result.isSuccess) {
            walletAdapter.authToken = null
            tokenStore?.authToken = null
            _uiState.value = WalletUiState.Disconnected
            true
        } else {
            false
        }
    }

    private fun fail(message: String, cause: Throwable? = null): Result<String> {
        _uiState.value = WalletUiState.Error(message)
        return Result.failure(WalletUiException(message, cause))
    }

    private fun restoreConnectedState(priorPublicKey: String?) {
        _uiState.value = if (priorPublicKey != null) {
            WalletUiState.Connected(priorPublicKey)
        } else {
            WalletUiState.Disconnected
        }
    }

    private companion object {
        const val CONNECT_FAILED_MESSAGE = "Wallet connection failed. Please try again."
        const val CONNECT_IN_PROGRESS_MESSAGE = "A wallet connection is already in progress."
        const val SIGN_FAILED_MESSAGE = "Wallet signing failed. Please try again."
        const val SEND_FAILED_MESSAGE = "The transaction could not be sent. Please try again."
    }
}
