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

    /** The interaction ended WITHOUT a signature in the ambiguous
     *  class (Gate 0 C-21): the transaction may already have been
     *  submitted. [mappedKindName] is the sealed wallet error kind
     *  name from the gateway's mapping table — never raw text. The
     *  coordinator records a durable UNRESOLVED_NO_SIGNATURE
     *  outcome; the purchase is never auto-retried and never
     *  closed as failed on this evidence. */
    data class UnresolvedNoSignature(val mappedKindName: String) : SendOutcome
}

/** The wallet seam: production is the Connect gateway's sender. */
interface WalletSender {
    fun sendTransaction(serializedTransaction: ByteArray): SendOutcome
}

/** The build context of the most recent transaction build (C-21):
 *  the exact blockhash the transaction was built with, and its
 *  lastValidBlockHeight. A [BlockhashProvider] that also
 *  implements this records the pair during the build so the
 *  coordinator can stamp it onto an UNRESOLVED_NO_SIGNATURE
 *  intent — the recorded values are the BUILT transaction's, by
 *  construction, never a later re-read. */
data class BuildContext(val blockhashBase58: String, val lastValidBlockHeight: Long)

interface BuildContextSource {
    fun lastBuildContext(): BuildContext?
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

    /** The buyer already holds an active pass for this item: a
     *  second purchase would deliver nothing incremental, so nothing
     *  was charged, the window was NOT refreshed, and the attempt
     *  was recorded as a non-delivery (Terms §7(b)). */
    data class DuplicateActivePass(val expiresAtMs: Long) : PurchaseResult

    /** A signature-less ambiguous outcome is open for this buyer
     *  (C-21): the payment is being reconciled against the chain
     *  and NO new purchase may start meanwhile — the sender was
     *  not called again and nothing was charged by this attempt. */
    data class UnresolvedNoSignature(val outcome: PurchaseOutcome) : PurchaseResult

    data object RailClosed : PurchaseResult
    data object UnknownItem : PurchaseResult
    data class AlreadyInFlight(val idempotencyKey: String) : PurchaseResult
    data object NotBound : PurchaseResult
}

