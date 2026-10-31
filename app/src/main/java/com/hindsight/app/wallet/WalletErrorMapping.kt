package com.hindsight.app.wallet

import com.hindsight.app.money.TxFailureKind
import com.solanamobile.seekerconnect.SeekerConnectException.Code

/**
 * The gateway's sealed error mapping (Gate 0 C-14): every
 * `SeekerConnectException` code the SDK can throw, mapped to (a)
 * the internal money failure kind, (b) plain user-facing copy, and
 * (c) the C-21 submission-ambiguity disposition. This table is the
 * ONLY place SDK codes are interpreted. Raw cause text — MWA error
 * strings, JSON-RPC payloads, addresses, token fragments — never
 * crosses the gateway: not to the UI, not across the bridge, not
 * into any log or the purchase journal. The gateway never logs an
 * exception object; there is no `printStackTrace` or throwable-
 * logging call anywhere in the wallet seam (census-enforced).
 * `CancellationException` is not in this table: it propagates
 * unsealed, always.
 *
 * Ambiguity enumeration (C-21 ii — the gateway's ruling, filed in
 * the build record):
 *  - AMBIGUOUS (the transaction may already have been submitted;
 *    the error carries no signature): SESSION_CLOSED — the
 *    vendor's own dropped-session semantics (quickstart: "the
 *    wallet may already have submitted it"); WALLET_ERROR — the
 *    catch-all whose provenance is unknowable from outside, so it
 *    takes the safe class (the estate's AC-11 discipline: the
 *    class that never claims "nothing was charged" without proof
 *    is the default).
 *  - CERTAINLY-NOT-SUBMITTED: REQUEST_DECLINED and CANCELLED
 *    (named so by the verdict); AUTHORIZATION_DECLINED (the
 *    session's authorize was refused — no sign request was ever
 *    made); ASSOCIATION_FAILED (no session was ever established —
 *    association precedes any request reaching the wallet);
 *    NO_WALLET_FOUND (nothing to submit to).
 */
object WalletErrorMapping {

    enum class Disposition { CERTAIN_FAILURE, AMBIGUOUS_NO_SIGNATURE }

    data class Row(
        val failureKind: TxFailureKind?,
        val userMessage: String,
        val disposition: Disposition
    )

    private val rows: Map<Code, Row> = mapOf(
        Code.NO_WALLET_FOUND to Row(
            TxFailureKind.WALLET_NO_WALLET_FOUND,
            "No MWA-compatible wallet found on this device.",
            Disposition.CERTAIN_FAILURE
        ),
        Code.ASSOCIATION_FAILED to Row(
            TxFailureKind.OTHER,
            "Wallet connection failed. Please try again.",
            Disposition.CERTAIN_FAILURE
        ),
        Code.AUTHORIZATION_DECLINED to Row(
            TxFailureKind.WALLET_DECLINED,
            "Connection declined in the wallet.",
            Disposition.CERTAIN_FAILURE
        ),
        Code.REQUEST_DECLINED to Row(
            TxFailureKind.WALLET_DECLINED,
            "You declined the request in your wallet.",
            Disposition.CERTAIN_FAILURE
        ),
        Code.SESSION_CLOSED to Row(
            null,
            "The wallet session ended before the result came back.",
            Disposition.AMBIGUOUS_NO_SIGNATURE
        ),
        Code.CANCELLED to Row(
            TxFailureKind.WALLET_SESSION_CANCELLED,
            "The wallet request was cancelled.",
            Disposition.CERTAIN_FAILURE
        ),
        Code.WALLET_ERROR to Row(
            null,
            "The wallet reported an error before the result came back.",
            Disposition.AMBIGUOUS_NO_SIGNATURE
        )
    )

    /** Total by construction: an unmapped future code lands in
     *  the ambiguous (safe) class, never in raw text. */
    fun row(code: Code): Row = rows[code] ?: Row(
        null,
        "The wallet reported an error before the result came back.",
        Disposition.AMBIGUOUS_NO_SIGNATURE
    )

    fun isAmbiguous(code: Code): Boolean =
        row(code).disposition == Disposition.AMBIGUOUS_NO_SIGNATURE
}
