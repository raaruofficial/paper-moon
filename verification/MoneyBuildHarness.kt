/**
 * Money-build verification harness (NOT shipped in the app).
 *
 * Compiles the pure-logic money modules (no Android SDK types beyond
 * org.json, which Android provides at runtime) and runs the PASS
 * tests from MONEY_BUILD_SPEC.md / PM_MONEY_BUILD_PREP.md Part A
 * against them. Exit code 0 = all checks passed.
 *
 * Build & run: see verification/run_harness.sh
 */
import com.hindsight.app.money.*
import com.hindsight.app.util.Base58
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory

var failures = 0
var checks = 0
fun check(name: String, cond: Boolean) {
    checks++
    if (cond) println("PASS  $name") else { println("FAIL  $name"); failures++ }
}
fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun jdkKeyPair(): Pair<ByteArray, java.security.KeyPair> {
    val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val raw = kp.public.encoded.takeLast(32).toByteArray()
    return raw to kp
}
fun jdkSign(kp: java.security.KeyPair, msg: ByteArray): ByteArray {
    val sig = Signature.getInstance("Ed25519")
    sig.initSign(kp.private); sig.update(msg); return sig.sign()
}

val TEST_RECIPIENT: String = Base58.encode(ByteArray(32) { (it + 7).toByte() })
const val SHARED_TREASURY = "FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP" // raaru.skr — must NEVER be the PM recipient

class FakeChain(var tx: ObservedTransaction?) : ChainReader {
    override fun fetchTransaction(signature: String): ObservedTransaction? = tx
}
class FixedBlockhash : BlockhashProvider {
    override fun recentBlockhash(): ByteArray = ByteArray(32) { 9 }
}
class CountingSender(var outcome: SendOutcome) : WalletSender {
    var calls = 0
    override fun sendTransaction(serializedTransaction: ByteArray): SendOutcome { calls++; return outcome }
}

