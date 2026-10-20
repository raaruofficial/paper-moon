package com.hindsight.app.wallet

import android.content.Context

/**
 * On-disk home for the MWA auth token — the token only, nothing else.
 *
 * The token is a bearer credential the wallet issues to THIS app's
 * identity at connect time. Presenting it on a later launch lets the
 * wallet skip the approval dialog for an already-approved connection.
 * It is not a key: it cannot sign or move anything by itself, and the
 * wallet can revoke it at any time.
 *
 * Storage is the app's private SharedPreferences (MODE_PRIVATE — no
 * other app can read the file). Deliberately minimal: the alternative,
 * EncryptedSharedPreferences, would add the androidx.security-crypto
 * dependency; that hardening belongs in a change that can be
 * build-verified end to end, not folded blind into this one.
 */
class WalletTokenStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_AUTH_TOKEN) else putString(KEY_AUTH_TOKEN, value)
            }.apply()
        }

    private companion object {
        const val PREFS_NAME = "paper_moon_wallet"
        const val KEY_AUTH_TOKEN = "mwa_auth_token"
    }
}
