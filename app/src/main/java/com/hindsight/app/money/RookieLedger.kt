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
 *
 * Two scopes share this ledger and store. The identity-bound grant
 * above is one. The other (v0.2.4, the dApp Store resubmission fix
 * for the "app became unusable" rejection) is the DEVICE-LOCAL
 * rookie allowance: the same [MoneyConfig.ROOKIE_RIDES] rides,
 * granted once per install by the shell, playable with no wallet
 * connected, no sign-in, and no network — a fresh install is
 * playable from first open. The pre-fix build gated ALL play
 * behind the connect → sign-in → claim ceremony above, so a fresh
 * reviewer (or any wallet-less user) met a catalog they could not
 * play at all. The allowance is install-scoped because its record
 * lives in the same encrypted, backup-excluded store as everything
 * else here: nothing outside this app's private storage can carry
 * it to another install, and uninstall is the only reset.
 *
 * The scopes are reconciled by construction — they never merge.
 * The local record is keyed by a reserved non-identity value (see
 * [LOCAL_GRANT_IDENTITY]); binding a wallet neither moves, tops
 * up, nor clears it, and local play never writes an identity scope
 * (nor any receipt or entitlement — day passes remain strictly
 * bound-identity purchases through the coordinator). The bridge
 * reports and consumes exactly one scope at a time: the bound
 * identity's when an identity is bound, the local allowance when
 * none is. A user who binds and claims receives the identity
 * grant's own full set, exactly as AC-07 always promised; a user
 * who never binds simply spends the install's set. Neither path
 * renews: every scope's remaining count only ever moves down.
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

    // ------------------------------------------------------------------
    // Device-local allowance (v0.2.4 — see the class doc)
    // ------------------------------------------------------------------

    /**
     * Ensure the device-local grant exists, recording it if this
     * install has none. Exactly once per install: an existing
     * record is returned unchanged — the remaining count is never
     * renewed by re-assembly, relaunch, or session cycling.
     * Returns null only if the grant could not be durably recorded
     * (fail closed: nothing is granted). The bridge calls this when
     * it assembles — the one moment guaranteed to precede every
     * game call — so a fresh install's first entitlement read
     * already finds its rides.
     */
    @Synchronized
    fun ensureLocalGrant(): Claim? = claim(LOCAL_GRANT_IDENTITY)

    /** The device-local claim, or null when not yet granted. */
    @Synchronized
    fun localClaim(): Claim? = claimFor(LOCAL_GRANT_IDENTITY)

    /** Remaining device-local rookie rides; 0 when ungranted. */
    @Synchronized
    fun localRemaining(): Int = remainingFor(LOCAL_GRANT_IDENTITY)

    /** Whether the device-local grant has been recorded. */
    @Synchronized
    fun hasLocalGrant(): Boolean = hasClaimed(LOCAL_GRANT_IDENTITY)

    /**
     * Consume one device-local rookie ride. Same discipline as the
     * identity-bound path: the shell calls this only after the ride
     * has been built (AC-08 item 3, decrement-after-build). Returns
     * the remaining count after the consume, or -1 when no ride was
     * available / the consume could not be recorded. The grant is
     * ensured first, so a consume can never strand a fresh install
     * at a gate its own entitlement read would have opened.
     */
    @Synchronized
    fun consumeLocalOne(): Int {
        if (ensureLocalGrant() == null) return -1
        return consumeOne(LOCAL_GRANT_IDENTITY)
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

    companion object {
        private const val KEY_PREFIX = "rookie:"

        /**
         * The store-key suffix of the device-local allowance. NOT a
         * wallet identity and never confusable with one: a bound
         * identity is the Base58 encoding of a 32-byte public key,
         * and Base58's alphabet excludes both '-' and 'l', which
         * this value contains — no wallet can ever encode to it.
         * The local claim record is keyed exactly like an identity
         * claim, in its own scope, and neither scope's lifecycle
         * touches the other's record.
         */
        const val LOCAL_GRANT_IDENTITY = "local-install"
    }
}
