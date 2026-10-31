package com.hindsight.app.wallet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-facing wallet state. Observed by Compose screens via the
 * gateway's uiState flow. (Moved verbatim out of the retired
 * clientlib WalletManager at the v0.2.10 Connect migration — the
 * state vocabulary the screens and bridge already speak does not
 * change with the transport.)
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

/** Shared mutable holder so the gateway file stays focused on the
 *  SDK seam; screens collect [WalletUiState] through the gateway. */
internal class WalletUiStateHolder {
    private val _uiState = MutableStateFlow<WalletUiState>(WalletUiState.Disconnected)
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()
    var value: WalletUiState
        get() = _uiState.value
        set(v) { _uiState.value = v }
}
