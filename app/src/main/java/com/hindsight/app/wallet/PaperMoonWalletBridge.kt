package com.hindsight.app.wallet

import androidx.activity.ComponentActivity
import android.webkit.JavascriptInterface
import com.hindsight.app.PaperMoonApp
import com.hindsight.app.money.BlockhashProvider
import com.hindsight.app.money.BuildContext
import com.hindsight.app.money.BuildContextSource
import com.hindsight.app.money.MoneyConfig
import com.hindsight.app.money.PurchaseCoordinator
import com.hindsight.app.money.PurchaseResult
import com.hindsight.app.money.ReceivingWalletConfig
import com.hindsight.app.money.SendOutcome
import com.hindsight.app.money.ShellVerifier
import com.hindsight.app.money.TxFailureKind
import com.hindsight.app.money.WalletSender
import com.hindsight.app.util.Base58
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
 *  - First-launch playability (v0.2.4): assembling this bridge
 *    ensures the shell's device-local rookie allowance (see the
 *    RookieLedger class doc), so a fresh install — no wallet, no
 *    sign-in, no network — can play its rookie rides from first
 *    open. The pre-fix build reported 0 unbound rookie rides, so
 *    every ride gated behind the sign-in ceremony and a wallet-less
 *    reviewer could play nothing (the "became unusable" rejection).
 *    Purchases still require the bound identity exactly as before;
 *    only PLAY of the free grant is unbound.
 *
 * Calling convention (async methods): call with a unique requestId,
 * get an ack, and receive the result via
 * `window.__paperMoonWalletResolve(requestId, resultJson)`.
 * Nothing here ever touches a private key — signing happens inside
 * the Seeker's Seed Vault wallet through Mobile Wallet Adapter.
 */
