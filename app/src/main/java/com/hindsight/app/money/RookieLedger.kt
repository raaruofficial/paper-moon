package com.hindsight.app.money

import org.json.JSONObject

/**
 * The identity-bound rookie grant (AC-07, consuming P6-d: first ride
 * free survives).
 *
 * The grant — [MoneyConfig.ROOKIE_RIDES] rookie rides, the first of
 * them being the free first ride — binds to the AC-01 bound identity
 * and is claimable **exactly once per identity**. The claim record is
 * shell-local, in the AC-02 encrypted store, keyed by bound identity.
 *
 * Relaunch, reinstall-restore, or session cycling does not renew it:
 * the record is durable, the claim is atomic (the record is written
 * before rides are reported, and a failed write claims nothing), and
 * the remaining count only ever moves down (rides are consumed one
 * at a time, after the ride exists — AC-08's decrement-after-build).
 *
 * An unbound user cannot claim or consume: every entry point takes a
 * non-null bound identity, and the bridge only calls here with the
 * Shell Verifier's bound identity.
 */
class RookieLedger(
    private val store: SecureKeyValueStore
) {
    data class Claim(val identity: String, val totalGranted: Int, val remaining: Int)

    @Synchronized
    fun claimFor(identity: String): Claim? =
        store.get(KEY_PREFIX + identity)?.let { decode(it) }

    /** Remaining rookie rides for [identity]; 0 when unclaimed. */
    @Synchronized
    fun remainingFor(identity: String): Int = claimFor(identity)?.remaining ?: 0

    @Synchronized
    fun hasClaimed(identity: String): Boolean = claimFor(identity) != null

    /**
     * Claim the grant for [identity]. Exactly once: a second claim
     * returns the existing record unchanged — the remaining count
     * never increases. Returns null only if the claim could not be
     * durably recorded (fail closed: nothing is granted).
     */
    @Synchronized
    fun claim(identity: String): Claim? {
        claimFor(identity)?.let { return it }
        val fresh = Claim(identity, MoneyConfig.ROOKIE_RIDES, MoneyConfig.ROOKIE_RIDES)
        return if (store.put(KEY_PREFIX + identity, encode(fresh))) fresh else null
    }

    /**
     * Consume one rookie ride for [identity]. Called by the shell
     * only after the ride has been built (AC-08 item 3,
     * decrement-after-build). Returns the remaining count after the
     * consume, or -1 when no ride was available / the consume could
     * not be recorded.
     */
    @Synchronized
    fun consumeOne(identity: String): Int {
        val current = claimFor(identity) ?: return -1
        if (current.remaining <= 0) return -1
        val next = current.copy(remaining = current.remaining - 1)
        return if (store.put(KEY_PREFIX + identity, encode(next))) next.remaining else -1
    }

    private fun encode(c: Claim): String = JSONObject()
        .put("identity", c.identity)
        .put("total", c.totalGranted)
        .put("remaining", c.remaining)
        .toString()

    private fun decode(raw: String): Claim? = runCatching {
        val j = JSONObject(raw)
        Claim(j.getString("identity"), j.getInt("total"), j.getInt("remaining"))
    }.getOrNull()

    private companion object {
        const val KEY_PREFIX = "rookie:"
    }
}
