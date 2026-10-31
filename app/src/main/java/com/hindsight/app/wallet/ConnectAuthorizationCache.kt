package com.hindsight.app.wallet

import com.hindsight.app.money.SecureKeyValueStore
import com.solanamobile.seekerconnect.AuthorizationCache
import com.solanamobile.seekerconnect.SeekerAccount
import com.solanamobile.seekerconnect.SeekerWalletCapabilities
import com.solanamobile.seekerconnect.StoredAuthorization
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one lock discipline for the Connect session state inside
 * [EncryptedPaperMoonStore] (Gate 0 C-09 / ARCHITECT SESS-01 §1):
 * cache save/clear, the legacy-token migration, and the disconnect
 * sequence's local forget all run under this lock, so a kill
 * mid-sequence cannot leave a half-written entry that resurrects a
 * cleared authorization — the corruption-eviction law in
 * [ConnectAuthorizationCache] is the backstop, never the mechanism.
 */
internal object SessionStoreLock

/**
 * Seeker Connect's [AuthorizationCache] implemented as an adapter
 * over Paper Moon's ONE encrypted substrate (Gate 0 C-06, brief
 * §4.2): the envelope lives in [EncryptedPaperMoonStore] under the
 * single namespaced key [KEY], inheriting Keystore encryption at
 * rest and both backup/transfer exclusions. The SDK's stock
 * `SharedPreferencesAuthorizationCache` is BARRED (UMBRA S-4: plain
 * SharedPreferences — plaintext at rest in an unexcluded,
 * SDK-named file `SeekerConnectAuthorizationCache.xml`, key
 * `authorization` — read from the AAR/source of record at v0.2.0
 * and filed in the build record); the in-memory default is barred
 * with it (it would silently delete shipped restore behaviour).
 *
 * The envelope is the SDK's own serialization, stored opaquely: the
 * adapter adds NO fields of its own inside it. Complete serialized
 * field list (format version [FORMAT_VERSION] = 1, byte-compatible
 * with the stock cache's format so the vendor contract suite's
 * semantics hold): `version`, `accounts[]` (`address`, `publicKey`
 * base64, `label`), `authToken`, `walletUriBase`, `chain`,
 * `capabilities` (`maxMessagesPerRequest`,
 * `maxTransactionsPerRequest`, `supportedTransactionVersions`,
 * `features`, `supportsSignAndSendTransactions`). The learned
 * wallet base URI exists ONLY as the envelope's `walletUriBase`
 * field (C-07): written only from a wallet authorize response
 * through the Connect seam (that is where the SDK puts it), used
 * only by the SDK as its association target, and destroyed with
 * the envelope on every clear path.
 *
 * The vendor's four cache laws, proven in the money harness:
 * byte-exact round trip; corruption ⇒ evict to a clean
 * disconnected state (never crash, never retry-loop); version
 * mismatch ⇒ evict, not misread; storage failure ⇒ degrade to
 * in-memory-only for the process, never wedge the wallet surface.
 */
