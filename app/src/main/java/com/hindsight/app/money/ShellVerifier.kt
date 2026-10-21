package com.hindsight.app.money

import com.hindsight.app.util.Base58
import java.security.SecureRandom

/**
 * The Shell Verifier — the named shell-local verification component
 * required by AC-01 (binding) and AC-05 (settlement). It has two
 * halves sharing one identity:
 *
 *  - Binding half (this file): constructs the sign-in challenge,
 *    verifies the wallet's ed25519 signature over it, and establishes
 *    the **bound identity**.
 *  - Settlement half ([SettlementVerifier]): re-verifies a payment
 *    transaction on-chain before any entitlement is granted.
 *
 * ## Challenge discipline (AC-01 items 1–3)
 * The challenge payload is constructed HERE and only here:
 *
 *     PAPER MOON sign-in
 *     nonce:<base58, 16 random bytes — ≥128-bit>
 *     issued-at:<epoch millis>
 *
 * No byte of page-supplied text can enter the payload: the bridge
 * exposes no method that takes page text for signing (the raw
 * `signMessage` bridge method is removed), and [issueChallenge]
 * takes no string input at all. A challenge is identified to the
 * rest of the shell by an opaque handle; the wallet signs the exact
 * payload the shell built for that handle.
 *
 * Nonces are single-use and live at most [NONCE_TTL_MS] (5 minutes,
 * the AC-01 ceiling). Verification consumes the nonce *before* the
 * signature is checked, so a failed attempt can never be retried
 * against the same challenge, and a replayed challenge always fails.
 *
 * ## Failure behavior (specified, per AC-01 item 3)
 * Every failure is a refusal with a named [Refusal] reason; the bound
 * identity is never set, cleared, or changed by a failed attempt.
 * Verification never throws across the bridge.
 *
 * ## What the bound identity unlocks (AC-01 item 4)
 * Exactly two things: the purchase surface (AC-04) and entitlement
 * reads (AC-05). Nothing else in this build keys off it.
 */
class ShellVerifier(
    private val nonceStore: NonceStore = InMemoryNonceStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val signatureVerifier: (publicKey: ByteArray, message: ByteArray, signature: ByteArray) -> Boolean =
        Ed25519::verify
) {
    /** An issued, not-yet-consumed challenge. The payload is shell-built. */
    data class Challenge(
        val handle: String,
        val payload: String,
        val issuedAtMs: Long
    )

    enum class Refusal {
        UNKNOWN_HANDLE,
        EXPIRED,
        ALREADY_USED,
        SIGNATURE_INVALID,
        WALLET_MISMATCH
    }

    sealed interface BindResult {
        data class Bound(val identity: String) : BindResult
        data class Refused(val reason: Refusal) : BindResult
    }

    @Volatile
    private var boundIdentityBacking: String? = null

    /** The bound identity (wallet public key, Base58), or null when unbound. */
    val boundIdentity: String?
        get() = boundIdentityBacking

    /**
     * Issue a fresh challenge. Takes no page input by construction.
     * The caller (bridge) shows [Challenge.payload] to the wallet via
     * MWA detached message signing and later passes the returned
     * signature to [verifyAndBind] under [Challenge.handle].
     */
    @Synchronized
    fun issueChallenge(): Challenge {
        val nonceBytes = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val nonce = Base58.encode(nonceBytes)
        val issuedAt = clock()
        val handle = Base58.encode(ByteArray(HANDLE_BYTES).also { random.nextBytes(it) })
        val payload = buildString {
            append(PURPOSE_PREFIX)
            append("\nnonce:").append(nonce)
            append("\nissued-at:").append(issuedAt)
        }
        nonceStore.put(handle, StoredNonce(nonce = nonce, issuedAtMs = issuedAt, consumed = false))
        return Challenge(handle = handle, payload = payload, issuedAtMs = issuedAt)
    }

    /**
     * Verify [signatureBase58] over the payload issued under [handle],
     * for the connected wallet [walletPublicKeyBase58]. On success the
     * bound identity becomes that wallet's public key.
     *
     * The nonce is consumed first (see class doc): replay, expiry, and
     * unknown handles are all refusals, and a signature failure spends
     * the challenge too.
     */
    @Synchronized
    fun verifyAndBind(
        handle: String,
        walletPublicKeyBase58: String,
        signatureBase58: String
    ): BindResult {
        val stored = nonceStore.get(handle)
            ?: return BindResult.Refused(Refusal.UNKNOWN_HANDLE)
        if (stored.consumed) return BindResult.Refused(Refusal.ALREADY_USED)
        // Consume before checking anything else: single-use is absolute.
        nonceStore.put(handle, stored.copy(consumed = true))
        if (clock() - stored.issuedAtMs > NONCE_TTL_MS) {
            return BindResult.Refused(Refusal.EXPIRED)
        }
        val payload = buildString {
            append(PURPOSE_PREFIX)
            append("\nnonce:").append(stored.nonce)
            append("\nissued-at:").append(stored.issuedAtMs)
        }
        val publicKey = runCatching { Base58.decode(walletPublicKeyBase58) }.getOrNull()
            ?: return BindResult.Refused(Refusal.WALLET_MISMATCH)
        val signature = runCatching { Base58.decode(signatureBase58) }.getOrNull()
            ?: return BindResult.Refused(Refusal.SIGNATURE_INVALID)
        if (publicKey.size != 32) return BindResult.Refused(Refusal.WALLET_MISMATCH)
        val ok = runCatching {
            signatureVerifier(publicKey, payload.toByteArray(Charsets.UTF_8), signature)
        }.getOrDefault(false)
        if (!ok) return BindResult.Refused(Refusal.SIGNATURE_INVALID)
        boundIdentityBacking = walletPublicKeyBase58
        return BindResult.Bound(walletPublicKeyBase58)
    }

    /** Drop the bound identity (wallet disconnect). Challenges are unaffected. */
    @Synchronized
    fun unbind() {
        boundIdentityBacking = null
    }

    data class StoredNonce(val nonce: String, val issuedAtMs: Long, val consumed: Boolean)

    /** Nonce persistence seam. The shell-local store is in-memory:
     *  a challenge never needs to survive process death — death just
     *  means the user signs in again, which is the safe direction. */
    interface NonceStore {
        fun get(handle: String): StoredNonce?
        fun put(handle: String, nonce: StoredNonce)
    }

    class InMemoryNonceStore : NonceStore {
        private val map = HashMap<String, StoredNonce>()
        @Synchronized override fun get(handle: String): StoredNonce? = map[handle]
        @Synchronized override fun put(handle: String, nonce: StoredNonce) {
            map[handle] = nonce
        }
    }

    companion object {
        const val PURPOSE_PREFIX: String = "PAPER MOON sign-in"
        const val NONCE_BYTES: Int = 16 // 128-bit nonce (AC-01 floor)
        const val HANDLE_BYTES: Int = 16
        const val NONCE_TTL_MS: Long = 5L * 60L * 1000L // ≤ 5 min (AC-01)
    }
}
