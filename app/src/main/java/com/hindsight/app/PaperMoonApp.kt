package com.hindsight.app

import android.app.Application
import com.hindsight.app.money.EntitlementLedger
import com.hindsight.app.money.PurchaseCoordinator
import com.hindsight.app.money.PurchaseFlow
import com.hindsight.app.money.PurchaseSheetController
import com.hindsight.app.money.RookieLedger
import com.hindsight.app.money.SendOutcome
import com.hindsight.app.money.SettlementVerifier
import com.hindsight.app.money.ShellVerifier
import com.hindsight.app.money.SolanaRpcChainReader
import com.hindsight.app.money.WalletSender
import com.hindsight.app.wallet.EncryptedPaperMoonStore
import com.hindsight.app.wallet.ConnectWalletGateway

/**
 * Application holder for app-scoped singletons.
 * The [ConnectWalletGateway] lives here (installed by MainActivity
 * before setContent, Gate 0 C-17) so the wallet session survives
 * navigation and configuration changes. Its authorization envelope
 * persists in the encrypted store across process death (AC-02).
 *
 * Money build (v0.2.0): the shell's money components live here too —
 * the Shell Verifier (AC-01), the encrypted store (AC-02), the
 * entitlement + rookie ledgers (AC-05/07), the purchase flow
 * (AC-06), and settlement verification over the public RPC (AC-05).
 * The purchase coordinator itself is assembled per purchase by the
 * bridge, because its wallet sender and sheet are activity-scoped;
 * launch reconciliation uses [reconcileCoordinator], whose sender is
 * never invoked (reconciliation only re-verifies signatures).
 */
class PaperMoonApp : Application() {
    /** The one encrypted store for token, entitlements, receipts, intents, claims. */
    val secureStore: EncryptedPaperMoonStore by lazy { EncryptedPaperMoonStore(this) }

    /**
     * The one wallet gateway (C-17): constructed exactly once per
     * process by MainActivity.onCreate BEFORE setContent and
     * installed here; Application-scoped thereafter. Null only
     * before the first activity creates it.
     */
    @Volatile
    var walletGateway: ConnectWalletGateway? = null

    fun requireWalletGateway(): ConnectWalletGateway =
        checkNotNull(walletGateway) { "wallet gateway not installed" }

    val shellVerifier: ShellVerifier by lazy { ShellVerifier() }

    val entitlementLedger: EntitlementLedger by lazy { EntitlementLedger(secureStore) }

    val rookieLedger: RookieLedger by lazy { RookieLedger(secureStore) }

    val purchaseFlow: PurchaseFlow by lazy { PurchaseFlow(secureStore) }

    val rpcReader: SolanaRpcChainReader by lazy { SolanaRpcChainReader() }

    val settlementVerifier: SettlementVerifier by lazy { SettlementVerifier(rpcReader) }

    val purchaseSheetController: PurchaseSheetController by lazy { PurchaseSheetController() }

    /** Coordinator used for launch reconciliation ONLY (ENT-4). Its
     *  sender/confirmer are inert: reconcile never sends or sheets. */
    val reconcileCoordinator: PurchaseCoordinator by lazy {
        PurchaseCoordinator(
            flow = purchaseFlow,
            ledger = entitlementLedger,
            settlement = settlementVerifier,
            blockhashProvider = rpcReader,
            walletSender = object : WalletSender {
                override fun sendTransaction(serializedTransaction: ByteArray): SendOutcome =
                    SendOutcome.Failed(com.hindsight.app.money.TxFailureKind.OTHER)
            },
            sheetConfirmer = com.hindsight.app.money.SheetConfirmer { false }
        )
    }
}