/**
 * Orchestrates one purchase end to end (AC-04 → AC-05 → AC-06):
 *
 *   unresolved guard → duplicate guard → gate check → intent
 *   (idempotency key) → native sheet → shell-built transaction →
 *   wallet sign-and-send → signature recorded (intent outcome, then
 *   pending journal) → on-chain settlement re-verification → grant.
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
        { ReceivingWalletConfig.configuredReceivingWallet },
    /** C-21 no-signature reconciliation. Optional only so the
     *  inert launch coordinator in tests can omit it — production
     *  wiring (PaperMoonApp + the bridge) always supplies it; an
     *  open no-signature outcome with no reconciler stays open
     *  (fail-closed), it never falls through to a new charge. */
    private val noSignatureReconciler: NoSignatureReconciler? = null
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

        // (1b) No-signature unresolved guard (C-21 i): an open
        // UNRESOLVED_NO_SIGNATURE outcome for this buyer plugs
        // into the same guard — reconciliation is attempted; a
        // match settles through the normal path and returns its
        // outcome; proven non-submission (expiry) frees the buyer
        // and falls through; anything else returns
        // UnresolvedNoSignature WITHOUT minting an intent, without
        // a sheet, and without calling the sender — a second
        // charge cannot happen while the outcome is open.
        flow.unresolvedNoSignatureOutcomes()
            .firstOrNull { it.buyerIdentity == buyerIdentity }
            ?.let { open ->
                reconcileNoSignature(open)?.let { return it }
            }

        // (2) Duplicate guard (Terms §7(b); bankai #3 MAJOR-5): the
        // buyer already holds an active pass for this item. A second
        // purchase would deliver nothing incremental, so nothing is
        // charged, the existing window is NOT refreshed, and the
        // attempt is recorded as a non-delivery — the remedy the
        // Terms promise for a duplicate payment.
        if (itemId == MoneyConfig.ITEM_DAY_PASS) {
            val held = ledger.entitlementFor(buyerIdentity)
            if (held.passActive) {
                // entitlementFor already established an active pass;
                // its receipt is the buyer's latest-expiring one.
                val active = ledger.allReceipts()
                    .filter { it.buyerIdentity == buyerIdentity }
                    .maxByOrNull { it.expiresAtMs }
                ledger.recordNonDelivery(
                    itemId, buyerIdentity, NON_DELIVERY_DUPLICATE_ACTIVE_PASS,
                    "active receipt ${active?.signature ?: "unknown"} expires ${held.passExpiresAtMs}"
                )
                return PurchaseResult.DuplicateActivePass(held.passExpiresAtMs)
            }
        }

        val sheet = PurchaseSheetData.forDayPass(receivingWalletProvider) ?: return PurchaseResult.RailClosed
        val key = when (val begun = flow.beginIntent(itemId)) {
            is PurchaseFlow.BeginResult.Begun -> begun.idempotencyKey
            is PurchaseFlow.BeginResult.RailClosed -> return PurchaseResult.RailClosed
            is PurchaseFlow.BeginResult.AlreadyInFlight -> {
                // (3) In-flight binding: the intent already exists —
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
            is SendOutcome.UnresolvedNoSignature -> {
                // C-21: record the durable unresolved outcome with
                // the full intent record (buyer, the BUILT
                // transaction's blockhash + lastValidBlockHeight
                // from the recording provider, amount/mint/
                // recipient from shell-side config, the sealed
                // error kind name, timestamp). Never auto-retried,
                // never closed as failed here.
                val ctx = (blockhashProvider as? BuildContextSource)?.lastBuildContext()
                val outcome = PurchaseOutcome(
                    idempotencyKey = key,
                    state = PurchaseState.UNRESOLVED_NO_SIGNATURE,
                    signature = null,
                    errorClass = null,
                    message = UNRESOLVED_USER_MESSAGE,
                    buyerIdentity = buyerIdentity,
                    blockhash = ctx?.blockhashBase58,
                    lastValidBlockHeight = ctx?.lastValidBlockHeight,
                    amountBaseUnits = MoneyConfig.PRICE_USDC_BASE_UNITS,
                    mint = MoneyConfig.USDC_MINT,
                    recipient = receivingWalletProvider(),
                    walletErrorKind = sent.mappedKindName,
                    createdAtMs = System.currentTimeMillis()
                )
                flow.recordOutcome(outcome)
                PurchaseResult.UnresolvedNoSignature(outcome)
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
     * One reconciliation pass over an open UNRESOLVED_NO_SIGNATURE
     * outcome (C-21 iii). Returns the [PurchaseResult] the caller
     * must surface when the outcome resolves or must keep blocking
     * (Matched → settle()'s result; StillOpen → the blocking
     * UnresolvedNoSignature result), or null when proven
     * non-submission closed the intent and the caller may proceed
     * with a fresh purchase. Never calls the wallet sender; never
     * retries the send.
     */
    fun reconcileNoSignature(outcome: PurchaseOutcome): PurchaseResult? {
        val reconciler = noSignatureReconciler
            ?: return PurchaseResult.UnresolvedNoSignature(outcome)
        return when (val result = reconciler.reconcile(outcome)) {
            is NoSignatureReconciler.Result.Matched -> {
                val buyer = outcome.buyerIdentity
                    ?: return PurchaseResult.UnresolvedNoSignature(outcome)
                settle(outcome.idempotencyKey, result.signature, MoneyConfig.ITEM_DAY_PASS, buyer)
            }
            NoSignatureReconciler.Result.ExpiredUnmatched -> {
                flow.recordOutcome(
                    outcome.copy(
                        state = PurchaseState.FAILED,
                        errorClass = TxErrorClass.RPC_OR_CONFIRMATION_FAILURE,
                        message = NOT_SUBMITTED_MESSAGE
                    )
                )
                null
            }
            NoSignatureReconciler.Result.StillOpen ->
                PurchaseResult.UnresolvedNoSignature(outcome)
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
        // C-21: open no-signature outcomes reconcile in the same
        // launch pass — a match grants through settle(), proven
        // non-submission closes the intent, anything else stays
        // open and keeps the guard.
        flow.unresolvedNoSignatureOutcomes().forEach { outcome ->
            reconcileNoSignature(outcome)
        }
        return ledger.entitlementFor(boundIdentityHint)
    }

    companion object {
        /** Non-delivery reason recorded for a purchase refused
         *  against an already-active pass (Terms §7(b)). */
        const val NON_DELIVERY_DUPLICATE_ACTIVE_PASS: String = "duplicate_active_pass"

        /** C-23's user line for an open unresolved outcome: says
         *  (a) the payment may already have been submitted, (b)
         *  the app is checking, and (c) no second charge can
         *  happen while it is checked. Never "payment failed",
         *  never "nothing was charged". */
        const val UNRESOLVED_USER_MESSAGE: String =
            "Your wallet closed before confirming, so this payment may already have been " +
                "submitted. We're checking the chain — no second charge can happen while we check."

        /** The recorded message when non-submission is PROVEN by
         *  block height (C-21 iii expiry close): only here may the
         *  record say the payment was not submitted. */
        const val NOT_SUBMITTED_MESSAGE: String =
            "The payment was not submitted — its validity window passed with no matching " +
                "payment on-chain. Nothing was charged; you can buy again."
    }
}