class ConnectAuthorizationCache(
    private val store: SecureKeyValueStore
) : AuthorizationCache {

    /** Process-lifetime fallback, engaged only when the durable
     *  store refuses a write (law 4). Never the primary path. */
    @Volatile
    private var memoryFallback: StoredAuthorization? = null

    @Volatile
    private var degradedToMemory: Boolean = false

    override suspend fun get(): StoredAuthorization? {
        // EVERY path under the one lock (TEKKADAN audit A-F2):
        // the degraded branches below are exactly where the
        // in-memory copy is authoritative, and an unlocked read
        // or write there races clear() — the SDK calls set() from
        // its transact coroutine on every wallet interaction
        // while disconnect's clear() runs on the UI coroutine.
        synchronized(SessionStoreLock) {
            if (degradedToMemory) return memoryFallback
            val raw = store.get(KEY) ?: return null
            val parsed = decode(raw)
            if (parsed == null) {
                // Corruption or a foreign/newer format version:
                // evict to a clean disconnected state (laws 2+3).
                store.remove(KEY)
                return null
            }
            return parsed
        }
    }

    override suspend fun set(authorization: StoredAuthorization) {
        synchronized(SessionStoreLock) {
            if (degradedToMemory) {
                memoryFallback = authorization
                return
            }
            val persisted = store.put(KEY, encode(authorization))
            if (!persisted) {
                // Law 4: degrade to in-memory-only for the process.
                // The superseded durable envelope is EVICTED
                // first (TEKKADAN audit A-F1): if it stayed, the
                // next process would restore the OLD authorization
                // — the masking that hides it is process-volatile,
                // so a failed re-save would silently resurrect
                // the wallet the user just replaced.
                store.remove(KEY)
                degradedToMemory = true
                memoryFallback = authorization
            }
        }
    }

    override suspend fun clear() {
        synchronized(SessionStoreLock) {
            memoryFallback = null
            store.remove(KEY)
        }
    }

    private fun encode(a: StoredAuthorization): String = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("accounts", JSONArray(a.accounts.map {
            JSONObject()
                .put("address", it.address)
                .put("publicKey", Base64.getEncoder().encodeToString(it.publicKey))
                .putOpt("label", it.label)
        }))
        .put("authToken", a.authToken)
        .putOpt("walletUriBase", a.walletUriBase)
        .put("chain", a.chain)
        .put("capabilities", JSONObject()
            .putOpt("maxMessagesPerRequest", a.capabilities.maxMessagesPerRequest)
            .putOpt("maxTransactionsPerRequest", a.capabilities.maxTransactionsPerRequest)
            .put("supportedTransactionVersions", JSONArray(a.capabilities.supportedTransactionVersions))
            .put("features", JSONArray(a.capabilities.features))
            .put("supportsSignAndSendTransactions", a.capabilities.supportsSignAndSendTransactions))
        .toString()

    private fun decode(raw: String): StoredAuthorization? = runCatching {
        val j = JSONObject(raw)
        if (j.getInt("version") != FORMAT_VERSION) return null
        val caps = j.getJSONObject("capabilities")
        val versions = caps.getJSONArray("supportedTransactionVersions")
        val features = caps.getJSONArray("features")
        val accounts = j.getJSONArray("accounts")
        StoredAuthorization(
            accounts = List(accounts.length()) { i ->
                val acc = accounts.getJSONObject(i)
                SeekerAccount(
                    acc.getString("address"),
                    Base64.getDecoder().decode(acc.getString("publicKey")),
                    if (acc.has("label") && !acc.isNull("label")) acc.getString("label") else null
                )
            },
            authToken = j.getString("authToken"),
            walletUriBase = if (j.has("walletUriBase") && !j.isNull("walletUriBase")) {
                j.getString("walletUriBase")
            } else null,
            chain = j.getString("chain"),
            capabilities = SeekerWalletCapabilities(
                maxMessagesPerRequest = if (caps.has("maxMessagesPerRequest") && !caps.isNull("maxMessagesPerRequest")) {
                    caps.getInt("maxMessagesPerRequest")
                } else null,
                maxTransactionsPerRequest = if (caps.has("maxTransactionsPerRequest") && !caps.isNull("maxTransactionsPerRequest")) {
                    caps.getInt("maxTransactionsPerRequest")
                } else null,
                supportedTransactionVersions = List(versions.length()) { versions.get(it) },
                features = List(features.length()) { features.getString(it) },
                supportsSignAndSendTransactions = caps.getBoolean("supportsSignAndSendTransactions")
            )
        )
    }.getOrNull()

    companion object {
        /** The one namespaced envelope key inside the encrypted store. */
        const val KEY: String = "seeker_connect_auth_v1"
        const val FORMAT_VERSION: Int = 1
    }
}
