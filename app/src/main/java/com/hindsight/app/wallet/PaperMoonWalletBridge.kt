package com.hindsight.app.wallet

import androidx.activity.ComponentActivity
import android.webkit.JavascriptInterface
import com.hindsight.app.PaperMoonApp
import com.hindsight.app.money.MoneyConfig
import com.hindsight.app.money.PurchaseCoordinator
import com.hindsight.app.money.PurchaseResult
import com.hindsight.app.money.ReceivingWalletConfig
import com.hindsight.app.money.SendOutcome
import com.hindsight.app.money.ShellVerifier
import com.hindsight.app.money.TxFailureKind
import com.hindsight.app.money.WalletSender
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The seam between the game (running in the WebView) and the native
 * shell. Exposed to JavaScript as `window.PaperMoonWallet`.
 *
 * MONEY BUILD (v0.2.0, bridge protocol 2). What changed and why:
 *
 *  - The raw `signMessage` method is GONE (AC-01, sweep MAJOR 1).
 *    No bridge method accepts page-supplied text for signing. Sign-in
 *    is [requestSignIn]: the shell issues its own challenge
 *    (Shell Verifier), the wallet signs that exact payload, the
 *    shell verifies it, and the bound identity is established.
 *  - The stub `signTransaction` method is GONE (AC-03). The page
 *    cannot submit a transaction at all. Purchases run through
 *    [requestPurchase] carrying an item identifier ONLY — never an
 *    amount, address, or asset — into the shell-owned flow (native
 *    sheet, shell-built transaction, on-chain settlement check).
 *  - Entitlement is read-only from here: [getEntitlement] reports
 *    what the shell's ledger holds; the page can display it and can
 *    never assert it (AC-05). The rookie grant is claimed/consumed
 *    through the shell ([claimRookie] / [consumeRookieRide], AC-07).
 *
 * Calling convention (async methods): call with a unique requestId,
 * get an ack, and receive the result via
 * `window.__paperMoonWalletResolve(requestId, resultJson)`.
 * Nothing here ever touches a private key — signing happens inside
 * the Seeker's Seed Vault wallet through Mobile Wallet Adapter.
 */
