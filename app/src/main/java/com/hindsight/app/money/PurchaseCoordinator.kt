package com.hindsight.app.money

/**
 * The five fields of the native purchase sheet (AC-04 item 2). Every
 * field is populated from shell-side values — the single price source
 * and the receiving-wallet config. The game never sees this data
 * before the sheet, never supplies any of it, and never handles
 * amounts or addresses at any point in the flow.
 */
data class PurchaseSheetData(
    val itemDisplay: String,
    val railAmountDisplay: String,
    val usdAnchorDisplay: String,
    val conversionBasis: String,
    val networkFeeEstimateDisplay: String,
    val recipientDisplay: String
) {
    companion object {
        /** The sheet data for the current config, or null when no
         *  receiving wallet is configured. This is data only: while
         *  the rail is closed (sales not enabled), the coordinator
         *  refuses at intent, before this sheet can be confirmed. */
        fun forDayPass(
            receivingWalletProvider: () -> String? =
                { ReceivingWalletConfig.configuredReceivingWallet }
        ): PurchaseSheetData? {
            val recipient = receivingWalletProvider() ?: return null
            return PurchaseSheetData(
                itemDisplay = MoneyConfig.ITEM_DISPLAY_NAME,
                railAmountDisplay = MoneyConfig.priceRailDisplay(),
                usdAnchorDisplay = MoneyConfig.priceUsdDisplay(),
                conversionBasis = MoneyConfig.CONVERSION_BASIS,
                networkFeeEstimateDisplay = "≈ 0.000015 SOL (network fee, paid from your wallet)",
                recipientDisplay = recipient
            )
        }
    }
}

/** The sheet seam: the native UI shows the sheet and reports the
 *  user's decision. The harness substitutes a fake. */
fun interface SheetConfirmer {
    /** True = user confirmed on the native sheet. */
    fun confirm(sheet: PurchaseSheetData): Boolean
}

/** Outcome of asking the wallet to sign-and-send a built transaction. */
sealed interface SendOutcome {
    data class Sent(val signature: String) : SendOutcome
    data class Failed(val kind: TxFailureKind) : SendOutcome
}

/** The wallet seam: production is WalletManager (MWA 2.0). */
interface WalletSender {
    fun sendTransaction(serializedTransaction: ByteArray): SendOutcome
}

/** Coordinator-level result of a purchase attempt. */
sealed interface PurchaseResult {
    data class Granted(val receipt: Receipt) : PurchaseResult
    data class Pending(val signature: String, val verdict: SettlementVerifier.Verdict) : PurchaseResult
    data class Failed(val errorClass: TxErrorClass) : PurchaseResult

    /** The chain definitively refused settlement for a sent payment:
     *  recorded as FAILED + journal REFUSED, nothing granted. The
     *  buyer is not wedged — a fresh purchase may be attempted. */
    data class SettlementRefused(
        val signature: String,
        val verdict: SettlementVerifier.Verdict
    ) : PurchaseResult

    data object RailClosed : PurchaseResult
    data object UnknownItem : PurchaseResult
    data class AlreadyInFlight(val idempotencyKey: String) : PurchaseResult
    data object NotBound : PurchaseResult
}

/**
 * Orchestrates one purchase end to end (AC-04 → AC-05 → AC-06):
 *
 *   unresolved guard → gate check → intent (idempotency key) →
 *   native sheet → shell-built transaction → wallet sign-and-send →
 *   signature recorded (intent outcome, then pending journal) →
 *   on-chain settlement re-verification → grant.
 *
 * The grant comes only from [EntitlementLedger.grantFromVerifiedReceipt]
 * after [SettlementVerifier] returns VERIFIED. Every earlier exit is
 * a named failure or a pending state — never a grant, never a silent
 * loss.
 *
 * Exactly-once binding (bankai #3 MAJOR-2 rebuild): a replayed
 * purchase — double-tap, retry, process-death relaunch — binds to
 * the ORIGINAL purchase at three points, and each one genuinely
 * engages: (1) an unresolved sent outcome for the same buyer is
 * settled before any new intent exists; (2) an in-flight intent
 * returns its own outcome/signature instead of minting a second
 * charge; (3) the ledger grants exactly once per key and per
 * signature. (The previous "replay check" read the outcome of the
 * just-minted key — a key that by construction had no prior outcome
 * — and could never fire.)
 */
