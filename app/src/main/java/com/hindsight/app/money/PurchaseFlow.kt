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
    UNKNOWN
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
    val buyerIdentity: String? = null
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
        if (outcome.state == PurchaseState.GRANTED ||
            outcome.state == PurchaseState.FAILED ||
            outcome.state == PurchaseState.PENDING_SETTLEMENT
        ) {
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
     * Launch reconcile (ENT-4): any intent left in a non-terminal,
     * post-send state at process death becomes UNKNOWN, so the launch
     * gate fails closed with a visible recovery path until settlement
     * reconciliation resolves it. Returns the unknown outcomes.
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
                persistOutcome(marked)
                unknown.add(marked)
            }
        }
        inFlight.clear()
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
        .toString()

    private fun decode(raw: String): PurchaseOutcome? = runCatching {
        val j = JSONObject(raw)
        PurchaseOutcome(
            idempotencyKey = j.getString("key"),
            state = PurchaseState.valueOf(j.getString("state")),
            signature = if (j.isNull("sig")) null else j.getString("sig"),
            errorClass = if (j.isNull("err")) null else TxErrorClass.valueOf(j.getString("err")),
            message = j.getString("msg"),
            buyerIdentity = if (j.isNull("buyer")) null else j.getString("buyer")
        )
    }.getOrNull()

    private companion object {
        const val KEY_PREFIX = "intent:"
    }
}
