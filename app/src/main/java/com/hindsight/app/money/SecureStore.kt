package com.hindsight.app.money

/**
 * The persistence seam for everything the money build stores
 * (AC-02): the MWA auth token, the entitlement cache, purchase
 * receipts, purchase intents, and the rookie claim ledger.
 *
 * Contract every implementation must keep:
 *  - Values at rest are ciphertext. The Android implementation is
 *    [com.hindsight.app.wallet.EncryptedPaperMoonStore], backed by
 *    EncryptedSharedPreferences (Android Keystore). No plaintext
 *    SharedPreferences copy of any stored value may exist.
 *  - [put] is a synchronous, durable commit: it returns true only
 *    after the value is persisted. Ledger code treats a false return
 *    as "not recorded" and fails closed (no grant, no claim).
 *  - The store's files are excluded from cloud backup and
 *    device-to-device transfer (the v0.1.3 G-2 rules, extended to
 *    the new store in res/xml).
 *
 * The in-memory implementation exists for tests and the verification
 * harness; it is never wired into the app.
 */
interface SecureKeyValueStore {
    fun get(key: String): String?
    /** Durable commit. False = not recorded. */
    fun put(key: String, value: String): Boolean
    fun remove(key: String)
    fun keysWithPrefix(prefix: String): Set<String>
}

/** Test/harness store. NOT for production wiring. */
class InMemorySecureStore : SecureKeyValueStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String): String? = map[key]
    @Synchronized override fun put(key: String, value: String): Boolean {
        map[key] = value
        return true
    }
    @Synchronized override fun remove(key: String) { map.remove(key) }
    @Synchronized override fun keysWithPrefix(prefix: String): Set<String> =
        map.keys.filter { it.startsWith(prefix) }.toSet()
}
