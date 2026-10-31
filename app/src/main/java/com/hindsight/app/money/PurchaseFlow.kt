package com.hindsight.app.money

import java.security.SecureRandom
import org.json.JSONObject

/** Named purchase states (AC-06 item 2). The UI renders these names. */
enum class PurchaseState {
    /** Intent created, sheet not yet confirmed. */
    INTENT_CREATED,

    /** User confirmed the sheet; wallet approval / send in flight. */
    AWAITING_WALLET,

    /** Transaction sent; settlement not yet verified — NO grant yet. */
    PENDING_SETTLEMENT,

    /** Settlement verified on-chain; the shell granted the pass. */
    GRANTED,

    /** A named failure (see [TxErrorClass]); nothing was granted. */
    FAILED,

    /** Sent-but-unconfirmed at process death / unknown outcome.
     *  Reconciled before the launch gate reads entitlement (ENT-4). */
    UNKNOWN,

    /** The wallet interaction ended WITHOUT a signature in the
     *  ambiguous class (Gate 0 C-21): the transaction may already
     *  have been submitted, so this outcome is DURABLE and plugs
     *  into the same unresolved guard as a sent signature — a
     *  fresh purchase for the buyer cannot start, and the sender
     *  is never called again for this intent, until reconciliation
     *  either finds the payment on-chain (and settles it through
     *  the normal grant path) or proves non-submission by block
     *  height (the built transaction's lastValidBlockHeight has
     *  passed with no matching transfer) and closes it. It is
     *  never auto-retried and never closed as failed by the error
     *  itself. */
    UNRESOLVED_NO_SIGNATURE
}

/** The terminal (or replayable) outcome of a purchase intent.
 *  [buyerIdentity] binds the outcome to its buyer so reconciliation
 *  can attribute a recovered signature to the right identity; it is
 *  null in records written before the field existed. */
data class PurchaseOutcome(
    val idempotencyKey: String,
    val state: PurchaseState,
    val signature: String?,
    val errorClass: TxErrorClass?,
    val message: String,
    val buyerIdentity: String? = null,
    // C-21 intent-record fields (UNRESOLVED_NO_SIGNATURE records):
    // everything reconciliation needs to find or exclude the
    // payment later, captured at build time from shell-side
    // values — the buyer, the built transaction's blockhash and
    // lastValidBlockHeight, and the amount/mint/recipient from
    // MoneyConfig and the receiving-wallet config. Null/absent in
    // records written before C-21 and in non-C-21 states.
    val blockhash: String? = null,
    val lastValidBlockHeight: Long? = null,
    val amountBaseUnits: Long? = null,
    val mint: String? = null,
    val recipient: String? = null,
    /** The sealed wallet error kind NAME (e.g. "SESSION_CLOSED") —
     *  never raw cause text (C-14). */
    val walletErrorKind: String? = null,
    val createdAtMs: Long = 0L
)

/**
 * Purchase intent + idempotency (AC-06 mechanics) and the fail-closed
 * receiving-wallet gate (owner-directed pre-join mitigation).
 *
 * One idempotency key runs from intent creation to confirmation.
 * Re-submitting the same intent — retry, double-tap, process-death
 * replay — returns the original outcome: never a second charge,
 * never a double grant. Completed outcomes are persisted in the
 * AC-02 store, so a replay after process death still resolves to the
 * original outcome instead of starting a new purchase.
 *
 * THE GATE: [beginIntent] checks [ReceivingWalletConfig] FIRST.
 * While no dedicated PAPER MOON receiving wallet is configured, no
 * intent can be created at all — the rail refuses to sell before a
 * sheet, a transaction, or a charge can exist. There is no default
 * recipient and no fallback.
 */
