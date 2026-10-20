package com.hindsight.app

import android.app.Application
import com.hindsight.app.wallet.WalletManager
import com.hindsight.app.wallet.WalletTokenStore

/**
 * Application holder for app-scoped singletons.
 * The [WalletManager] lives here so the wallet session survives navigation
 * and configuration changes. It is wired with a [WalletTokenStore] so the
 * MWA auth token persists across process death (T1-S03).
 */
class PaperMoonApp : Application() {
    val walletManager: WalletManager by lazy {
        WalletManager(WalletTokenStore(this))
    }
}
