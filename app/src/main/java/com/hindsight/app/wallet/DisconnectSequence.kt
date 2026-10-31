package com.hindsight.app.wallet

import kotlinx.coroutines.CancellationException

/**
 * The disconnect contract's sequence (Gate 0 C-09 / UMBRA S-10),
 * extracted as a pure orchestration so the money harness can prove
 * its one load-bearing property: the local forget and the
 * [com.hindsight.app.money.ShellVerifier] unbind are
 * UNCONDITIONAL — they run even when the wallet-side deauthorize
 * attempt throws — and a failed deauthorize is reported, never
 * hidden and never retried silently in the background.
 *
 * Order is the contract: (1) attempt deauthorize while a session
 * can still be opened; (2) regardless of (1)'s outcome, forget
 * locally (envelope cleared — token, accounts, and the learned
 * wallet URI with it) and unbind; (3) the caller surfaces the
 * honest residue copy when (1) failed.
 */
object DisconnectSequence {

    /**
     * @param attemptDeauthorize wallet-side revocation attempt;
     *   may throw. Its result is the truth of the revoke only.
     * @param forgetLocally clears the envelope + SDK local state.
     * @param unbind drops the shell's bound identity.
     * @return true when the deauthorize succeeded.
     */
    suspend fun run(
        attemptDeauthorize: suspend () -> Boolean,
        forgetLocally: suspend () -> Unit,
        unbind: () -> Unit
    ): Boolean {
        var cancellation: CancellationException? = null
        val revoked = try {
            attemptDeauthorize()
        } catch (e: CancellationException) {
            // Cancellation is not a revoke failure (TEKKADAN
            // audit A-F4): the forget + unbind below still run —
            // they are the contract's unconditional safety leg —
            // but the cancellation itself is honoured and
            // rethrown once they complete, never swallowed into
            // a normal "not revoked" result.
            cancellation = e
            false
        } catch (e: Exception) {
            false
        }
        forgetLocally()
        unbind()
        cancellation?.let { throw it }
        return revoked
    }
}
