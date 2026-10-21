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
    data object RailClosed : PurchaseResult
    data object UnknownItem : PurchaseResult
    data class AlreadyInFlight(val idempotencyKey: String) : PurchaseResult
    data object NotBound : PurchaseResult
}

/**
 * Orchestrates one purchase end to end (AC-04 → AC-05 → AC-06):
 *
 *   gate check → intent (idempotency key) → native sheet →
 *   shell-built transaction → wallet sign-and-send → receipt
 *   persisted → on-chain settlement re-verification → grant.
 *
 * The grant comes only from [EntitlementLedger.grantFromVerifiedReceipt]
 * after [SettlementVerifier] returns VERIFIED. Every earlier exit is
 * a named failure or a pending state — never a grant, never a silent
 * loss (the receipt signature is durable before settlement is even
 * attempted, so a process death mid-purchase restores by signature).
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
        val sheet = PurchaseSheetData.forDayPass(receivingWalletProvider) ?: return PurchaseResult.RailClosed
        val key = when (val begun = flow.beginIntent(itemId)) {
            is PurchaseFlow.BeginResult.Begun -> begun.idempotencyKey
            is PurchaseFlow.BeginResult.RailClosed -> return PurchaseResult.RailClosed
            is PurchaseFlow.BeginResult.AlreadyInFlight ->
                return PurchaseResult.AlreadyInFlight(begun.existingKey)
            is PurchaseFlow.BeginResult.UnknownItem -> return PurchaseResult.UnknownItem
        }
        // Exactly-once replay: a completed intent returns its original
        // outcome instead of charging again (AC-06 item 1).
        flow.outcomeFor(key)?.let { prior ->
            if (prior.state == PurchaseState.GRANTED && prior.signature != null) {
                ledger.receiptForSignature(prior.signature)?.let { return PurchaseResult.Granted(it) }
            }
        }
        if (!sheetConfirmer.confirm(sheet)) {
            val outcome = PurchaseOutcome(
                key, PurchaseState.FAILED, null,
                TxErrorClass.USER_CANCELLED_MID_FLOW,
                TxErrorClass.USER_CANCELLED_MID_FLOW.userMessage
            )
            flow.recordOutcome(outcome)
            return PurchaseResult.Failed(TxErrorClass.USER_CANCELLED_MID_FLOW)
        }
        flow.recordOutcome(PurchaseOutcome(key, PurchaseState.AWAITING_WALLET, null, null, "Awaiting wallet."))
        val serialized = TransactionBuilder.buildDayPassPurchase(buyerIdentity, blockhashProvider, receivingWalletProvider)
        if (serialized == null) {
            val outcome = PurchaseOutcome(
                key, PurchaseState.FAILED, null,
                TxErrorClass.RPC_OR_CONFIRMATION_FAILURE,
                TxErrorClass.RPC_OR_CONFIRMATION_FAILURE.userMessage
            )
            flow.recordOutcome(outcome)
            return PurchaseResult.Failed(TxErrorClass.RPC_OR_CONFIRMATION_FAILURE)
        }
        return when (val sent = walletSender.sendTransaction(serialized)) {
            is SendOutcome.Failed -> {
                val errorClass = TxErrorMapper.map(sent.kind)
                flow.recordOutcome(
                    PurchaseOutcome(key, PurchaseState.FAILED, null, errorClass, errorClass.userMessage)
                )
                PurchaseResult.Failed(errorClass)
            }
            is SendOutcome.Sent -> {
                // Receipt first, settlement second: the signature is
                // durable before any grant attempt (AC-05 item 1).
                ledger.recordPending(sent.signature, buyerIdentity)
                flow.recordOutcome(
                    PurchaseOutcome(
                        key, PurchaseState.PENDING_SETTLEMENT, sent.signature, null,
                        "Sent — verifying settlement on-chain."
                    )
                )
                settle(key, sent.signature, itemId, buyerIdentity)
            }
        }
    }

    /** Attempt settlement for a sent purchase; grants only on VERIFIED. */
    fun settle(
        idempotencyKey: String,
        signature: String,
        itemId: String,
        buyerIdentity: String
    ): PurchaseResult {
        return when (val verdict = settlement.verify(signature)) {
            SettlementVerifier.Verdict.VERIFIED -> {
                val receipt = ledger.grantFromVerifiedReceipt(
                    signature, idempotencyKey, itemId, buyerIdentity
                )
                if (receipt != null) {
                    flow.recordOutcome(
                        PurchaseOutcome(
                            idempotencyKey, PurchaseState.GRANTED, signature, null,
                            "Day pass active."
                        )
                    )
                    PurchaseResult.Granted(receipt)
                } else {
                    PurchaseResult.Pending(signature, verdict)
                }
            }
            else -> PurchaseResult.Pending(signature, verdict)
        }
    }

    /**
     * Launch reconciliation (AC-05 item 4 restore-by-signature,
     * AC-06 item 2 / ENT-4): runs BEFORE the launch gate reads
     * entitlement. Marks stale intents UNKNOWN, then re-verifies any
     * pending receipt signature on-chain and grants if it verifies.
     * Fails closed: anything unverifiable stays pending, visible, and
     * blocks nothing else — and never grants.
     */
    fun reconcileOnLaunch(boundIdentityHint: String?): Entitlement {
        flow.reconcileOnLaunch()
        ledger.pending()?.let { (signature, buyer) ->
            // The pending intent's key is the one recorded at send time;
            // find it via the flow's stored outcomes by signature.
            val key = findIntentKeyForSignature(signature)
            if (key != null) settle(key, signature, MoneyConfig.ITEM_DAY_PASS, buyer)
        }
        return ledger.entitlementFor(boundIdentityHint)
    }

    private fun findIntentKeyForSignature(signature: String): String? {
        // PurchaseFlow persists outcomes by key; the pending signature's
        // key is recoverable from the ledger's own receipt index if the
        // grant already happened, else from the in-flight record. When
        // neither exists, mint a reconcile key derived from the
        // signature itself — grant exactly-once is enforced by the
        // ledger on BOTH keys, so a duplicate grant cannot occur.
        return ledger.receiptForSignature(signature)?.idempotencyKey
            ?: "reconcile:$signature"
    }
}
