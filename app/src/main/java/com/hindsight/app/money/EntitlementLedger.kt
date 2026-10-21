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
 * Shell-local entitlement + receipt ledger, persisted in the AC-02
 * encrypted store.
 *
 * Grants happen here and nowhere else, and only [grantFromVerifiedReceipt]
 * creates one — it is called exclusively with a receipt the
 * [SettlementVerifier] has already re-verified on-chain. The grant is
 * exactly-once per idempotency key (AC-06): re-granting the same key
 * returns the original receipt and never extends or duplicates it.
 */
class EntitlementLedger(
    private val store: SecureKeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun receiptForSignature(signature: String): Receipt? =
        store.get(KEY_RECEIPT_PREFIX + signature)?.let { decodeReceipt(it) }

    @Synchronized
    fun receiptForIdempotencyKey(key: String): Receipt? =
        store.get(KEY_INTENT_RECEIPT_PREFIX + key)?.let { decodeReceipt(it) }

    @Synchronized
    fun allReceipts(): List<Receipt> =
        store.keysWithPrefix(KEY_RECEIPT_PREFIX).mapNotNull { store.get(it) }
            .mapNotNull { decodeReceipt(it) }

    /** Record a receipt signature as pending settlement (pre-grant). */
    @Synchronized
    fun recordPending(signature: String, buyerIdentity: String): Boolean =
        store.put(KEY_PENDING, JSONObject().put("sig", signature).put("buyer", buyerIdentity).toString())

    @Synchronized
    fun pending(): Pair<String, String>? {
        val raw = store.get(KEY_PENDING) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val sig = json.optString("sig").takeIf { it.isNotEmpty() } ?: return null
        val buyer = json.optString("buyer").takeIf { it.isNotEmpty() } ?: return null
        return sig to buyer
    }

    @Synchronized
    fun clearPending() = store.remove(KEY_PENDING)

    /**
     * Grant a day pass from a verified receipt. Exactly-once per
     * idempotency key: a second call with the same key returns the
     * original receipt unchanged (never a double grant, never an
     * extension). Returns null if the receipt could not be durably
     * recorded — the caller must then treat the purchase as pending,
     * never as granted (fail closed).
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
        val encoded = encodeReceipt(receipt)
        if (!store.put(KEY_RECEIPT_PREFIX + signature, encoded)) return null
        if (!store.put(KEY_INTENT_RECEIPT_PREFIX + idempotencyKey, encoded)) return null
        if (pending()?.first == signature) clearPending()
        return receipt
    }

    /** Current entitlement for [identity] at the current time. */
    @Synchronized
    fun entitlementFor(identity: String?): Entitlement {
        val now = clock()
        val mine = allReceipts().filter { it.buyerIdentity == identity }
        val active = mine.filter { it.expiresAtMs > now }.maxByOrNull { it.expiresAtMs }
        val pendingSig = pending()?.takeIf { it.second == identity }?.first
        return Entitlement(
            passActive = active != null,
            passExpiresAtMs = active?.expiresAtMs ?: 0L,
            pendingSignature = pendingSig,
            boundIdentity = identity
        )
    }

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

    private companion object {
        const val KEY_RECEIPT_PREFIX = "receipt:"
        const val KEY_INTENT_RECEIPT_PREFIX = "intent-receipt:"
        const val KEY_PENDING = "pending-receipt"
    }
}
