package com.hindsight.app.wallet

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
        val revoked = runCatching { attemptDeauthorize() }.getOrDefault(false)
        forgetLocally()
        unbind()
        return revoked
    }
}
