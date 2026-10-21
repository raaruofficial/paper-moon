package com.hindsight.app.money

/**
 * The five-class transaction error taxonomy (AC-03 item 2).
 *
 * Every failure of a real transaction attempt resolves to EXACTLY ONE
 * of these classes, with plain user-facing copy. No raw exception
 * text ever crosses the bridge: the shell maps whatever the wallet /
 * RPC layer produced onto one class here, and only [userMessage]
 * (plus the class name) is exposed to the game.
 *
 * The receiving-wallet gate is deliberately NOT a sixth class in this
 * enum: it is not a transaction failure. It is a pre-transaction
 * refusal, raised by [PurchaseFlow] before any transaction exists,
 * and surfaced under its own name (`rail-closed`).
 */
enum class TxErrorClass(val userMessage: String) {
    USER_DECLINED("You declined the transaction in your wallet. Nothing was charged."),
    INSUFFICIENT_FUNDS("Insufficient USDC balance for this purchase. Nothing was charged."),
    WALLET_ABSENT("No MWA-compatible wallet found on this device. Nothing was charged."),
    RPC_OR_CONFIRMATION_FAILURE(
        "The network could not confirm this transaction. If a charge appears, it will be reconciled on next launch — do not pay again."
    ),
    USER_CANCELLED_MID_FLOW("The purchase was cancelled before it completed. Nothing was charged.")
}

/** Internal failure kinds the wallet/RPC layers can report. */
enum class TxFailureKind {
    WALLET_DECLINED,
    WALLET_NO_WALLET_FOUND,
    WALLET_SESSION_CANCELLED,
    INSUFFICIENT_BALANCE_PREFLIGHT,
    RPC_UNREACHABLE,
    CONFIRMATION_TIMEOUT,
    SEND_FAILED,
    OTHER
}

/**
 * The single mapping from internal failure kinds to taxonomy classes.
 * Total by construction: every kind maps, and unknown failures land
 * in RPC_OR_CONFIRMATION_FAILURE — the class whose copy never claims
 * "nothing was charged" without proof (AC-11 item 4's discipline:
 * the safe class is the default).
 */
object TxErrorMapper {
    fun map(kind: TxFailureKind): TxErrorClass = when (kind) {
        TxFailureKind.WALLET_DECLINED -> TxErrorClass.USER_DECLINED
        TxFailureKind.WALLET_NO_WALLET_FOUND -> TxErrorClass.WALLET_ABSENT
        TxFailureKind.WALLET_SESSION_CANCELLED -> TxErrorClass.USER_CANCELLED_MID_FLOW
        TxFailureKind.INSUFFICIENT_BALANCE_PREFLIGHT -> TxErrorClass.INSUFFICIENT_FUNDS
        TxFailureKind.RPC_UNREACHABLE,
        TxFailureKind.CONFIRMATION_TIMEOUT,
        TxFailureKind.SEND_FAILED,
        TxFailureKind.OTHER -> TxErrorClass.RPC_OR_CONFIRMATION_FAILURE
    }

    /** Extract a failure kind from an MWA transaction result failure, by
     *  the shape of the SDK outcome the WalletManager observed. Kept as
     *  data (booleans/enums) so the mapping is unit-testable without
     *  the SDK on the classpath. */
    fun kindFor(
        noWalletFound: Boolean,
        userDeclinedHeuristic: Boolean,
        sessionCancelled: Boolean
    ): TxFailureKind = when {
        noWalletFound -> TxFailureKind.WALLET_NO_WALLET_FOUND
        sessionCancelled -> TxFailureKind.WALLET_SESSION_CANCELLED
        userDeclinedHeuristic -> TxFailureKind.WALLET_DECLINED
        else -> TxFailureKind.OTHER
    }
}
