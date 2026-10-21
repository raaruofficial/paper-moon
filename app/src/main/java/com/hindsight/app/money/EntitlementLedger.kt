package com.hindsight.app.money

import org.json.JSONObject

/**
 * What the shell — and only the shell — knows about entitlement
 * (AC-05 item 3). The game reads this through bridge getters; it can
 * display it and can never assert it.
 */
data class Entitlement(
    val passActive: Boolean,
    val passExpiresAtMs: Long,
    val pendingSignature: String?,
    val boundIdentity: String?
) {
    companion object {
        val NONE = Entitlement(false, 0L, null, null)
    }
}

/** A purchase receipt. The transaction signature IS the receipt (AC-05 item 1). */
data class Receipt(
    val signature: String,
    val idempotencyKey: String,
    val itemId: String,
    val buyerIdentity: String,
    val grantedAtMs: Long,
    val expiresAtMs: Long
)

/**
 * One entry in the pending-settlement journal. Every broadcast
 * purchase gets its OWN entry, keyed by its signature: entries are
 * never overwritten by a later purchase, and an entry leaves ACTIVE
 * only by resolving (GRANTED when the grant lands, REFUSED when the
 * chain definitively refuses settlement) — the record itself is
 * kept, so a resolved purchase stays auditable instead of being
 * silently discarded.
 */
data class PendingEntry(
    val signature: String,
    val buyerIdentity: String,
    val idempotencyKey: String,
    val itemId: String,
    val recordedAtMs: Long,
    val status: Status,
    val note: String?
) {
    enum class Status { ACTIVE, GRANTED, REFUSED }
}

/**
 * A recorded non-delivery (Terms §7: every recorded non-delivery
 * enters the remedy queue — none is silently lost). Written when a
 * payment-shaped event delivers nothing, e.g. a purchase attempt
 * against an already-active pass (duplicate payment, §7(b)).
 */
data class NonDelivery(
    val itemId: String,
    val buyerIdentity: String,
    val reason: String,
    val detail: String?,
    val recordedAtMs: Long
)

/**
 * Shell-local entitlement + receipt ledger, persisted in the AC-02
 * encrypted store.
 *
 * Grants happen here and nowhere else, and only [grantFromVerifiedReceipt]
 * creates one — it is called exclusively with a receipt the
 * [SettlementVerifier] has already re-verified on-chain. The grant is
 * exactly-once per idempotency key (AC-06): re-granting the same key
 * returns the original receipt and never extends or duplicates it.
 *
 * Durability shape (bankai #3 MAJOR-2 rebuild): a grant is ONE
 * durable write — the receipt record, keyed by signature, is the
 * source of truth, and the by-key lookup is derived from it by scan,
 * so the two can never disagree (the previous two-put grant could
 * strand the intent index and report "pending" for a pass that was
 * already active). Pending settlement is a per-signature JOURNAL,
 * not a single slot: a second purchase can never erase the record
 * of an earlier sent-but-unsettled one, and reconciliation resolves
 * every ACTIVE entry independently.
 */
