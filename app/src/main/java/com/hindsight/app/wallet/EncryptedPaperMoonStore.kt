package com.hindsight.app.wallet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hindsight.app.money.SecureKeyValueStore

/**
 * The AC-02 encrypted persistence layer.
 *
 * Everything the money build persists — the MWA auth token,
 * entitlement cache, purchase receipts, purchase intents, and the
 * rookie claim ledger — lives in this one Keystore-backed
 * [EncryptedSharedPreferences] store. Keys and values are both
 * encrypted (AES-256 SIV keys / AES-256 GCM values, master key in
 * the Android Keystore). There is no plaintext SharedPreferences
 * copy of any of it: the legacy plaintext token prefs
 * (`paper_moon_wallet`) are deleted on first init.
 *
 * Backup/transfer posture (v0.1.3 G-2, extended): this store's files
 * — `paper_moon_secure.xml` and the EncryptedSharedPreferences keyset
 * file — are excluded from cloud backup and device-to-device
 * transfer in res/xml/backup_rules.xml and
 * res/xml/data_extraction_rules.xml, exactly as the token prefs were.
 */
class EncryptedPaperMoonStore(context: Context) : SecureKeyValueStore {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        // One-time migration hygiene: the v0.1.3 plaintext token store
        // must not survive alongside the encrypted store (AC-02 item
        // 1: no plaintext copy survives). The token itself is NOT
        // migrated — a bearer token copied between stores is a copy;
        // the wallet simply re-issues one on the next connect.
        runCatching {
            appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun get(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    override fun put(key: String, value: String): Boolean =
        runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

    override fun remove(key: String) {
        runCatching { prefs.edit().remove(key).commit() }
    }

    override fun keysWithPrefix(prefix: String): Set<String> =
        runCatching { prefs.all.keys.filter { it.startsWith(prefix) }.toSet() }
            .getOrDefault(emptySet())

    companion object {
        const val PREFS_NAME = "paper_moon_secure"
        private const val LEGACY_PREFS_NAME = "paper_moon_wallet"
    }
}