class PurchaseCoordinator(
    private val flow: PurchaseFlow,
    private val ledger: EntitlementLedger,
    private val settlement: SettlementVerifier,
    private val blockhashProvider: BlockhashProvider,
    private val walletSender: WalletSender,
    private val sheetConfirmer: SheetConfirmer,
    private val receivingWalletProvider: () -> String? =
        { ReceivingWalletConfig.configuredReceivingWallet }
) {
    fun purchase(itemId: String, buyerIdentity: String?): PurchaseResult {
        if (buyerIdentity == null) return PurchaseResult.NotBound

        // (1) Unresolved guard: a sent-but-unresolved purchase for
        // this buyer binds first. Settling it here returns its own
        // outcome — Granted, still-Pending — and never a new charge.
        // Only a definitive refusal (recorded FAILED by settle)
        // falls through, freeing the buyer to purchase afresh.
        flow.unresolvedOutcomes()
            .firstOrNull { it.buyerIdentity == buyerIdentity && it.signature != null }
            ?.let { unresolved ->
                when (val bound = settle(unresolved.idempotencyKey, unresolved.signature!!, itemId, buyerIdentity)) {
                    is PurchaseResult.Granted, is PurchaseResult.Pending -> return bound
                    else -> Unit
                }
            }

        val sheet = PurchaseSheetData.forDayPass(receivingWalletProvider) ?: return PurchaseResult.RailClosed
        val key = when (val begun = flow.beginIntent(itemId)) {
            is PurchaseFlow.BeginResult.Begun -> begun.idempotencyKey
            is PurchaseFlow.BeginResult.RailClosed -> return PurchaseResult.RailClosed
            is PurchaseFlow.BeginResult.AlreadyInFlight -> {
                // (2) In-flight binding: the intent already exists —
                // return ITS outcome (settling it first if it carries
                // a signature) rather than starting a second charge.
                val prior = flow.outcomeFor(begun.existingKey)
                if (prior?.signature != null) {
                    return settle(begun.existingKey, prior.signature, itemId, buyerIdentity)
                }
                return PurchaseResult.AlreadyInFlight(begun.existingKey)
            }
            is PurchaseFlow.BeginResult.UnknownItem -> return PurchaseResult.UnknownItem
        }
        if (!sheetConfirmer.confirm(sheet)) {
            val outcome = PurchaseOutcome(
                key, PurchaseState.FAILED, null,
                TxErrorClass.USER_CANCELLED_MID_FLOW,
                TxErrorClass.USER_CANCELLED_MID_FLOW.userMessage,
                buyerIdentity
            )
            flow.recordOutcome(outcome)
            return PurchaseResult.Failed(TxErrorClass.USER_CANCELLED_MID_FLOW)
        }
        flow.recordOutcome(
            PurchaseOutcome(key, PurchaseState.AWAITING_WALLET, null, null, "Awaiting wallet.", buyerIdentity)
        )
        val serialized = TransactionBuilder.buildDayPassPurchase(buyerIdentity, blockhashProvider, receivingWalletProvider)
        if (serialized == null) {
            val outcome = PurchaseOutcome(
                key, PurchaseState.FAILED, null,
                TxErrorClass.RPC_OR_CONFIRMATION_FAILURE,
                TxErrorClass.RPC_OR_CONFIRMATION_FAILURE.userMessage,
                buyerIdentity
            )
            flow.recordOutcome(outcome)
            return PurchaseResult.Failed(TxErrorClass.RPC_OR_CONFIRMATION_FAILURE)
        }
        return when (val sent = walletSender.sendTransaction(serialized)) {
            is SendOutcome.Failed -> {
                val errorClass = TxErrorMapper.map(sent.kind)
                flow.recordOutcome(
                    PurchaseOutcome(key, PurchaseState.FAILED, null, errorClass, errorClass.userMessage, buyerIdentity)
                )
                PurchaseResult.Failed(errorClass)
            }
            is SendOutcome.Sent -> {
                // The signature is recorded in the intent outcome
                // FIRST, then journaled in the ledger. Either record
                // alone is a sufficient recovery source at next
                // launch (reconcileOnLaunch scans both), so a crash
                // between broadcast and either write cannot strand
                // the payment. The journal result is checked, not
                // assumed: if journaling fails, the outcome record
                // still carries the signature and reconciliation
                // backfills from it.
                flow.recordOutcome(
                    PurchaseOutcome(
                        key, PurchaseState.PENDING_SETTLEMENT, sent.signature, null,
                        "Sent — verifying settlement on-chain.", buyerIdentity
                    )
                )
                val journaled = ledger.recordPending(sent.signature, buyerIdentity, key, itemId)
                if (!journaled) {
                    flow.recordOutcome(
                        PurchaseOutcome(
                            key, PurchaseState.PENDING_SETTLEMENT, sent.signature, null,
                            "Sent — verifying settlement on-chain. (Pending journal write failed; " +
                                "the signature is carried by this intent record for reconciliation.)",
                            buyerIdentity
                        )
                    )
                }
                settle(key, sent.signature, itemId, buyerIdentity)
            }
        }
    }

    /**
     * Attempt settlement for a sent purchase. Grants only on
     * VERIFIED. A definitive on-chain refusal resolves the purchase
     * (outcome FAILED, journal REFUSED, nothing granted) instead of
     * leaving it pending forever; transient verdicts stay pending.
     */
    fun settle(
        idempotencyKey: String,
        signature: String,
        itemId: String,
        buyerIdentity: String
    ): PurchaseResult {
        val verdict = settlement.verify(signature)
        return when {
            verdict == SettlementVerifier.Verdict.VERIFIED -> {
                val receipt = ledger.grantFromVerifiedReceipt(
                    signature, idempotencyKey, itemId, buyerIdentity
                )
                if (receipt != null) {
                    flow.recordOutcome(
                        PurchaseOutcome(
                            idempotencyKey, PurchaseState.GRANTED, signature, null,
                            "Day pass active.", buyerIdentity
                        )
                    )
                    PurchaseResult.Granted(receipt)
                } else {
                    // The grant could not be durably recorded: stays
                    // pending (never granted on an unrecorded write),
                    // and reconciliation retries it.
                    PurchaseResult.Pending(signature, verdict)
                }
            }
            verdict.isDefinitiveRefusal() -> {
                flow.recordOutcome(
                    PurchaseOutcome(
                        idempotencyKey, PurchaseState.FAILED, signature, null,
                        "Settlement refused on-chain (${verdict.name}): the payment is not a " +
                            "valid day-pass payment. Nothing was granted.",
                        buyerIdentity
                    )
                )
                ledger.markPendingRefused(signature, verdict.name)
                PurchaseResult.SettlementRefused(signature, verdict)
            }
            else -> PurchaseResult.Pending(signature, verdict)
        }
    }

    /**
     * Launch reconciliation (AC-05 item 4 restore-by-signature,
     * AC-06 item 2 / ENT-4): runs BEFORE the launch gate reads
     * entitlement. Marks stale intents UNKNOWN, then settles EVERY
     * recovery candidate from both durable records of a sent
     * signature — the ledger's pending journal and the flow's
     * unresolved intent outcomes. A candidate leaves reconciliation
     * only by resolving (grant on VERIFIED, recorded refusal on a
     * definitive verdict); anything else stays ACTIVE/UNKNOWN —
     * visible, retained, and retried at the next launch. Fails
     * closed throughout: nothing here grants without VERIFIED.
     */
    fun reconcileOnLaunch(boundIdentityHint: String?): Entitlement {
        flow.reconcileOnLaunch()
        val candidates = LinkedHashMap<String, Triple<String, String, String>>()
        ledger.activePendingEntries().forEach { entry ->
            candidates[entry.signature] = Triple(
                entry.idempotencyKey.ifEmpty { "reconcile:${entry.signature}" },
                entry.buyerIdentity,
                entry.itemId
            )
        }
        flow.unresolvedOutcomes().forEach { outcome ->
            val signature = outcome.signature ?: return@forEach
            if (signature !in candidates) {
                candidates[signature] = Triple(
                    outcome.idempotencyKey,
                    outcome.buyerIdentity ?: boundIdentityHint ?: "",
                    MoneyConfig.ITEM_DAY_PASS
                )
            }
        }
        for ((signature, candidate) in candidates) {
            val (key, buyer, item) = candidate
            if (buyer.isEmpty()) continue // unattributable: stays ACTIVE and visible in the journal
            settle(key, signature, item, buyer)
        }
        return ledger.entitlementFor(boundIdentityHint)
    }
}