class PaperMoonWalletBridge(
    private val activity: ComponentActivity,
    /**
     * The one wallet gateway (Gate 0 C-17), constructed by
     * MainActivity in onCreate before setContent and
     * Application-scoped. The v0.2.5 crash class — a wallet
     * collaborator constructed during composition, at RESUMED,
     * registering an activity-result launcher AndroidX forbids at
     * or after STARTED (BUTTON_CRASH_DIAGNOSIS.md) — stays closed
     * by construction: this bridge constructs nothing
     * lifecycle-bound; it only ever *uses* the gateway, whose own
     * wallet calls run inside the host activity's lifecycleScope
     * (C-20) with the SDK's per-call registration (v029 audit A3).
     */
    private val gateway: ConnectWalletGateway,
    private val evaluateJs: (script: String) -> Unit
) {
    private val app = activity.applicationContext as PaperMoonApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // v0.2.4 first-launch playability: the game reaches the shell
        // only through this bridge, so assembling it is the one
        // moment guaranteed to precede every game call — including
        // the page's boot-time entitlement read. Ensure the
        // device-local rookie allowance exists before the page
        // loads: a fresh install then reads 3 playable rookie rides
        // from its first getEntitlement(), and no wallet ceremony
        // stands between a new user and the free rides. Idempotent
        // (an existing allowance returns unchanged, never renewed);
        // a failed write grants nothing — the ledger fails closed
        // and the entitlement simply reports 0, as before the fix.
        app.rookieLedger.ensureLocalGrant()
    }

    // ------------------------------------------------------------------
    // Synchronous getters (safe to call any time)
    // ------------------------------------------------------------------

    /** Bridge protocol version. The game can feature-detect on this. */
    @JavascriptInterface
    fun getVersion(): String = "2"

    @JavascriptInterface
    fun isConnected(): Boolean = gateway.isConnected

    /** Base58 wallet address, or "" when not connected. */
    @JavascriptInterface
    fun getPublicKey(): String = gateway.connectedPublicKey() ?: ""

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
     * write any of this. The rookie fields report the ACTIVE scope
     * (v0.2.4): the bound identity's grant when an identity is
     * bound (0 until that identity claims, as AC-07 always held),
     * the device-local allowance when none is — so a fresh, unbound
     * install reads its 3 local rides here instead of the pre-fix 0
     * that gated all play behind sign-in.
     */
    @JavascriptInterface
    fun getEntitlement(): String {
        val identity = app.shellVerifier.boundIdentity
        val entitlement = app.entitlementLedger.entitlementFor(identity)
        // C-21/C-23: an open unresolved (signature-less) outcome
        // for the bound identity is VISIBLE state — the page can
        // show the honest line while reconciliation runs; it is
        // never hidden and never described as "failed".
        val unresolved = app.purchaseFlow.unresolvedNoSignatureOutcomes()
            .firstOrNull { it.buyerIdentity == identity }
        val json = JSONObject()
            .put("bound", identity != null)
            .put("passActive", entitlement.passActive)
            .put("passExpiresAtMs", entitlement.passExpiresAtMs)
            .put("pending", entitlement.pendingSignature != null)
            .put("paymentUnresolved", unresolved != null)
        if (unresolved != null) {
            json.put("unresolvedMessage", PurchaseCoordinator.UNRESOLVED_USER_MESSAGE)
        }
        return json.put(
                "rookieClaimed",
                if (identity != null) app.rookieLedger.hasClaimed(identity)
                else app.rookieLedger.hasLocalGrant()
            )
            .put(
                "rookieRemaining",
                if (identity != null) app.rookieLedger.remainingFor(identity)
                else app.rookieLedger.localRemaining()
            )
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
            gateway.connect().fold(
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
            val pubkey = gateway.connectedPublicKey()
            if (pubkey == null) {
                resolveErr(requestId, "not_connected: connect a wallet before sign-in")
                return@launch
            }
            val challenge = app.shellVerifier.issueChallenge()
            gateway.signShellChallenge(challenge.payload).fold(
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
     * The device-local allowance is a separate scope and is never
     * claimed here: an unbound call still resolves `not_bound`.
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
     *
     * The ride is charged to the ACTIVE scope (v0.2.4): the bound
     * identity's grant when an identity is bound, the device-local
     * allowance when none is — an unbound player spending a free
     * ride is the normal first-launch path, not an error. Only an
     * exhausted active scope resolves `no_rookie_rides`. (Purchases
     * are unchanged: [requestPurchase] with no bound identity still
     * resolves `not_bound` — buying is bound-only, playing the free
     * rides is not.)
     */
    @JavascriptInterface
    fun consumeRookieRide(requestId: String): String {
        val identity = app.shellVerifier.boundIdentity
        scope.launch {
            val remaining = if (identity != null) {
                app.rookieLedger.consumeOne(identity)
            } else {
                app.rookieLedger.consumeLocalOne()
            }
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
                // C-21: the recording provider — the blockhash +
                // lastValidBlockHeight stamped onto an unresolved
                // intent are the BUILT transaction's, captured
                // during the build itself.
                blockhashProvider = RecordingBlockhashProvider(app.rpcReader),
                walletSender = ConnectWalletSender(),
                sheetConfirmer = app.purchaseSheetController,
                noSignatureReconciler = app.noSignatureReconciler
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
                is PurchaseResult.DuplicateActivePass -> {
                    data.put("state", "duplicate-active")
                        .put("expiresAtMs", result.expiresAtMs)
                    resolveErr(
                        requestId,
                        "You already hold an active day pass. A second purchase would deliver " +
                            "nothing, so nothing was charged; the attempt was recorded.",
                        data
                    )
                }
                is PurchaseResult.SettlementRefused -> {
                    data.put("state", "settlement-refused")
                        .put("signature", result.signature)
                        .put("verdict", result.verdict.name)
                    resolveErr(
                        requestId,
                        "The payment did not verify as a day-pass payment on-chain " +
                            "(${result.verdict.name}). Nothing was granted; the outcome was recorded.",
                        data
                    )
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
                is PurchaseResult.UnresolvedNoSignature -> {
                    // C-21/C-23: the honest unresolved state — the
                    // payment may already have been submitted; the
                    // app is checking; no second charge can happen
                    // meanwhile. Never "failed", never "not_bound".
                    data.put("state", "unresolved")
                    resolveErr(requestId, PurchaseCoordinator.UNRESOLVED_USER_MESSAGE, data)
                }
            }
            postEntitlementChanged()
        }
        return ack(requestId)
    }

    /** Wallet sender for the coordinator: preflight balance (the
     *  INSUFFICIENT_FUNDS class), then the real sign-and-send
     *  through the gateway. Runs on the coordinator's IO thread;
     *  the gateway call is bridged with runBlocking — the blocking
     *  wait is on the gateway operation, which itself completes
     *  from the host activity's lifecycleScope deferred (C-20),
     *  never on the SDK session directly. */
    private inner class ConnectWalletSender : WalletSender {
        override fun sendTransaction(serializedTransaction: ByteArray): SendOutcome {
            val buyer = gateway.connectedPublicKey()
                ?: return SendOutcome.Failed(TxFailureKind.WALLET_NO_WALLET_FOUND)
            val balance = runCatching { app.rpcReader.usdcBalanceBaseUnits(buyer) }.getOrNull()
            if (balance != null && balance < MoneyConfig.PRICE_USDC_BASE_UNITS) {
                return SendOutcome.Failed(TxFailureKind.INSUFFICIENT_BALANCE_PREFLIGHT)
            }
            return runBlocking {
                when (val result = gateway.signAndSendTransactions(serializedTransaction)) {
                    is ConnectWalletGateway.SendResult.Sent ->
                        SendOutcome.Sent(result.signatureBase58)
                    is ConnectWalletGateway.SendResult.Failed ->
                        SendOutcome.Failed(result.kind)
                    is ConnectWalletGateway.SendResult.Ambiguous ->
                        // C-21: the durable unresolved machinery —
                        // the coordinator records an
                        // UNRESOLVED_NO_SIGNATURE intent and plugs
                        // it into the unresolved guard.
                        SendOutcome.UnresolvedNoSignature(result.mappedKindName)
                }
            }
        }
    }

    /**
     * C-21 recording blockhash provider: delegates to the real
     * reader's height-aware read and remembers the pair, so the
     * coordinator stamps the BUILT transaction's own blockhash +
     * lastValidBlockHeight onto an unresolved intent. If the
     * height-aware read fails, the build fails closed exactly as
     * before (null blockhash → build failure), and no unresolved
     * intent can be stamped with invented values.
     */
    private class RecordingBlockhashProvider(
        private val reader: com.hindsight.app.money.SolanaRpcChainReader
    ) : BlockhashProvider, BuildContextSource {
        @Volatile
        private var last: BuildContext? = null

        override fun recentBlockhash(): ByteArray? {
            val pair = reader.recentBlockhashWithHeight() ?: return null
            last = BuildContext(Base58.encode(pair.first), pair.second)
            return pair.first
        }

        override fun lastBuildContext(): BuildContext? = last
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
