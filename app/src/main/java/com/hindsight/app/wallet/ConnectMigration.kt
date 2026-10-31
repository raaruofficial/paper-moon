package com.hindsight.app.wallet

import com.hindsight.app.money.SecureKeyValueStore

/**
 * The store's ONE migration seam (Gate 0 C-10 / ARCHITECT SESS-01
 * §2): every storage migration for the Connect build executes
 * inside [runOnce], once, idempotently, under [SessionStoreLock] —
 * the same lock discipline as the money state. A second ad-hoc
 * migration path anywhere else is a structural defect.
 *
 * The v0.2.10 migration is the legacy MWA token. The bare token is
 * NEVER fabricated into a Connect authorization object (brief
 * §4.3 rule 1's seeding proof was not carried by the §2.3 proof
 * build, so the ruled default stands): on first launch with a
 * legacy token present, the token is deleted and the user makes
 * one fresh consent. Entitlements and rookie state never depended
 * on the token and are untouched. When the deletion costs the
 * user their silent-restore, a one-time notice flag is left for
 * the wallet surface's point-of-need reconnect prompt (C-26a);
 * a live Connect envelope means the user already re-consented
 * and no prompt is owed.
 */
object ConnectMigration {

    /** The pre-Connect token key (WalletTokenStore's key, retired
     *  with that class at this migration). */
    const val LEGACY_TOKEN_KEY: String = "mwa_auth_token"

    /** One-time point-of-need notice flag (C-26a). */
    const val NOTICE_KEY: String = "seeker_connect_reconnect_notice"

    /**
     * Run the migration if it has not run. Idempotent: after the
     * first pass the legacy key is gone and later calls are
     * no-ops. Returns true when a legacy token was deleted on
     * this pass.
     */
    fun runOnce(store: SecureKeyValueStore): Boolean = synchronized(SessionStoreLock) {
        // Presence is the only fact read: the legacy value itself
        // is never read into an authorization, logged, or copied —
        // deletion is the entire migration.
        if (store.get(LEGACY_TOKEN_KEY) == null) return false
        val hasEnvelope = store.get(ConnectAuthorizationCache.KEY) != null
        store.remove(LEGACY_TOKEN_KEY)
        if (!hasEnvelope) {
            store.put(NOTICE_KEY, "1")
        }
        true
    }

    /** The reconnect prompt is owed (shown once, at point of need). */
    fun noticePending(store: SecureKeyValueStore): Boolean =
        store.get(NOTICE_KEY) != null

    /** The prompt was shown/dismissed; it never nags again. */
    fun dismissNotice(store: SecureKeyValueStore) {
        synchronized(SessionStoreLock) { store.remove(NOTICE_KEY) }
    }
}
