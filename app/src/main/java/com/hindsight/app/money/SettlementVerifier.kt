package com.hindsight.app.money

/**
 * A token transfer as seen on-chain, reduced to what settlement
 * verification needs. Produced by a [ChainReader] from a real RPC
 * response; constructed directly in tests.
 */
data class ObservedTransfer(
    val toAddress: String,
    val mint: String,
    val amountBaseUnits: Long
)

/** A transaction as seen at a named commitment level. */
data class ObservedTransaction(
    val signature: String,
    /** True only when the tx was found at the required commitment. */
    val foundAtCommitment: Boolean,
    val transfers: List<ObservedTransfer>,
    val failed: Boolean
)

/**
 * The chain seam. The production implementation is
 * [SolanaRpcChainReader] (JSON-RPC `getTransaction`); tests use fakes.
 * Implementations must return null (never throw across this seam)
 * when the RPC is unreachable or the response cannot be parsed —
 * unreachable means fail closed, per AC-05 item 2.
 */
interface ChainReader {
    fun fetchTransaction(signature: String): ObservedTransaction?
}

/**
 * Settlement half of the Shell Verifier (AC-05).
 *
 * **No entitlement is granted on the wallet's say-so.** Before any
 * grant, the transaction is re-verified on-chain and ALL of these
 * must hold:
 *  1. the transaction is found at the named commitment level
 *     ([COMMITMENT] = "confirmed");
 *  2. it did not fail on-chain;
 *  3. it contains a transfer to the configured dedicated receiving
 *     wallet (the AC-04 recipient — which, per the pre-join
 *     mitigation, can only be the dedicated PAPER MOON wallet);
 *  4. that transfer is of the correct mint (USDC, P6-c);
 *  5. its amount is ≥ the quoted rail amount (single price source).
 *
 * Any check failing — or the RPC being unreachable — means NO grant
 * (fail closed), a named pending state, and reconciliation on next
 * launch (ENT-4). Restore-by-signature uses this same path: at
 * launch, known receipt signatures are re-verified here, so a
 * reinstall or process death never strands a paid pass.
 *
 * RPC semantics (AC-05 item 4, specified here): the endpoint is the
 * public Solana mainnet JSON-RPC unless overridden at build time;
 * there is no silent failover to a different trust domain in this
 * build — a single endpoint, and unreachability is a visible pending
 * state, not a guess. Offline grace: a pending purchase stays
 * pending indefinitely (never expires into a loss, never grants
 * offline); the receipt signature is durable in AC-02 storage and
 * the next launch with connectivity reconciles it.
 */
class SettlementVerifier(
    private val chainReader: ChainReader,
    private val receivingWalletProvider: () -> String? =
        { ReceivingWalletConfig.configuredReceivingWallet }
) {
    enum class Verdict {
        VERIFIED,
        NOT_FOUND,
        TX_FAILED,
        WRONG_RECIPIENT,
        WRONG_MINT,
        SHORT_AMOUNT,
        RAIL_UNCONFIGURED,
        RPC_UNREACHABLE;

        /**
         * True when the chain has given a FINAL answer that this
         * signature is not a valid day-pass payment: the transaction
         * exists and failed, or it paid the wrong recipient, the
         * wrong mint, or too little. A definitive refusal resolves a
         * pending purchase (recorded, nothing granted) instead of
         * leaving it pending forever. NOT_FOUND (propagation lag)
         * and RPC_UNREACHABLE / RAIL_UNCONFIGURED (connectivity /
         * configuration) are NOT definitive — those stay pending.
         */
        fun isDefinitiveRefusal(): Boolean =
            this == TX_FAILED || this == WRONG_RECIPIENT ||
                this == WRONG_MINT || this == SHORT_AMOUNT
    }

    fun verify(signature: String): Verdict {
        val recipient = receivingWalletProvider()
            ?: return Verdict.RAIL_UNCONFIGURED
        val tx = runCatching { chainReader.fetchTransaction(signature) }.getOrNull()
            ?: return Verdict.RPC_UNREACHABLE
        if (!tx.foundAtCommitment) return Verdict.NOT_FOUND
        if (tx.failed) return Verdict.TX_FAILED
        // Checks in spec order; each wrong-* verdict is distinct so a
        // payment to the wrong place can never blur into "short".
        val toRecipient = tx.transfers.filter { it.toAddress == recipient }
        if (toRecipient.isEmpty()) return Verdict.WRONG_RECIPIENT
        val correctMint = toRecipient.filter { it.mint == MoneyConfig.USDC_MINT }
        if (correctMint.isEmpty()) return Verdict.WRONG_MINT
        val total = correctMint.sumOf { it.amountBaseUnits }
        if (total < MoneyConfig.PRICE_USDC_BASE_UNITS) return Verdict.SHORT_AMOUNT
        return Verdict.VERIFIED
    }

    companion object {
        /** The named commitment level settlement requires (AC-05 item 2). */
        const val COMMITMENT: String = "confirmed"
    }
}