fun main() {
    println("== GATE: dedicated receiving wallet + fail-closed mechanics (pre-join mitigation) ==")
    // Decided state (2026-10-20, TRACE pre-join decision under owner
    // delegation): the dedicated PAPER MOON receiving wallet exists and
    // is configured. Sales opening remains gated by AC-10 + flip; the
    // fail-closed mechanics below are proven against INJECTED
    // unconfigured/closed providers — the state any build without the
    // decided wallet must refuse in.
    check("GATE configured wallet is the dedicated PM receiving wallet",
        ReceivingWalletConfig.configuredReceivingWallet == "41FFd2wK8Uoy75TVYDx6bXZzscPH3UihwpuiX8fAtQYe")
    check("GATE configured wallet is never the shared treasury", ReceivingWalletConfig.configuredReceivingWallet != SHARED_TREASURY)
    run {
        val flow = PurchaseFlow(InMemorySecureStore(), railOpenProvider = { false })
        check("GATE beginIntent refuses (RailClosed)", flow.beginIntent(MoneyConfig.ITEM_DAY_PASS) is PurchaseFlow.BeginResult.RailClosed)
        check("GATE no purchase sheet while unconfigured", PurchaseSheetData.forDayPass { null } == null)
        check("GATE tx builder refuses while unconfigured", TransactionBuilder.buildDayPassPurchase(Base58.encode(ByteArray(32) { 3 }), FixedBlockhash()) { null } == null)
        val settlement = SettlementVerifier(FakeChain(null)) { null }
        check("GATE settlement verdict RAIL_UNCONFIGURED", settlement.verify("anysig") == SettlementVerifier.Verdict.RAIL_UNCONFIGURED)
        val coord = PurchaseCoordinator(flow, EntitlementLedger(InMemorySecureStore()),
            settlement, FixedBlockhash(), CountingSender(SendOutcome.Sent("s")), object : SheetConfirmer {
                override fun confirm(sheet: PurchaseSheetData) = true }, { null })
        check("GATE coordinator purchase refuses (RailClosed)",
            coord.purchase(MoneyConfig.ITEM_DAY_PASS, Base58.encode(ByteArray(32) { 3 })) is PurchaseResult.RailClosed)
    }

    println("== Base58 (money-build fix, pins the v0.1.3 defect) ==")
    check("B58 known vector 'hello world'", Base58.encode("hello world".toByteArray()) == "StV1DL6CwTryKyV")
    check("B58 decode known vector", String(Base58.decode("StV1DL6CwTryKyV")) == "hello world")
    run {
        val rnd = java.security.SecureRandom()
        var ok = true
        for (n in listOf(16, 32, 64)) repeat(100) {
            val b = ByteArray(n).also { rnd.nextBytes(it) }
            if (!Base58.decode(Base58.encode(b)).contentEquals(b)) ok = false
        }
        check("B58 round-trips 300 random values", ok)
    }

    println("== AC-01: Shell Verifier binding ==")
    // RFC 8032 vectors
    check("AC-01 ed25519 RFC8032 TEST1 verifies", Ed25519.verify(
        hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
        ByteArray(0),
        hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")))
    // Cross-check against the JDK's Ed25519 over varied messages:
    // every JDK signature must verify here; every bit-flipped one
    // must not. (An earlier draft also carried the RFC 8032 TEST 2
    // vector, but the hand-transcribed hex disagreed with BOTH this
    // verifier and the JDK's — the transcription was wrong, so the
    // cross-check replaces it as the stronger evidence.)
    run {
        val (raw2, kp2) = jdkKeyPair()
        var ok = true
        for (m in listOf("a", "PAPER MOON sign-in", "x".repeat(500), "")) {
            val msg = m.toByteArray()
            val good = jdkSign(kp2, msg)
            if (!Ed25519.verify(raw2, msg, good)) ok = false
            val badSig = good.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
            if (Ed25519.verify(raw2, msg, badSig)) ok = false
        }
        check("AC-01 ed25519 cross-check vs JDK (accept good, reject flipped)", ok)
    }
    check("AC-01 ed25519 rejects tampered sig", !Ed25519.verify(
        hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
        "x".toByteArray(),
        hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")))
    check("AC-01 ed25519 rejects small-order pubkey", !Ed25519.verify(ByteArray(32), ByteArray(0), ByteArray(64)))

    var now = 1_700_000_000_000L
    val verifier = ShellVerifier(clock = { now })
    val (pubRaw, kp) = jdkKeyPair()
    val pubB58 = Base58.encode(pubRaw)
    val challenge = verifier.issueChallenge()
    check("AC-01 payload is shell-built (prefix+nonce+issued-at only)",
        challenge.payload.matches(Regex("PAPER MOON sign-in\\nnonce:[1-9A-HJ-NP-Za-km-z]+\\nissued-at:\\d+")))
    check("AC-01 nonce >=128-bit", Base58.decode(challenge.payload.substringAfter("nonce:").substringBefore("\n")).size >= 16)
    check("AC-01 TTL <= 5 min", ShellVerifier.NONCE_TTL_MS <= 300_000L)
    val sig = jdkSign(kp, challenge.payload.toByteArray(Charsets.UTF_8))
    val bound = verifier.verifyAndBind(challenge.handle, pubB58, Base58.encode(sig))
    check("AC-01 fresh challenge verifies and binds", bound is ShellVerifier.BindResult.Bound && verifier.boundIdentity == pubB58)
    val replay = verifier.verifyAndBind(challenge.handle, pubB58, Base58.encode(sig))
    check("AC-01 replayed challenge fails", replay is ShellVerifier.BindResult.Refused &&
        replay.reason == ShellVerifier.Refusal.ALREADY_USED)
    // attacker text: no API path — a signature over attacker text is not over the issued payload
    val c2 = verifier.issueChallenge()
    val attackerSig = jdkSign(kp, "attacker chosen text".toByteArray())
    val r2 = verifier.verifyAndBind(c2.handle, pubB58, Base58.encode(attackerSig))
    check("AC-01 signature over attacker-chosen text fails", r2 is ShellVerifier.BindResult.Refused &&
        r2.reason == ShellVerifier.Refusal.SIGNATURE_INVALID)
    check("AC-01 failed attempt consumes nonce", verifier.verifyAndBind(c2.handle, pubB58,
        Base58.encode(jdkSign(kp, c2.payload.toByteArray()))) is ShellVerifier.BindResult.Refused)
    val c3 = verifier.issueChallenge()
    now += 6 * 60 * 1000L
    val r3 = verifier.verifyAndBind(c3.handle, pubB58, Base58.encode(jdkSign(kp, c3.payload.toByteArray())))
    check("AC-01 expired challenge fails", r3 is ShellVerifier.BindResult.Refused && r3.reason == ShellVerifier.Refusal.EXPIRED)
    check("AC-01 unknown handle fails", verifier.verifyAndBind("nope", pubB58, "AAAA") is ShellVerifier.BindResult.Refused)

    println("== AC-03: taxonomy + shell-side tx construction ==")
    check("AC-03 declined maps", TxErrorMapper.map(TxFailureKind.WALLET_DECLINED) == TxErrorClass.USER_DECLINED)
    check("AC-03 insufficient maps", TxErrorMapper.map(TxFailureKind.INSUFFICIENT_BALANCE_PREFLIGHT) == TxErrorClass.INSUFFICIENT_FUNDS)
    check("AC-03 absent maps", TxErrorMapper.map(TxFailureKind.WALLET_NO_WALLET_FOUND) == TxErrorClass.WALLET_ABSENT)
    check("AC-03 rpc maps", TxErrorMapper.map(TxFailureKind.RPC_UNREACHABLE) == TxErrorClass.RPC_OR_CONFIRMATION_FAILURE)
    check("AC-03 cancelled maps", TxErrorMapper.map(TxFailureKind.WALLET_SESSION_CANCELLED) == TxErrorClass.USER_CANCELLED_MID_FLOW)
    check("AC-03 exactly five classes", TxErrorClass.values().size == 5)
    run {
        val tx = TransactionBuilder.buildDayPassPurchase(pubB58, FixedBlockhash()) { TEST_RECIPIENT }
        check("AC-03 tx builds with configured wallet", tx != null)
        if (tx != null) {
            val needle = byteArrayOf(12) + ByteArray(8).also { var v = MoneyConfig.PRICE_USDC_BASE_UNITS; for (i in 0..7) { it[i] = (v and 0xFF).toByte(); v = v ushr 8 } } + byteArrayOf(6)
            fun contains(hay: ByteArray, needle: ByteArray): Boolean =
                hay.indices.any { i -> i + needle.size <= hay.size && needle.indices.all { hay[i + it] == needle[it] } }
            check("AC-03 tx carries TransferChecked for exactly the single-source price", contains(tx, needle))
            val recipientAta = TransactionBuilder.associatedTokenAddress(Base58.decode(TEST_RECIPIENT), Base58.decode(MoneyConfig.USDC_MINT))!!
            check("AC-03 tx includes recipient ATA (derived from configured wallet)", contains(tx, recipientAta))
            check("AC-03 tx never names the shared treasury", !contains(tx, Base58.decode(SHARED_TREASURY)))
        }
    }

    println("== AC-04: shell-owned sheet ==")
    run {
        val sheet = PurchaseSheetData.forDayPass { TEST_RECIPIENT }!!
        check("AC-04 sheet item from P6-a", sheet.itemDisplay.contains("24h"))
        check("AC-04 sheet rail amount from P6-c", sheet.railAmountDisplay == "3.50 USDC")
        check("AC-04 sheet USD anchor from P6-b", sheet.usdAnchorDisplay == "$3.50")
        check("AC-04 sheet fee estimate present", sheet.networkFeeEstimateDisplay.isNotEmpty())
        check("AC-04 sheet recipient = configured dedicated wallet", sheet.recipientDisplay == TEST_RECIPIENT)
    }

    println("== AC-05: settlement verification ==")
    fun txTo(to: String, mint: String, amt: Long, failed: Boolean = false, found: Boolean = true) =
        ObservedTransaction("sig", found, listOf(ObservedTransfer(to, mint, amt)), failed)
    val usdc = MoneyConfig.USDC_MINT
    val price = MoneyConfig.PRICE_USDC_BASE_UNITS
    fun verdict(tx: ObservedTransaction?) = SettlementVerifier(FakeChain(tx)) { TEST_RECIPIENT }.verify("sig")
    check("AC-05 correct payment verifies", verdict(txTo(TEST_RECIPIENT, usdc, price)) == SettlementVerifier.Verdict.VERIFIED)
    check("AC-05 wrong recipient grants nothing", verdict(txTo(pubB58, usdc, price)) == SettlementVerifier.Verdict.WRONG_RECIPIENT)
    check("AC-05 wrong mint grants nothing", verdict(txTo(TEST_RECIPIENT, "SomeOtherMint", price)) == SettlementVerifier.Verdict.WRONG_MINT)
    check("AC-05 short amount grants nothing", verdict(txTo(TEST_RECIPIENT, usdc, price - 1)) == SettlementVerifier.Verdict.SHORT_AMOUNT)
    check("AC-05 not-found grants nothing", verdict(txTo(TEST_RECIPIENT, usdc, price, found = false)) == SettlementVerifier.Verdict.NOT_FOUND)
    check("AC-05 failed tx grants nothing", verdict(txTo(TEST_RECIPIENT, usdc, price, failed = true)) == SettlementVerifier.Verdict.TX_FAILED)
    check("AC-05 unreachable RPC fails closed", verdict(null) == SettlementVerifier.Verdict.RPC_UNREACHABLE)

    println("== AC-05/06: coordinator grant path, pending, restore, idempotency ==")
    run {
        val store = InMemorySecureStore()
        val flow = PurchaseFlow(store, railOpenProvider = { true })
        val ledger = EntitlementLedger(store) { now }
        val chain = FakeChain(txTo(TEST_RECIPIENT, usdc, price))
        val sender = CountingSender(SendOutcome.Sent("sig-1"))
        val coord = PurchaseCoordinator(flow, ledger, SettlementVerifier(chain) { TEST_RECIPIENT },
            FixedBlockhash(), sender, object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true }) { TEST_RECIPIENT }
        val result = coord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("AC-05 coordinator grants only after verified settlement", result is PurchaseResult.Granted)
        check("AC-05 entitlement active with 24h expiry", ledger.entitlementFor(pubB58).passActive &&
            ledger.entitlementFor(pubB58).passExpiresAtMs == now + MoneyConfig.PASS_DURATION_MS)
        // idempotency at the ledger: re-grant same key/signature returns original, no extension
        val again = ledger.grantFromVerifiedReceipt("sig-1",
            ledger.receiptForSignature("sig-1")!!.idempotencyKey, MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("AC-06 double grant impossible (same receipt returned)", again != null &&
            again.grantedAtMs == (result as PurchaseResult.Granted).receipt.grantedAtMs &&
            ledger.allReceipts().size == 1)
        // pending + offline: no grant, no silent loss; restore-by-signature on reconcile
        val store2 = InMemorySecureStore()
        val flow2 = PurchaseFlow(store2, railOpenProvider = { true })
        val ledger2 = EntitlementLedger(store2) { now }
        val chain2 = FakeChain(null) // offline
        val coord2 = PurchaseCoordinator(flow2, ledger2, SettlementVerifier(chain2) { TEST_RECIPIENT },
            FixedBlockhash(), CountingSender(SendOutcome.Sent("sig-2")), object : SheetConfirmer {
                override fun confirm(sheet: PurchaseSheetData) = true }) { TEST_RECIPIENT }
        val pendingResult = coord2.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("AC-05 offline at grant time = pending, no grant", pendingResult is PurchaseResult.Pending &&
            !ledger2.entitlementFor(pubB58).passActive && ledger2.pending() != null)
        chain2.tx = txTo(TEST_RECIPIENT, usdc, price) // back online
        val restored = coord2.reconcileOnLaunch(pubB58)
        check("AC-05 restore-by-signature at next launch grants", restored.passActive)
        check("AC-06 reconcile marks nothing unknown after grant", flow2.reconcileOnLaunch().isEmpty())
        // user declines on sheet: no send, named class
        val sender3 = CountingSender(SendOutcome.Sent("sig-3"))
        val coord3 = PurchaseCoordinator(PurchaseFlow(InMemorySecureStore(), railOpenProvider = { true }),
            EntitlementLedger(InMemorySecureStore()) { now }, SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT },
            FixedBlockhash(), sender3, object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = false }) { TEST_RECIPIENT }
        val declined = coord3.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("AC-04/06 sheet decline sends nothing, named class", declined is PurchaseResult.Failed &&
            declined.errorClass == TxErrorClass.USER_CANCELLED_MID_FLOW && sender3.calls == 0)
        // wallet-declined fault case produces its class
        val coord4 = PurchaseCoordinator(PurchaseFlow(InMemorySecureStore(), railOpenProvider = { true }),
            EntitlementLedger(InMemorySecureStore()) { now }, SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT },
            FixedBlockhash(), CountingSender(SendOutcome.Failed(TxFailureKind.WALLET_DECLINED)),
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true }) { TEST_RECIPIENT }
        val walletDeclined = coord4.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("AC-03 wallet-declined fault case -> USER_DECLINED", walletDeclined is PurchaseResult.Failed &&
            walletDeclined.errorClass == TxErrorClass.USER_DECLINED)
        // not bound cannot purchase
        check("AC-07 unbound user cannot purchase", coord4.purchase(MoneyConfig.ITEM_DAY_PASS, null) is PurchaseResult.NotBound)
    }

    println("== AC-07: identity-bound rookie grant ==")
    run {
        val store = InMemorySecureStore()
        val rookie = RookieLedger(store)
        val idA = pubB58
        val idB = Base58.encode(ByteArray(32) { 42 })
        check("AC-07 unclaimed remaining is 0", rookie.remainingFor(idA) == 0)
        check("AC-07 claim grants 3", rookie.claim(idA)?.remaining == 3)
        check("AC-07 second claim does not renew", rookie.claim(idA)?.remaining == 3)
        check("AC-07 consume decrements", rookie.consumeOne(idA) == 2)
        // relaunch / reinstall-restore: new ledger over the same store
        val relaunched = RookieLedger(store)
        check("AC-07 relaunch never increases remaining", relaunched.remainingFor(idA) == 2)
        check("AC-07 second identity claims independently", relaunched.claim(idB)?.remaining == 3 &&
            relaunched.remainingFor(idA) == 2)
        relaunched.consumeOne(idA); relaunched.consumeOne(idA)
        check("AC-07 exhausting the grant sticks at 0", relaunched.remainingFor(idA) == 0 && relaunched.consumeOne(idA) == -1)
    }

    println("== AC-09: single price source ==")
    check("AC-09 price anchor $3.50 (P6-b)", MoneyConfig.PRICE_USD_CENTS == 350L && MoneyConfig.priceUsdDisplay() == "$3.50")
    check("AC-09 rail 3.50 USDC at 6dp = anchor x10^6 (P6-c 1:1)", MoneyConfig.PRICE_USDC_BASE_UNITS == MoneyConfig.PRICE_USD_CENTS * 10_000L)
    check("AC-09 pass duration 24h (P6-a)", MoneyConfig.PASS_DURATION_MS == 86_400_000L)

    println("\n$checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