class PurchaseFlow(
    private val store: SecureKeyValueStore,
    private val random: SecureRandom = SecureRandom(),
    private val railOpenProvider: () -> Boolean = { ReceivingWalletConfig.railOpen }
) {
    sealed interface BeginResult {
        data class Begun(val idempotencyKey: String) : BeginResult

        /** The rail is closed: no dedicated receiving wallet configured. */
        data object RailClosed : BeginResult

        /** A purchase for this item is already in flight under [existingKey]. */
        data class AlreadyInFlight(val existingKey: String) : BeginResult

        data class UnknownItem(val itemId: String) : BeginResult
    }

    private val inFlight = HashMap<String, String>() // itemId -> idempotencyKey

    @Synchronized
    fun beginIntent(itemId: String): BeginResult {
        if (!railOpenProvider()) return BeginResult.RailClosed
        if (itemId != MoneyConfig.ITEM_DAY_PASS) return BeginResult.UnknownItem(itemId)
        inFlight[itemId]?.let { return BeginResult.AlreadyInFlight(it) }
        val key = ByteArray(16).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        inFlight[itemId] = key
        persistOutcome(
            PurchaseOutcome(key, PurchaseState.INTENT_CREATED, null, null, "Intent created.")
        )
        return BeginResult.Begun(key)
    }

    /** Record a state transition for an intent. Terminal states stick:
     *  a GRANTED/FAILED outcome is never overwritten by a later replay.
     *  Returns false when the transition could not be durably
     *  recorded — callers that hand money-shaped state to this method
     *  must not pretend otherwise.
     *
     *  The in-flight slot is released at PENDING_SETTLEMENT as well
     *  as at the terminal states: once a signature exists, the
     *  durable outcome + the ledger's pending journal carry the
     *  purchase, and the coordinator's unresolved-outcome guard —
     *  not this in-memory slot — is what stops a second charge
     *  (bankai #3 MAJOR-2: PENDING_SETTLEMENT previously wedged the
     *  item in flight for the process's whole life). */
    @Synchronized
    fun recordOutcome(outcome: PurchaseOutcome): Boolean {
        val existing = outcomeFor(outcome.idempotencyKey)
        if (existing != null &&
            (existing.state == PurchaseState.GRANTED || existing.state == PurchaseState.FAILED)
        ) {
            return true
        }
        val persisted = persistOutcome(outcome)
        if (persisted &&
            (outcome.state == PurchaseState.GRANTED ||
                outcome.state == PurchaseState.FAILED ||
                outcome.state == PurchaseState.PENDING_SETTLEMENT ||
                outcome.state == PurchaseState.UNRESOLVED_NO_SIGNATURE)
        ) {
            // UNRESOLVED_NO_SIGNATURE releases the slot too: the
            // durable outcome + the coordinator's guard — not this
            // in-memory slot — is what stops a second charge.
            //
            // The release is conditioned on the write having
            // LANDED (TEKKADAN audit F4): if the durable record
            // failed, no guard exists after this process, so the
            // in-memory slot stays held and the buyer fails closed
            // in-process (AlreadyInFlight) instead of free.
            inFlight.values.remove(outcome.idempotencyKey)
        }
        return persisted
    }

    @Synchronized
    fun outcomeFor(idempotencyKey: String): PurchaseOutcome? =
        store.get(KEY_PREFIX + idempotencyKey)?.let { decode(it) }

    /**
     * Every outcome still carrying an unresolved sent signature —
     * PENDING_SETTLEMENT or UNKNOWN with a signature. These are the
     * recovery candidates reconciliation settles, and the guard the
     * coordinator consults before minting any new intent: an
     * unresolved outcome is never silently dropped from this list —
     * it leaves only by reaching GRANTED or FAILED.
     */
    @Synchronized
    fun unresolvedOutcomes(): List<PurchaseOutcome> =
        store.keysWithPrefix(KEY_PREFIX)
            .mapNotNull { store.get(it) }
            .mapNotNull { decode(it) }
            .filter {
                (it.state == PurchaseState.PENDING_SETTLEMENT ||
                    it.state == PurchaseState.UNKNOWN) && it.signature != null
            }

    /**
     * Every open UNRESOLVED_NO_SIGNATURE outcome (C-21): the
     * no-signature half of the unresolved guard. An outcome leaves
     * this list only by settling (GRANTED via a found signature)
     * or by the expiry close (FAILED, proven not-submitted) — the
     * error that created it never closes it.
     */
    @Synchronized
    fun unresolvedNoSignatureOutcomes(): List<PurchaseOutcome> =
        store.keysWithPrefix(KEY_PREFIX)
            .mapNotNull { store.get(it) }
            .mapNotNull { decode(it) }
            .filter { it.state == PurchaseState.UNRESOLVED_NO_SIGNATURE }

    /**
     * Intents stranded by process death mid-send (TEKKADAN audit
     * F2): [reconcileOnLaunch] rewrote them UNKNOWN, and they
     * carry no signature. UNKNOWN-without-signature can arise
     * ONLY from AWAITING_WALLET — PENDING_SETTLEMENT always
     * carries its signature, and INTENT_CREATED is never
     * rewritten — so these are exactly the purchases that reached
     * the wallet and never produced an outcome. No signature
     * guard can cover them; the coordinator converts them into
     * the no-signature machinery (UNRESOLVED_NO_SIGNATURE) at
     * launch reconciliation so the guard covers them there.
     */
    @Synchronized
    fun strandedNoSignatureOutcomes(): List<PurchaseOutcome> =
        store.keysWithPrefix(KEY_PREFIX)
            .mapNotNull { store.get(it) }
            .mapNotNull { decode(it) }
            .filter { it.state == PurchaseState.UNKNOWN && it.signature == null }

    /**
     * Launch reconcile (ENT-4): any intent left in a non-terminal,
     * post-send state at process death becomes UNKNOWN, so the launch
     * gate fails closed with a visible recovery path until settlement
     * reconciliation resolves it. Returns the unknown outcomes.
     * (UNRESOLVED_NO_SIGNATURE outcomes are already durable and
     * self-describing; they are deliberately NOT rewritten here —
     * the no-signature reconciler owns their resolution.)
     */
    @Synchronized
    fun reconcileOnLaunch(): List<PurchaseOutcome> {
        val keys = store.keysWithPrefix(KEY_PREFIX)
        val unknown = ArrayList<PurchaseOutcome>()
        for (storeKey in keys) {
            val outcome = store.get(storeKey)?.let { decode(it) } ?: continue
            if (outcome.state == PurchaseState.PENDING_SETTLEMENT ||
                outcome.state == PurchaseState.AWAITING_WALLET
            ) {
                val marked = outcome.copy(
                    state = PurchaseState.UNKNOWN,
                    message = "Purchase outcome unknown — reconciling against the chain before any new purchase."
                )
                // The marking counts only when it is durable
                // (TEKKADAN R2 residual, B-F4 doctrine): a failed
                // write leaves the outcome in its prior state for
                // the next pass instead of reporting a conversion
                // that never landed.
                if (persistOutcome(marked)) {
                    unknown.add(marked)
                }
            }
        }
        // Slots clear only for intents whose UNKNOWN marking is
        // durable. An intent whose marking failed stays in flight
        // (fail-closed in-process) and stays in its durable prior
        // state for the next launch's pass.
        val markedKeys = unknown.map { it.idempotencyKey }.toHashSet()
        inFlight.values.removeAll(markedKeys)
        return unknown
    }

    private fun persistOutcome(outcome: PurchaseOutcome): Boolean =
        store.put(KEY_PREFIX + outcome.idempotencyKey, encode(outcome))

    private fun encode(o: PurchaseOutcome): String = JSONObject()
        .put("key", o.idempotencyKey)
        .put("state", o.state.name)
        .put("sig", o.signature ?: JSONObject.NULL)
        .put("err", o.errorClass?.name ?: JSONObject.NULL)
        .put("msg", o.message)
        .put("buyer", o.buyerIdentity ?: JSONObject.NULL)
        .put("bh", o.blockhash ?: JSONObject.NULL)
        .put("lvbh", o.lastValidBlockHeight ?: JSONObject.NULL)
        .put("amt", o.amountBaseUnits ?: JSONObject.NULL)
        .put("mint", o.mint ?: JSONObject.NULL)
        .put("rcpt", o.recipient ?: JSONObject.NULL)
        .put("werr", o.walletErrorKind ?: JSONObject.NULL)
        .put("ts", o.createdAtMs)
        .toString()

    private fun decode(raw: String): PurchaseOutcome? = runCatching {
        val j = JSONObject(raw)
        PurchaseOutcome(
            idempotencyKey = j.getString("key"),
            state = PurchaseState.valueOf(j.getString("state")),
            signature = if (j.isNull("sig")) null else j.getString("sig"),
            errorClass = if (j.isNull("err")) null else TxErrorClass.valueOf(j.getString("err")),
            message = j.getString("msg"),
            buyerIdentity = if (j.isNull("buyer")) null else j.getString("buyer"),
            blockhash = if (j.isNull("bh")) null else j.optString("bh").takeIf { it.isNotEmpty() },
            lastValidBlockHeight = if (j.isNull("lvbh")) null else j.optLong("lvbh"),
            amountBaseUnits = if (j.isNull("amt")) null else j.optLong("amt"),
            mint = if (j.isNull("mint")) null else j.optString("mint").takeIf { it.isNotEmpty() },
            recipient = if (j.isNull("rcpt")) null else j.optString("rcpt").takeIf { it.isNotEmpty() },
            walletErrorKind = if (j.isNull("werr")) null else j.optString("werr").takeIf { it.isNotEmpty() },
            createdAtMs = j.optLong("ts", 0L)
        )
    }.getOrNull()

    private companion object {
        const val KEY_PREFIX = "intent:"
    }
}
