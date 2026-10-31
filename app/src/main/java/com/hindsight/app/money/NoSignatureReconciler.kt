package com.hindsight.app.money

/**
 * Reconciliation for UNRESOLVED_NO_SIGNATURE outcomes (Gate 0
 * C-21 iii): the wallet interaction ended without a signature in
 * the ambiguous class, so the payment may or may not be on-chain.
 * This reconciler resolves the question from chain evidence only:
 *
 *  - CANDIDATES: recent signatures touching the intent's recorded
 *    recipient (the recipient-side signature history).
 *  - MATCH: a candidate counts only when BOTH hold —
 *    [SettlementVerifier] returns VERIFIED for it (recipient,
 *    mint, exact amount, successful) AND its transfer details
 *    include a transfer whose source is the intent's buyer, to
 *    the recorded recipient, of the recorded mint, at exactly the
 *    recorded amount. The buyer-source requirement is the point:
 *    another buyer's identical payment never settles this intent.
 *    A match hands the signature to the coordinator's normal
 *    settle() path, so the grant still comes only from the
 *    ledger's exactly-once machinery.
 *  - EXPIRY: when the current block height passes the intent's
 *    recorded lastValidBlockHeight with no match, the built
 *    transaction can never land — non-submission is PROVEN, and
 *    the coordinator closes the intent FAILED (not-submitted) and
 *    re-opens purchase.
 *  - Anything else — chain unreadable, height unknown, no match
 *    yet inside the validity window — stays OPEN. Fail-closed:
 *    an unreadable chain never closes an intent and never grants.
 */
class NoSignatureReconciler(
    private val chain: NoSignatureChainReader,
    private val settlement: SettlementVerifier
) {
    /** The chain reads this reconciler needs, beyond the frozen
     *  settlement [ChainReader]. Production: [SolanaRpcChainReader]. */
    interface NoSignatureChainReader {
        /** Recent signatures for an address (newest first), or
         *  null when the chain is unreadable. */
        fun signaturesForAddress(addressBase58: String, limit: Int): List<String>?

        /** All-owner transfer details for a transaction, or null
         *  when unreadable / not found. */
        fun fetchTransferDetails(signature: String): List<ObservedTransferDetail>?

        /** Current block height, or null when unknown. */
        fun currentBlockHeight(): Long?
    }

    sealed interface Result {
        /** A proven matching payment: settle [signature] through
         *  the normal grant path. */
        data class Matched(val signature: String) : Result

        /** Validity expired with no match: non-submission proven. */
        data object ExpiredUnmatched : Result

        /** Not resolvable yet (or not resolvable from the
         *  available evidence): the intent stays open. */
        data object StillOpen : Result
    }

    fun reconcile(outcome: PurchaseOutcome): Result {
        val buyer = outcome.buyerIdentity ?: return Result.StillOpen
        val recipient = outcome.recipient ?: return Result.StillOpen
        val mint = outcome.mint ?: return Result.StillOpen
        val amount = outcome.amountBaseUnits ?: return Result.StillOpen

        val candidates = chain.signaturesForAddress(recipient, SIGNATURE_SCAN_LIMIT)
        if (candidates != null) {
            for (signature in candidates) {
                if (settlement.verify(signature) != SettlementVerifier.Verdict.VERIFIED) continue
                val details = chain.fetchTransferDetails(signature) ?: continue
                val hit = details.any {
                    it.fromOwner == buyer &&
                        it.toOwner == recipient &&
                        it.mint == mint &&
                        it.amountBaseUnits == amount
                }
                if (hit) return Result.Matched(signature)
            }
        }
        val height = chain.currentBlockHeight()
        val lastValid = outcome.lastValidBlockHeight
        if (height != null && lastValid != null && height > lastValid) {
            return Result.ExpiredUnmatched
        }
        return Result.StillOpen
    }

    private companion object {
        /** How deep the recipient's recent signature history is
         *  scanned per reconciliation pass. A day-pass payment
         *  reconciles within minutes in the normal case; the scan
         *  repeats at every launch and on the in-process re-check
         *  loop while the intent is open. */
        const val SIGNATURE_SCAN_LIMIT: Int = 25
    }
}