class PaperMoonWalletBridge(
    private val activity: ComponentActivity,
    private val walletManager: WalletManager,
    private val evaluateJs: (script: String) -> Unit
) {
    private val app = activity.applicationContext as PaperMoonApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sender = ActivityResultSender(activity)

    // ------------------------------------------------------------------
    // Synchronous getters (safe to call any time)
    // ------------------------------------------------------------------

    /** Bridge protocol version. The game can feature-detect on this. */
    @JavascriptInterface
    fun getVersion(): String = "2"

    @JavascriptInterface
    fun isConnected(): Boolean = walletManager.isConnected

    /** Base58 wallet address, or "" when not connected. */
    @JavascriptInterface
    fun getPublicKey(): String = walletManager.publicKeyBase58 ?: ""

    /** The AC-01 bound identity (Base58), or "" when unbound. Binding
     *  is established only by a verified shell challenge — connecting
     *  a wallet alone does not bind. */
    @JavascriptInterface
    fun getBoundIdentity(): String = app.shellVerifier.boundIdentity ?: ""

    /**
     * The single price source, rendered for the bundle's labels
     * (AC-09): the bundle displays these values and holds no price
     * literal of its own for the purchase path.
     */
    @JavascriptInterface
    fun getPriceInfo(): String = JSONObject()
        .put("itemId", MoneyConfig.ITEM_DAY_PASS)
        .put("itemDisplay", MoneyConfig.ITEM_DISPLAY_NAME)
        .put("usdAnchor", MoneyConfig.priceUsdDisplay())
        .put("railAmount", MoneyConfig.priceRailDisplay())
        .put("conversionBasis", MoneyConfig.CONVERSION_BASIS)
        .put("railOpen", ReceivingWalletConfig.railOpen)
        .toString()

    /**
     * What the shell's ledger says this user holds (AC-05 item 5:
     * the visible entitlement state). Read-only; the page cannot
     * write any of this. `rookieRemaining` is 0 until claimed.
     */
    @JavascriptInterface
    fun getEntitlement(): String {
        val identity = app.shellVerifier.boundIdentity
        val entitlement = app.entitlementLedger.entitlementFor(identity)
        return JSONObject()
            .put("bound", identity != null)
            .put("passActive", entitlement.passActive)
            .put("passExpiresAtMs", entitlement.passExpiresAtMs)
            .put("pending", entitlement.pendingSignature != null)
            .put("rookieClaimed", identity != null && app.rookieLedger.hasClaimed(identity))
            .put("rookieRemaining", if (identity != null) app.rookieLedger.remainingFor(identity) else 0)
            .toString()
    }

    // ------------------------------------------------------------------
    // Async operations (ack immediately, resolve via __paperMoonWalletResolve)
    // ------------------------------------------------------------------

    /**
     * Start the native MWA connect flow. Resolves with
     * `{ ok: true, data: { publicKey } }`. Connecting does NOT bind
     * identity — see [requestSignIn].
     */
    @JavascriptInterface
    fun connectWallet(requestId: String): String {
        scope.launch {
            walletManager.connect(sender).fold(
                onSuccess = { pubkey ->
                    resolveOk(requestId, JSONObject().put("publicKey", pubkey))
                },
                onFailure = { e ->
                    resolveErr(requestId, e.message ?: "connect failed")
                }
            )
        }
        return ack(requestId)
    }

    /**
     * AC-01 sign-in: the shell issues a challenge, the connected
     * wallet signs the shell-built payload, the Shell Verifier
     * verifies it and binds the identity. Resolves with
     * `{ ok: true, data: { publicKey } }` on a verified bind, or a
     * named refusal. The page supplies nothing but the requestId.
     */
    @JavascriptInterface
    fun requestSignIn(requestId: String): String {
        scope.launch(Dispatchers.IO) {
            val pubkey = walletManager.publicKeyBase58
            if (pubkey == null) {
                resolveErr(requestId, "not_connected: connect a wallet before sign-in")
                return@launch
            }
            val challenge = app.shellVerifier.issueChallenge()
            walletManager.signShellChallenge(sender, challenge.payload).fold(
                onSuccess = { signature ->
                    when (val result = app.shellVerifier.verifyAndBind(challenge.handle, pubkey, signature)) {
                        is ShellVerifier.BindResult.Bound ->
                            resolveOk(requestId, JSONObject().put("publicKey", result.identity))
                        is ShellVerifier.BindResult.Refused ->
                            resolveErr(requestId, "bind_refused: ${result.reason.name}")
                    }
                },
                onFailure = { e -> resolveErr(requestId, e.message ?: "sign-in failed") }
            )
        }
        return ack(requestId)
    }

    /**
     * Claim the identity-bound rookie grant (AC-07). Exactly once per
     * bound identity; re-claims return the existing remaining count.
     */
    @JavascriptInterface
    fun claimRookie(requestId: String): String {
        val identity = app.shellVerifier.boundIdentity
        if (identity == null) {
            scope.launch { resolveErr(requestId, "not_bound: sign in to claim the rookie grant") }
            return ack(requestId)
        }
        scope.launch {
            val claim = app.rookieLedger.claim(identity)
            if (claim != null) {
                resolveOk(requestId, JSONObject().put("rookieRemaining", claim.remaining))
                postEntitlementChanged()
            } else {
                resolveErr(requestId, "claim_failed: the grant could not be recorded")
            }
        }
        return ack(requestId)
    }

    /**
     * Consume one rookie ride. The bundle calls this ONLY after a
     * ride has been successfully built (AC-08 item 3:
     * decrement-after-build). Resolves the remaining count.
     */
    @JavascriptInterface
    fun consumeRookieRide(requestId: String): String {
        val identity = app.shellVerifier.boundIdentity
        if (identity == null) {
            scope.launch { resolveErr(requestId, "not_bound") }
            return ack(requestId)
        }
        scope.launch {
            val remaining = app.rookieLedger.consumeOne(identity)
            if (remaining >= 0) {
                resolveOk(requestId, JSONObject().put("rookieRemaining", remaining))
                postEntitlementChanged()
            } else {
                resolveErr(requestId, "no_rookie_rides")
            }
        }
        return ack(requestId)
    }

    /**
     * AC-04 purchase: the page passes an item identifier ONLY. The
     * shell runs the whole flow — fail-closed receiving-wallet gate,
     * idempotency, native confirmation sheet with shell-side values,
     * shell-built transaction, wallet approval, on-chain settlement
     * verification — and resolves with the outcome. While the
     * dedicated receiving wallet is unconfigured this resolves
     * `rail-closed` and nothing is sheeted, built, or charged.
     */
    @JavascriptInterface
    fun requestPurchase(requestId: String, itemId: String): String {
        scope.launch(Dispatchers.IO) {
            val identity = app.shellVerifier.boundIdentity
            val coordinator = PurchaseCoordinator(
                flow = app.purchaseFlow,
                ledger = app.entitlementLedger,
                settlement = app.settlementVerifier,
                blockhashProvider = app.rpcReader,
                walletSender = MwaWalletSender(),
                sheetConfirmer = app.purchaseSheetController
            )
            val data = JSONObject()
            when (val result = coordinator.purchase(itemId, identity)) {
                is PurchaseResult.Granted -> {
                    data.put("state", "granted")
                        .put("signature", result.receipt.signature)
                        .put("expiresAtMs", result.receipt.expiresAtMs)
                    resolveOk(requestId, data)
                }
                is PurchaseResult.Pending -> {
                    data.put("state", "pending")
                        .put("signature", result.signature)
                        .put("verdict", result.verdict.name)
                        .put("message", "Payment sent — your pass activates when settlement verifies on-chain. If you reopen the app, it reconciles automatically.")
                    resolveOk(requestId, data)
                }
                is PurchaseResult.Failed -> {
                    data.put("state", "failed")
                        .put("errorClass", result.errorClass.name)
                    resolveErr(requestId, result.errorClass.userMessage, data)
                }
                is PurchaseResult.RailClosed -> {
                    data.put("state", "rail-closed")
                    resolveErr(requestId, ReceivingWalletConfig.RAIL_CLOSED_MESSAGE, data)
                }
                is PurchaseResult.UnknownItem -> {
                    data.put("state", "failed")
                    resolveErr(requestId, "unknown_item", data)
                }
                is PurchaseResult.AlreadyInFlight -> {
                    data.put("state", "in-flight")
                    resolveErr(requestId, "A purchase is already in progress.", data)
                }
                is PurchaseResult.NotBound -> {
                    data.put("state", "failed")
                    resolveErr(requestId, "not_bound: sign in to purchase", data)
                }
            }
            postEntitlementChanged()
        }
        return ack(requestId)
    }

    /** Wallet sender for the coordinator: preflight balance (the
     *  INSUFFICIENT_FUNDS class), then the real MWA sign-and-send.
     *  Runs on the coordinator's IO thread; the manager call is
     *  bridged with runBlocking because MWA is suspend-based. */
    private inner class MwaWalletSender : WalletSender {
        override fun sendTransaction(serializedTransaction: ByteArray): SendOutcome {
            val buyer = walletManager.publicKeyBase58 ?: return SendOutcome.Failed(TxFailureKind.WALLET_NO_WALLET_FOUND)
            val balance = runCatching { app.rpcReader.usdcBalanceBaseUnits(buyer) }.getOrNull()
            if (balance != null && balance < MoneyConfig.PRICE_USDC_BASE_UNITS) {
                return SendOutcome.Failed(TxFailureKind.INSUFFICIENT_BALANCE_PREFLIGHT)
            }
            return runBlocking {
                walletManager.signAndSendTransactions(sender, listOf(serializedTransaction)).fold(
                    onSuccess = { signatures ->
                        signatures.firstOrNull()?.let { SendOutcome.Sent(it) }
                            ?: SendOutcome.Failed(TxFailureKind.OTHER)
                    },
                    onFailure = { e ->
                        SendOutcome.Failed(
                            (e as? WalletUiException)?.failureKind ?: TxFailureKind.OTHER
                        )
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private fun ack(requestId: String): String =
        JSONObject()
            .put("accepted", true)
            .put("requestId", requestId)
            .toString()

    private fun resolveOk(requestId: String, data: JSONObject) {
        val payload = JSONObject().put("ok", true).put("data", data).toString()
        postResolve(requestId, payload)
    }

    private fun resolveErr(requestId: String, error: String, data: JSONObject? = null) {
        val payload = JSONObject().put("ok", false).put("error", error)
        if (data != null) payload.put("data", data)
        postResolve(requestId, payload.toString())
    }

    private fun postResolve(requestId: String, resultJson: String) {
        // JSONObject.quote handles escaping; the game parses the string back.
        val script =
            "window.__paperMoonWalletResolve && " +
                "window.__paperMoonWalletResolve(${JSONObject.quote(requestId)}, ${JSONObject.quote(resultJson)});"
        evaluateJs(script)
    }

    private fun postEntitlementChanged() {
        evaluateJs(
            "window.__paperMoonEntitlementChanged && window.__paperMoonEntitlementChanged();"
        )
    }
}