class EntitlementLedger(
    private val store: SecureKeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun receiptForSignature(signature: String): Receipt? =
        store.get(KEY_RECEIPT_PREFIX + signature)?.let { decodeReceipt(it) }

    /** Derived from the receipt records themselves (see class doc):
     *  there is no separate index that could be orphaned. */
    @Synchronized
    fun receiptForIdempotencyKey(key: String): Receipt? =
        allReceipts().firstOrNull { it.idempotencyKey == key }

    @Synchronized
    fun allReceipts(): List<Receipt> =
        store.keysWithPrefix(KEY_RECEIPT_PREFIX).mapNotNull { store.get(it) }
            .mapNotNull { decodeReceipt(it) }

    // ------------------------------------------------------------------
    // Pending-settlement journal
    // ------------------------------------------------------------------

    /**
     * Journal a broadcast purchase as pending settlement. Idempotent
     * per signature: re-recording the same signature reports success
     * and never overwrites the existing entry. Returns false only
     * when the record could not be durably written — callers must
     * treat that as "not journaled" and rely on the intent outcome
     * record (which carries the same signature) for recovery.
     */
    @Synchronized
    fun recordPending(
        signature: String,
        buyerIdentity: String,
        idempotencyKey: String = "",
        itemId: String = MoneyConfig.ITEM_DAY_PASS
    ): Boolean {
        migrateLegacyPending()
        if (pendingEntry(signature) != null) return true
        return store.put(
            KEY_PENDING_PREFIX + signature,
            encodePending(
                PendingEntry(
                    signature, buyerIdentity, idempotencyKey, itemId,
                    clock(), PendingEntry.Status.ACTIVE, null
                )
            )
        )
    }

    /** All journal entries, oldest first. Resolved entries are kept. */
    @Synchronized
    fun pendingEntries(): List<PendingEntry> {
        migrateLegacyPending()
        return store.keysWithPrefix(KEY_PENDING_PREFIX)
            .mapNotNull { store.get(it) }
            .mapNotNull { decodePending(it) }
            .sortedWith(compareBy({ it.recordedAtMs }, { it.signature }))
    }

    /** Journal entries still awaiting settlement resolution. */
    @Synchronized
    fun activePendingEntries(): List<PendingEntry> =
        pendingEntries().filter { it.status == PendingEntry.Status.ACTIVE }

    @Synchronized
    fun pendingEntry(signature: String): PendingEntry? =
        store.get(KEY_PENDING_PREFIX + signature)?.let { decodePending(it) }

    /** The oldest ACTIVE pending purchase, if any (legacy accessor). */
    @Synchronized
    fun pending(): Pair<String, String>? =
        activePendingEntries().firstOrNull()?.let { it.signature to it.buyerIdentity }

    /** The oldest ACTIVE pending signature for [identity], if any. */
    @Synchronized
    fun pendingFor(identity: String): String? =
        activePendingEntries().firstOrNull { it.buyerIdentity == identity }?.signature

    /** Resolve a journal entry as granted (the receipt is durable). */
    @Synchronized
    fun markPendingGranted(signature: String): Boolean =
        transitionPending(signature, PendingEntry.Status.GRANTED, null)

    /** Resolve a journal entry as definitively refused on-chain. */
    @Synchronized
    fun markPendingRefused(signature: String, verdictName: String): Boolean =
        transitionPending(signature, PendingEntry.Status.REFUSED, verdictName)

    private fun transitionPending(
        signature: String,
        status: PendingEntry.Status,
        note: String?
    ): Boolean {
        val entry = pendingEntry(signature) ?: return false
        if (entry.status == status) return true
        return store.put(
            KEY_PENDING_PREFIX + signature,
            encodePending(entry.copy(status = status, note = note ?: entry.note))
        )
    }

    /**
     * Fold the pre-journal single-slot pending record (key
     * "pending-receipt") into the journal, once, so a signature
     * recorded by an older build is never stranded by the upgrade.
     */
    private fun migrateLegacyPending() {
        val raw = store.get(KEY_LEGACY_PENDING) ?: return
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val sig = json?.optString("sig")?.takeIf { it.isNotEmpty() }
        val buyer = json?.optString("buyer")?.takeIf { it.isNotEmpty() }
        if (sig != null && buyer != null && pendingEntry(sig) == null) {
            store.put(
                KEY_PENDING_PREFIX + sig,
                encodePending(
                    PendingEntry(
                        sig, buyer, "", MoneyConfig.ITEM_DAY_PASS,
                        clock(), PendingEntry.Status.ACTIVE,
                        "migrated from the legacy single-slot pending record"
                    )
                )
            )
        }
        store.remove(KEY_LEGACY_PENDING)
    }

    // ------------------------------------------------------------------
    // Non-delivery records (Terms §7 remedy queue)
    // ------------------------------------------------------------------

    /**
     * Record a non-delivery durably. Returns false when the record
     * could not be written — the caller decides how to surface that;
     * the record is never fabricated in memory instead.
     */
    @Synchronized
    fun recordNonDelivery(
        itemId: String,
        buyerIdentity: String,
        reason: String,
        detail: String?
    ): Boolean {
        val at = clock()
        val record = NonDelivery(itemId, buyerIdentity, reason, detail, at)
        return store.put(
            KEY_NON_DELIVERY_PREFIX + at + ":" + reason + ":" + buyerIdentity,
            encodeNonDelivery(record)
        )
    }

    @Synchronized
    fun nonDeliveries(): List<NonDelivery> =
        store.keysWithPrefix(KEY_NON_DELIVERY_PREFIX)
            .mapNotNull { store.get(it) }
            .mapNotNull { decodeNonDelivery(it) }
            .sortedBy { it.recordedAtMs }

    // ------------------------------------------------------------------
    // Grant + entitlement
    // ------------------------------------------------------------------

    /**
     * Grant a day pass from a verified receipt. Exactly-once per
     * idempotency key AND per signature: a second call returns the
     * original receipt unchanged (never a double grant, never an
     * extension). The grant is a single durable write; returns null
     * if that write fails — the caller must then treat the purchase
     * as pending, never as granted (fail closed), and reconciliation
     * will retry the grant from the journal/outcome records.
     */
    @Synchronized
    fun grantFromVerifiedReceipt(
        signature: String,
        idempotencyKey: String,
        itemId: String,
        buyerIdentity: String
    ): Receipt? {
        receiptForIdempotencyKey(idempotencyKey)?.let { return it }
        receiptForSignature(signature)?.let { return it }
        val now = clock()
        val receipt = Receipt(
            signature = signature,
            idempotencyKey = idempotencyKey,
            itemId = itemId,
            buyerIdentity = buyerIdentity,
            grantedAtMs = now,
            expiresAtMs = now + MoneyConfig.PASS_DURATION_MS
        )
        if (!store.put(KEY_RECEIPT_PREFIX + signature, encodeReceipt(receipt))) return null
        // Best-effort journal resolution: if this mark is lost, the
        // entry stays ACTIVE and reconciliation re-resolves it
        // against the durable receipt above — self-healing, and the
        // receipt remains the source of truth either way.
        markPendingGranted(signature)
        return receipt
    }

    /** Current entitlement for [identity] at the current time. */
    @Synchronized
    fun entitlementFor(identity: String?): Entitlement {
        val now = clock()
        val mine = allReceipts().filter { it.buyerIdentity == identity }
        val active = mine.filter { it.expiresAtMs > now }.maxByOrNull { it.expiresAtMs }
        val pendingSig = if (identity != null) pendingFor(identity) else null
        return Entitlement(
            passActive = active != null,
            passExpiresAtMs = active?.expiresAtMs ?: 0L,
            pendingSignature = pendingSig,
            boundIdentity = identity
        )
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    private fun encodeReceipt(r: Receipt): String = JSONObject()
        .put("sig", r.signature)
        .put("key", r.idempotencyKey)
        .put("item", r.itemId)
        .put("buyer", r.buyerIdentity)
        .put("granted", r.grantedAtMs)
        .put("expires", r.expiresAtMs)
        .toString()

    private fun decodeReceipt(raw: String): Receipt? = runCatching {
        val j = JSONObject(raw)
        Receipt(
            signature = j.getString("sig"),
            idempotencyKey = j.getString("key"),
            itemId = j.getString("item"),
            buyerIdentity = j.getString("buyer"),
            grantedAtMs = j.getLong("granted"),
            expiresAtMs = j.getLong("expires")
        )
    }.getOrNull()

    private fun encodePending(e: PendingEntry): String = JSONObject()
        .put("sig", e.signature)
        .put("buyer", e.buyerIdentity)
        .put("key", e.idempotencyKey)
        .put("item", e.itemId)
        .put("at", e.recordedAtMs)
        .put("status", e.status.name)
        .put("note", e.note ?: JSONObject.NULL)
        .toString()

    private fun decodePending(raw: String): PendingEntry? = runCatching {
        val j = JSONObject(raw)
        PendingEntry(
            signature = j.getString("sig"),
            buyerIdentity = j.getString("buyer"),
            idempotencyKey = j.optString("key"),
            itemId = j.optString("item", MoneyConfig.ITEM_DAY_PASS),
            recordedAtMs = j.optLong("at"),
            status = PendingEntry.Status.valueOf(j.getString("status")),
            note = if (j.isNull("note")) null else j.getString("note")
        )
    }.getOrNull()

    private fun encodeNonDelivery(n: NonDelivery): String = JSONObject()
        .put("item", n.itemId)
        .put("buyer", n.buyerIdentity)
        .put("reason", n.reason)
        .put("detail", n.detail ?: JSONObject.NULL)
        .put("at", n.recordedAtMs)
        .toString()

    private fun decodeNonDelivery(raw: String): NonDelivery? = runCatching {
        val j = JSONObject(raw)
        NonDelivery(
            itemId = j.getString("item"),
            buyerIdentity = j.getString("buyer"),
            reason = j.getString("reason"),
            detail = if (j.isNull("detail")) null else j.getString("detail"),
            recordedAtMs = j.getLong("at")
        )
    }.getOrNull()

    private companion object {
        const val KEY_RECEIPT_PREFIX = "receipt:"
        const val KEY_PENDING_PREFIX = "pending:"
        const val KEY_LEGACY_PENDING = "pending-receipt"
        const val KEY_NON_DELIVERY_PREFIX = "nondelivery:"
    }
}
