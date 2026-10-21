package com.hindsight.app.wallet

import android.content.Context
import com.hindsight.app.money.SecureKeyValueStore

/**
 * On-disk home for the MWA auth token — the token only, nothing else.
 *
 * The token is a bearer credential the wallet issues to THIS app's
 * identity at connect time. Presenting it on a later launch lets the
 * wallet skip the approval dialog for an already-approved connection.
 * It is not a key: it cannot sign or move anything by itself, and the
 * wallet can revoke it at any time.
 *
 * MONEY BUILD (AC-02): storage is now the Keystore-backed
 * [EncryptedPaperMoonStore] — the v0.1.3 plaintext SharedPreferences
 * copy is gone (and is wiped on the encrypted store's first init).
 * The token rides the same encrypted store as entitlements and
 * receipts, under its own key, and the G-2 backup/transfer
 * exclusions cover the store's files.
 */
class WalletTokenStore(
    private val store: SecureKeyValueStore
) {
    constructor(context: Context) : this(EncryptedPaperMoonStore(context))

    var authToken: String?
        get() = store.get(KEY_AUTH_TOKEN)
        set(value) {
            if (value == null) store.remove(KEY_AUTH_TOKEN) else store.put(KEY_AUTH_TOKEN, value)
        }

    private companion object {
        const val KEY_AUTH_TOKEN = "mwa_auth_token"
    }
}
