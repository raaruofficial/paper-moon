package com.hindsight.app

import android.app.Application
import com.hindsight.app.wallet.WalletManager

/**
 * Application holder for app-scoped singletons.
 * The [WalletManager] lives here so the wallet session survives navigation
 * and configuration changes.
 */
class HindsightApp : Application() {
    val walletManager: WalletManager by lazy { WalletManager() }
}
