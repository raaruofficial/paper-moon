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
import com.hindsight.app.wallet.ConnectAuthorizationCache
import com.hindsight.app.wallet.ConnectMigration
import com.hindsight.app.wallet.DisconnectSequence
import com.solanamobile.seekerconnect.SeekerAccount
import com.solanamobile.seekerconnect.SeekerWalletCapabilities
import com.solanamobile.seekerconnect.StoredAuthorization
import kotlinx.coroutines.runBlocking
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
const val RAARU_SKR_TREASURY = "FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP" // raaru.skr — owner-ruled PM recipient since 2026-10-21

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

/** Fault-injecting store for the bankai #3 crash-injection tests:
 *  puts to keys matching [failWhen] report failure (not recorded),
 *  exactly the SecureKeyValueStore contract's false case. Healing =
 *  resetting [failWhen]; "process death" = dropping every
 *  flow/ledger/coordinator object and rebuilding over this store. */
class PutFailStore(val inner: InMemorySecureStore = InMemorySecureStore()) : SecureKeyValueStore {
    var failWhen: (String) -> Boolean = { false }
    override fun get(key: String): String? = inner.get(key)
    override fun put(key: String, value: String): Boolean =
        if (failWhen(key)) false else inner.put(key, value)
    override fun remove(key: String) = inner.remove(key)
    override fun keysWithPrefix(prefix: String): Set<String> = inner.keysWithPrefix(prefix)
}

fun main() {
    println("== GATE: owner-ruled receiving wallet (raaru.skr) + fail-closed mechanics ==")
    // Owner ruling 2026-10-21 (supersedes the TRACE pre-join dedicated-wallet
    // state): day-pass payments receive directly at raaru.skr, the shared
    // treasury, and sales are ENABLED. The production rail is therefore OPEN
    // at this head. Fail-closed mechanics are proven below against INJECTED
    // unconfigured/closed providers -- the state any build without a
    // configured wallet must refuse in.
    check("GATE configured wallet is the owner-ruled recipient (raaru.skr)",
        ReceivingWalletConfig.configuredReceivingWallet == "FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP")
    check("GATE configured wallet equals the raaru.skr treasury by owner ruling", ReceivingWalletConfig.configuredReceivingWallet == RAARU_SKR_TREASURY)
    check("GATE configured opens the rail (sales enabled by owner ruling)",
        ReceivingWalletConfig.railOpen)
    run {
        val productionFlow = PurchaseFlow(InMemorySecureStore())
        check("GATE production-default flow begins intent while sales enabled",
            productionFlow.beginIntent(MoneyConfig.ITEM_DAY_PASS) is PurchaseFlow.BeginResult.Begun)
        // Production-coordinator RailClosed check dropped by design (prep
        // 2026-10-24 option (a)): with the rail open, a coordinator over
        // FakeChain(null) cannot reach a verified grant, so no honest
        // RailClosed OR Granted expectation exists on that fixture.
        // Fail-closed proof lives in the injected-provider block below.
    }
    run {
        val flow = PurchaseFlow(InMemorySecureStore(), railOpenProvider = { false })
        check("GATE beginIntent refuses (RailClosed)", flow.beginIntent(MoneyConfig.ITEM_DAY_PASS) is PurchaseFlow.BeginResult.RailClosed)
        check("GATE no purchase sheet while unconfigured", PurchaseSheetData.forDayPass { null } == null)
        check("GATE tx builder refuses while unconfigured", TransactionBuilder.buildDayPassPurchase(Base58.encode(ByteArray(32) { 3 }), FixedBlockhash()) { null } == null)
        val settlement = SettlementVerifier(FakeChain(null)) { null }
        check("GATE settlement verdict RAIL_UNCONFIGURED", settlement.verify("anysig") == SettlementVerifier.Verdict.RAIL_UNCONFIGURED)
        val coord = PurchaseCoordinator(flow, EntitlementLedger(InMemorySecureStore()),
            settlement, FixedBlockhash(), CountingSender(SendOutcome.Sent("s")), object : SheetConfirmer {
                override fun confirm(sheet: PurchaseSheetData) = true }, receivingWalletProvider = { null })
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
            check("AC-03 tx built for injected recipient never names raaru.skr", !contains(tx, Base58.decode(RAARU_SKR_TREASURY)))
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

    println("== AC-07L: device-local rookie allowance (v0.2.4 cold start) ==")
    run {
        // Cold start, no wallet anywhere: a fresh store IS a fresh
        // install. This block proves the exact state the v0.2.3
        // build denied its reviewers — the unbound entitlement read
        // must find the full local grant playable (v0.2.3 read 0
        // unbound and every ride gated behind connect + sign-in +
        // claim). The bridge's unbound read/consume paths are thin
        // compositions of these ledger calls (localRemaining /
        // consumeLocalOne); the bound paths stay AC-07's, above.
        val store = InMemorySecureStore()
        val rookie = RookieLedger(store)
        check("AC-07L fresh install holds no local grant before assembly", !rookie.hasLocalGrant() && rookie.localRemaining() == 0)
        check("AC-07L assembly grants the full local allowance", rookie.ensureLocalGrant()?.remaining == MoneyConfig.ROOKIE_RIDES)
        check("AC-07L unbound cold-start read finds 3 playable rides", rookie.localRemaining() == 3)
        check("AC-07L re-assembly never renews the allowance", rookie.ensureLocalGrant()?.remaining == 3)
        check("AC-07L local consume decrements (decrement-after-build)", rookie.consumeLocalOne() == 2)
        // relaunch: new ledger over the same store — count only moves down
        val relaunched = RookieLedger(store)
        check("AC-07L relaunch keeps the spent count", relaunched.localRemaining() == 2)
        relaunched.consumeLocalOne(); relaunched.consumeLocalOne()
        check("AC-07L exhausting the local allowance sticks at 0", relaunched.localRemaining() == 0 && relaunched.consumeLocalOne() == -1)
        // Scope independence: the local allowance is not an identity
        // grant and never writes one — and an identity's claim is
        // still its own full set, drawn down only by its own rides.
        check("AC-07L local grant leaks nothing into an identity scope", relaunched.remainingFor(pubB58) == 0 && !relaunched.hasClaimed(pubB58))
        check("AC-07L identity claim after local play grants its own full set", relaunched.claim(pubB58)?.remaining == 3 && relaunched.localRemaining() == 0)
        check("AC-07L identity consume never draws the local scope", relaunched.consumeOne(pubB58) == 2 && relaunched.localRemaining() == 0)
        // The reserved local key can never be a wallet identity:
        // identities are Base58 encodings of 32-byte keys, and
        // Base58's alphabet cannot produce this value (it excludes
        // both '-' and 'l'), so no decode of it is a 32-byte key.
        check("AC-07L local scope key is not a possible wallet identity",
            runCatching { Base58.decode(RookieLedger.LOCAL_GRANT_IDENTITY) }.fold(
                onSuccess = { it.size != 32 }, onFailure = { true }))
        // Fail closed: an unrecordable grant reports no rides.
        val failStore = PutFailStore()
        failStore.failWhen = { true }
        val failRookie = RookieLedger(failStore)
        check("AC-07L unrecordable local grant fails closed (0 rides, nothing granted)",
            failRookie.ensureLocalGrant() == null && failRookie.localRemaining() == 0 && failRookie.consumeLocalOne() == -1)
    }

    println("== FIX WAVE bankai #3 MAJOR-2: settlement-write complex (crash injection) ==")
    run {
        val alwaysOk = object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true }
        fun coordFor(store: SecureKeyValueStore, flow: PurchaseFlow, chain: FakeChain, sender: CountingSender) =
            PurchaseCoordinator(flow, EntitlementLedger(store) { now }, SettlementVerifier(chain) { TEST_RECIPIENT },
                FixedBlockhash(), sender, alwaysOk) { TEST_RECIPIENT }

        // (a) The pending journal never overwrites: two buyers' sent
        // purchases coexist, each attributed to its own buyer. The old
        // single slot erased the first when the second was recorded.
        val journalStore = InMemorySecureStore()
        val journal = EntitlementLedger(journalStore) { now }
        val buyerB = Base58.encode(ByteArray(32) { 42 })
        check("FIX2 journal records first pending", journal.recordPending("sig-a", pubB58, "key-a"))
        check("FIX2 journal records second pending without erasing the first",
            journal.recordPending("sig-b", buyerB, "key-b") &&
                journal.pendingFor(pubB58) == "sig-a" && journal.pendingFor(buyerB) == "sig-b" &&
                journal.activePendingEntries().size == 2)
        check("FIX2 journal re-record is idempotent, never an overwrite",
            journal.recordPending("sig-a", buyerB, "key-x") &&
                journal.pendingEntry("sig-a")!!.buyerIdentity == pubB58 &&
                journal.pendingEntry("sig-a")!!.idempotencyKey == "key-a")
        val failStore = PutFailStore()
        failStore.failWhen = { it.startsWith("pending:") }
        check("FIX2 recordPending reports an unrecorded write as false",
            !EntitlementLedger(failStore) { now }.recordPending("sig-z", pubB58, "key-z"))

        // (b) Crash between broadcast and the journal write: the
        // journal write fails during the purchase, the chain is
        // unreachable, the purchase is Pending. After "process
        // death" (all objects rebuilt over the same store) and
        // healing, reconciliation recovers the signature from the
        // INTENT OUTCOME record alone and grants.
        val crashStore = PutFailStore()
        crashStore.failWhen = { it.startsWith("pending:") }
        val crashFlow = PurchaseFlow(crashStore, railOpenProvider = { true })
        val crashChain = FakeChain(null)
        val crashCoord = coordFor(crashStore, crashFlow, crashChain, CountingSender(SendOutcome.Sent("sig-crash")))
        val crashResult = crashCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("FIX2 unjournaled sent purchase is Pending, not lost, not granted",
            crashResult is PurchaseResult.Pending &&
                EntitlementLedger(crashStore) { now }.activePendingEntries().isEmpty() &&
                crashFlow.unresolvedOutcomes().size == 1)
        crashStore.failWhen = { false }
        crashChain.tx = txTo(TEST_RECIPIENT, usdc, price)
        val relaunchFlow = PurchaseFlow(crashStore, railOpenProvider = { true })
        val relaunchCoord = coordFor(crashStore, relaunchFlow, crashChain, CountingSender(SendOutcome.Sent("unused")))
        check("FIX2 relaunch reconciliation grants from the outcome record alone",
            relaunchCoord.reconcileOnLaunch(pubB58).passActive &&
                EntitlementLedger(crashStore) { now }.receiptForSignature("sig-crash") != null)

        // (c) Grant write failure: settlement VERIFIED but the
        // receipt cannot be durably recorded — the purchase stays
        // Pending (never granted on a failed write); after healing,
        // reconciliation retries and the grant lands, with the
        // by-signature and by-key lookups returning the SAME receipt
        // (the old two-put grant could orphan the index).
        val grantStore = PutFailStore()
        grantStore.failWhen = { it.startsWith("receipt:") }
        val grantFlow = PurchaseFlow(grantStore, railOpenProvider = { true })
        val grantChain = FakeChain(txTo(TEST_RECIPIENT, usdc, price))
        val grantCoord = coordFor(grantStore, grantFlow, grantChain, CountingSender(SendOutcome.Sent("sig-grant")))
        val grantResult = grantCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("FIX2 unverifiable-write grant stays Pending, nothing granted",
            grantResult is PurchaseResult.Pending &&
                !EntitlementLedger(grantStore) { now }.entitlementFor(pubB58).passActive)
        grantStore.failWhen = { false }
        val grantLedger = EntitlementLedger(grantStore) { now }
        val grantKey = grantLedger.pendingEntry("sig-grant")!!.idempotencyKey
        val healedCoord = coordFor(grantStore, PurchaseFlow(grantStore, railOpenProvider = { true }),
            grantChain, CountingSender(SendOutcome.Sent("unused")))
        healedCoord.reconcileOnLaunch(pubB58)
        check("FIX2 healed reconciliation grants; by-key and by-signature receipts are the same record",
            grantLedger.entitlementFor(pubB58).passActive &&
                grantLedger.receiptForSignature("sig-grant") != null &&
                grantLedger.receiptForIdempotencyKey(grantKey)?.signature == "sig-grant" &&
                grantLedger.receiptForIdempotencyKey(grantKey)?.grantedAtMs ==
                    grantLedger.receiptForSignature("sig-grant")?.grantedAtMs)

        // (d) A definitive on-chain refusal RESOLVES the purchase:
        // SettlementRefused, outcome FAILED, journal REFUSED — and
        // the buyer is not wedged: a fresh purchase can proceed.
        val refuseChain = FakeChain(txTo(buyerB, usdc, price)) // pays the wrong recipient
        val refuseStore = InMemorySecureStore()
        val refuseFlow = PurchaseFlow(refuseStore, railOpenProvider = { true })
        val refuseLedger = EntitlementLedger(refuseStore) { now }
        val refuseSender = CountingSender(SendOutcome.Sent("sig-refused"))
        val refuseCoord = PurchaseCoordinator(refuseFlow, refuseLedger, SettlementVerifier(refuseChain) { TEST_RECIPIENT },
            FixedBlockhash(), refuseSender, alwaysOk) { TEST_RECIPIENT }
        val refused = refuseCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("FIX2 definitive refusal resolves as SettlementRefused (WRONG_RECIPIENT)",
            refused is PurchaseResult.SettlementRefused &&
                refused.verdict == SettlementVerifier.Verdict.WRONG_RECIPIENT &&
                refuseLedger.pendingEntry("sig-refused")?.status == PendingEntry.Status.REFUSED &&
                refuseFlow.unresolvedOutcomes().isEmpty() &&
                !refuseLedger.entitlementFor(pubB58).passActive)
        refuseChain.tx = txTo(TEST_RECIPIENT, usdc, price)
        refuseSender.outcome = SendOutcome.Sent("sig-after-refusal")
        check("FIX2 buyer can purchase afresh after a recorded refusal",
            refuseCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58) is PurchaseResult.Granted &&
                refuseSender.calls == 2)

        // (e) PENDING_SETTLEMENT no longer wedges the item in flight
        // for the process's life, and a replayed purchase binds to
        // the original — it settles the original signature instead
        // of sending a second transaction.
        val wedgeStore = InMemorySecureStore()
        val wedgeFlow = PurchaseFlow(wedgeStore, railOpenProvider = { true })
        val wedgeChain = FakeChain(null) // offline: purchase stays pending
        val wedgeSender = CountingSender(SendOutcome.Sent("sig-wedge"))
        val wedgeCoord = coordFor(wedgeStore, wedgeFlow, wedgeChain, wedgeSender)
        val first = wedgeCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("FIX2 offline purchase is Pending", first is PurchaseResult.Pending)
        check("FIX2 item is not wedged in flight after PENDING_SETTLEMENT",
            wedgeFlow.beginIntent(MoneyConfig.ITEM_DAY_PASS) is PurchaseFlow.BeginResult.Begun)
        wedgeFlow.reconcileOnLaunch() // clear the probe intent's in-flight slot
        val replay = wedgeCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("FIX2 replay binds to the original purchase — no second send",
            replay is PurchaseResult.Pending && replay.signature == "sig-wedge" && wedgeSender.calls == 1)

        // (f) UNKNOWN outcomes are retained, not discarded: offline
        // reconciliation keeps the candidate; a later definitive
        // verdict resolves it to FAILED with the record intact.
        val unkStore = InMemorySecureStore()
        val unkFlow = PurchaseFlow(unkStore, railOpenProvider = { true })
        val unkChain = FakeChain(null)
        val unkCoord = coordFor(unkStore, unkFlow, unkChain, CountingSender(SendOutcome.Sent("sig-unk")))
        unkCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        unkCoord.reconcileOnLaunch(pubB58) // still offline
        val unkKey = unkFlow.unresolvedOutcomes().single().idempotencyKey
        check("FIX2 offline reconciliation retains the UNKNOWN candidate",
            unkFlow.outcomeFor(unkKey)?.state == PurchaseState.UNKNOWN &&
                EntitlementLedger(unkStore) { now }.activePendingEntries().size == 1)
        unkChain.tx = txTo(TEST_RECIPIENT, usdc, price, failed = true)
        unkCoord.reconcileOnLaunch(pubB58)
        check("FIX2 definitive verdict at reconcile resolves UNKNOWN to FAILED, record kept",
            unkFlow.outcomeFor(unkKey)?.state == PurchaseState.FAILED &&
                unkFlow.unresolvedOutcomes().isEmpty() &&
                EntitlementLedger(unkStore) { now }.pendingEntry("sig-unk")?.status == PendingEntry.Status.REFUSED)
    }

    println("== FIX WAVE bankai #3 MAJOR-5: duplicate purchase guard (Terms §7(b)) ==")
    run {
        val dupStore = InMemorySecureStore()
        val dupFlow = PurchaseFlow(dupStore, railOpenProvider = { true })
        val dupLedger = EntitlementLedger(dupStore) { now }
        val dupChain = FakeChain(txTo(TEST_RECIPIENT, usdc, price))
        val dupSender = CountingSender(SendOutcome.Sent("sig-dup-1"))
        val dupCoord = PurchaseCoordinator(dupFlow, dupLedger, SettlementVerifier(dupChain) { TEST_RECIPIENT },
            FixedBlockhash(), dupSender, object : SheetConfirmer {
                override fun confirm(sheet: PurchaseSheetData) = true }) { TEST_RECIPIENT }
        val firstBuy = dupCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("FIX5 first purchase grants", firstBuy is PurchaseResult.Granted && dupSender.calls == 1)
        val firstExpiry = (firstBuy as PurchaseResult.Granted).receipt.expiresAtMs
        // The unguarded behaviour: the second purchase charged again
        // and REFRESHED the window. Now: refused, recorded, untouched.
        val dup = dupCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("FIX5 duplicate purchase is refused as DuplicateActivePass, no second charge",
            dup is PurchaseResult.DuplicateActivePass && dup.expiresAtMs == firstExpiry && dupSender.calls == 1)
        check("FIX5 duplicate attempt is recorded as a non-delivery (§7(b) remedy queue)",
            dupLedger.nonDeliveries().size == 1 &&
                dupLedger.nonDeliveries()[0].reason == PurchaseCoordinator.NON_DELIVERY_DUPLICATE_ACTIVE_PASS &&
                dupLedger.nonDeliveries()[0].buyerIdentity == pubB58)
        check("FIX5 the active window is NOT refreshed by the duplicate attempt",
            dupLedger.entitlementFor(pubB58).passExpiresAtMs == firstExpiry &&
                dupLedger.allReceipts().size == 1)
        // After the pass expires, purchase works again — the guard
        // binds to the ACTIVE window, not to the buyer forever.
        now += MoneyConfig.PASS_DURATION_MS + 1
        dupSender.outcome = SendOutcome.Sent("sig-dup-2")
        val afterExpiry = dupCoord.purchase(MoneyConfig.ITEM_DAY_PASS, pubB58)
        check("FIX5 purchase succeeds again after the active pass expires",
            afterExpiry is PurchaseResult.Granted && dupSender.calls == 2 &&
                dupLedger.allReceipts().size == 2)
        now -= MoneyConfig.PASS_DURATION_MS + 1
    }

    println("== C-06: Connect authorization cache adapter laws (Gate 0) ==")
    run {
        val buyerAddr = Base58.encode(ByteArray(32) { 5 })
        val auth = StoredAuthorization(
            accounts = listOf(SeekerAccount(buyerAddr, ByteArray(32) { 5 }, "Seeker")),
            authToken = "token-abc",
            walletUriBase = "solana-wallet:/",
            chain = "solana:mainnet",
            capabilities = SeekerWalletCapabilities(1, 1, listOf("legacy"), listOf("feat"), true)
        )
        val store = InMemorySecureStore()
        val cache = ConnectAuthorizationCache(store)
        runBlocking {
            check("C-06 empty store reads as no authorization", cache.get() == null)
            cache.set(auth)
            val back = cache.get()
            check("C-06 set/get round-trips the account", back?.accounts?.firstOrNull()?.address == buyerAddr)
            check("C-06 round-trips the token opaquely", back?.authToken == "token-abc")
            check("C-06 learned URI is envelope data only (round-trips)", back?.walletUriBase == "solana-wallet:/")
            check("C-06 round-trips capabilities", back?.capabilities?.supportsSignAndSendTransactions == true)
            check("C-06 envelope lives under the one adapter key",
                store.get(ConnectAuthorizationCache.KEY) != null)
            check("C-06 no adapter-added store keys",
                store.keysWithPrefix("").size == 1)
            // Corrupt envelope: evict + null (vendor law iii).
            store.put(ConnectAuthorizationCache.KEY, "{not json")
            check("C-06 corrupt envelope reads as no authorization", cache.get() == null)
            check("C-06 corrupt envelope is evicted", store.get(ConnectAuthorizationCache.KEY) == null)
            // Version mismatch: evict + null (never silently trusted, never a crash).
            cache.set(auth)
            val raw = store.get(ConnectAuthorizationCache.KEY)!!
            store.put(ConnectAuthorizationCache.KEY, raw.replace("\"version\":1", "\"version\":99"))
            check("C-06 version-mismatched envelope reads as no authorization", cache.get() == null)
            check("C-06 version-mismatched envelope is evicted",
                store.get(ConnectAuthorizationCache.KEY) == null)
            // Clear removes everything (the C-09 forget leg's store half).
            cache.set(auth)
            cache.clear()
            check("C-06 clear removes the envelope",
                cache.get() == null && store.get(ConnectAuthorizationCache.KEY) == null)
        }
        // Failed durable save: degrade to in-memory-only for the
        // process — the session continues, nothing pretends to be
        // persisted, and a fresh process (new cache, same store)
        // restores nothing.
        val failing = PutFailStore()
        failing.failWhen = { true }
        val degraded = ConnectAuthorizationCache(failing)
        runBlocking {
            degraded.set(auth)
            check("C-06 failed save degrades to in-memory for the process",
                degraded.get()?.authToken == "token-abc")
            check("C-06 degraded authorization is NOT in the durable store",
                failing.inner.get(ConnectAuthorizationCache.KEY) == null)
            degraded.clear()
            check("C-06 degraded cache still clears", degraded.get() == null)
        }
        check("C-06 a fresh process restores nothing after a degraded save",
            runBlocking { ConnectAuthorizationCache(failing).get() } == null)
    }

    println("== C-09: disconnect sequence (unconditional forget + unbind) ==")
    runBlocking {
        var forgotten = false
        var unbound = false
        val revoked = DisconnectSequence.run(
            attemptDeauthorize = { true },
            forgetLocally = { forgotten = true },
            unbind = { unbound = true }
        )
        check("C-09 successful deauthorize reports revoked; forget+unbind ran",
            revoked && forgotten && unbound)
        forgotten = false; unbound = false
        val threw = DisconnectSequence.run(
            attemptDeauthorize = { throw RuntimeException("wallet gone") },
            forgetLocally = { forgotten = true },
            unbind = { unbound = true }
        )
        check("C-09 THROWING deauthorize still forgets + unbinds; reports not-revoked",
            !threw && forgotten && unbound)
        forgotten = false; unbound = false
        val declined = DisconnectSequence.run(
            attemptDeauthorize = { false },
            forgetLocally = { forgotten = true },
            unbind = { unbound = true }
        )
        check("C-09 failed deauthorize still forgets + unbinds; reports not-revoked",
            !declined && forgotten && unbound)
    }

    println("== C-10: migration seam (legacy token deleted, never read into an authorization) ==")
    run {
        val store = InMemorySecureStore()
        store.put("mwa_auth_token", "legacy-secret")
        val deleted = ConnectMigration.runOnce(store)
        check("C-10 legacy token deleted at migration", deleted && store.get("mwa_auth_token") == null)
        check("C-10 reconnect notice armed when no envelope exists", ConnectMigration.noticePending(store))
        check("C-10 runOnce is idempotent", !ConnectMigration.runOnce(store))
        ConnectMigration.dismissNotice(store)
        check("C-10 notice dismisses", !ConnectMigration.noticePending(store))
        val withEnvelope = InMemorySecureStore()
        withEnvelope.put("mwa_auth_token", "legacy-secret")
        withEnvelope.put(ConnectAuthorizationCache.KEY, "{}")
        ConnectMigration.runOnce(withEnvelope)
        check("C-10 existing envelope suppresses the notice", !ConnectMigration.noticePending(withEnvelope))
        check("C-10 token still deleted when an envelope exists", withEnvelope.get("mwa_auth_token") == null)
        val clean = InMemorySecureStore()
        check("C-10 no legacy token -> no deletion, no notice",
            !ConnectMigration.runOnce(clean) && !ConnectMigration.noticePending(clean))
    }

    println("== C-21: SESSION_CLOSED unresolved-no-signature machinery ==")
    // Fakes for the no-signature chain reads.
    class FakeNoSigChain(
        var signatures: List<String>? = emptyList(),
        var details: List<ObservedTransferDetail>? = emptyList(),
        var height: Long? = 500L
    ) : NoSignatureReconciler.NoSignatureChainReader {
        override fun signaturesForAddress(addressBase58: String, limit: Int) = signatures
        override fun fetchTransferDetails(signature: String) = details
        override fun currentBlockHeight() = height
    }
    class RecordingBlockhash(
        var ctx: BuildContext = BuildContext("buildhash", 1_000L)
    ) : BlockhashProvider, BuildContextSource {
        override fun recentBlockhash(): ByteArray = ByteArray(32) { 7 }
        override fun lastBuildContext(): BuildContext = ctx
    }
    run {
        val buyer = Base58.encode(ByteArray(32) { 3 })
        val flow = PurchaseFlow(InMemorySecureStore())
        val ledger = EntitlementLedger(InMemorySecureStore())
        // Settlement verifies ONLY the found signature.
        val settleChain = object : ChainReader {
            override fun fetchTransaction(signature: String): ObservedTransaction? =
                if (signature == "sig-found") ObservedTransaction(
                    signature, true,
                    listOf(ObservedTransfer(TEST_RECIPIENT, MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS)),
                    false
                ) else null
        }
        val settlement = SettlementVerifier(settleChain) { TEST_RECIPIENT }
        val noSig = FakeNoSigChain()
        val sender = CountingSender(SendOutcome.UnresolvedNoSignature("SESSION_CLOSED"))
        val coord = PurchaseCoordinator(
            flow, ledger, settlement, RecordingBlockhash(), sender,
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(noSig, settlement)) { TEST_RECIPIENT }
        val r1 = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("C-21(i) ambiguous send returns UnresolvedNoSignature, never Failed",
            r1 is PurchaseResult.UnresolvedNoSignature)
        check("C-21 sender called exactly once", sender.calls == 1)
        val open = flow.unresolvedNoSignatureOutcomes()
        check("C-21 durable UNRESOLVED_NO_SIGNATURE outcome recorded", open.size == 1)
        val o = open.first()
        check("C-21 intent record carries buyer/blockhash/LVBH/amount/mint/recipient/kind",
            o.buyerIdentity == buyer && o.blockhash == "buildhash" &&
                o.lastValidBlockHeight == 1_000L &&
                o.amountBaseUnits == MoneyConfig.PRICE_USDC_BASE_UNITS &&
                o.mint == MoneyConfig.USDC_MINT && o.recipient == TEST_RECIPIENT &&
                o.walletErrorKind == "SESSION_CLOSED" && o.signature == null)
        check("C-21 recorded message is the C-23 line (never 'failed'/'not charged')",
            o.message == PurchaseCoordinator.UNRESOLVED_USER_MESSAGE)
        // (i) the guard: while open, no new intent, sender never re-called.
        val r2 = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("C-21(i) open outcome blocks a second purchase", r2 is PurchaseResult.UnresolvedNoSignature)
        check("C-21(i) sender never re-called while open", sender.calls == 1)
        check("C-21(i) still exactly one intent record", flow.unresolvedNoSignatureOutcomes().size == 1)
        // (iii) another buyer's identical payment must NOT settle this intent.
        noSig.signatures = listOf("sig-found")
        noSig.details = listOf(
            ObservedTransferDetail(
                Base58.encode(ByteArray(32) { 8 }), TEST_RECIPIENT,
                MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS
            )
        )
        val r3 = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("C-21(iii) another buyer's payment does not settle (buyer-source match)",
            r3 is PurchaseResult.UnresolvedNoSignature && !ledger.entitlementFor(buyer).passActive)
        // (iii) the buyer's own payment settles through the normal grant path.
        noSig.details = listOf(
            ObservedTransferDetail(
                buyer, TEST_RECIPIENT, MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS
            )
        )
        val r4 = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("C-21(iii) matching payment settles to Granted", r4 is PurchaseResult.Granted)
        check("C-21 outcome closed GRANTED with the found signature",
            flow.outcomeFor(o.idempotencyKey)?.state == PurchaseState.GRANTED &&
                flow.outcomeFor(o.idempotencyKey)?.signature == "sig-found")
        check("C-21 grant is the ledger's exactly-once receipt",
            ledger.allReceipts().count { it.signature == "sig-found" } == 1)
    }
    run {
        // (iii) expiry: height passes LVBH with no match -> the
        // not-submitted close; purchase re-opens.
        val buyer = Base58.encode(ByteArray(32) { 4 })
        val flow = PurchaseFlow(InMemorySecureStore())
        val ledger = EntitlementLedger(InMemorySecureStore())
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val noSig = FakeNoSigChain(signatures = emptyList(), details = emptyList(), height = 1_001L)
        val sender = CountingSender(SendOutcome.UnresolvedNoSignature("WALLET_ERROR"))
        val coord = PurchaseCoordinator(
            flow, ledger, settlement, RecordingBlockhash(), sender,
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(noSig, settlement)) { TEST_RECIPIENT }
        val r1 = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("C-21 WALLET_ERROR (the other ambiguous kind) also opens an outcome",
            r1 is PurchaseResult.UnresolvedNoSignature)
        val key = flow.unresolvedNoSignatureOutcomes().first().idempotencyKey
        val freed = coord.reconcileNoSignature(flow.outcomeFor(key)!!)
        check("C-21(iii) expiry close frees the buyer (reconcile returns null)", freed == null)
        val closed = flow.outcomeFor(key)
        check("C-21(iii) expiry close is FAILED with the not-submitted message only here",
            closed?.state == PurchaseState.FAILED &&
                closed?.message == PurchaseCoordinator.NOT_SUBMITTED_MESSAGE)
        check("C-21(iii) nothing granted on expiry", !ledger.entitlementFor(buyer).passActive)
        sender.outcome = SendOutcome.Failed(TxFailureKind.WALLET_DECLINED)
        val r2 = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("C-21 purchase re-opens after the expiry close",
            r2 is PurchaseResult.Failed && sender.calls == 2)
    }
    run {
        // Fail-closed: an unreadable chain leaves the outcome open
        // forever — never closed, never granted.
        val buyer = Base58.encode(ByteArray(32) { 9 })
        val flow = PurchaseFlow(InMemorySecureStore())
        val ledger = EntitlementLedger(InMemorySecureStore())
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val noSig = FakeNoSigChain(signatures = null, details = null, height = null)
        val coord = PurchaseCoordinator(
            flow, ledger, settlement, RecordingBlockhash(),
            CountingSender(SendOutcome.UnresolvedNoSignature("SESSION_CLOSED")),
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(noSig, settlement)) { TEST_RECIPIENT }
        coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        val key = flow.unresolvedNoSignatureOutcomes().first().idempotencyKey
        val still = coord.reconcileNoSignature(flow.outcomeFor(key)!!)
        check("C-21 unreadable chain -> outcome stays open (fail-closed)",
            still is PurchaseResult.UnresolvedNoSignature &&
                flow.outcomeFor(key)?.state == PurchaseState.UNRESOLVED_NO_SIGNATURE &&
                !ledger.entitlementFor(buyer).passActive)
    }

    println("== C-20 (coordinator-level proxy): a certainly-failed send never wedges the buyer ==")
    // The lifecycle half of C-20 (wallet calls inside the host
    // activity's lifecycleScope) is device evidence from the
    // runtime cells; this is the half the harness can prove: the
    // expired-cache shape — a send that fails certainly (e.g. the
    // wallet refuses a stale session) closes FAILED and the next
    // purchase proceeds normally.
    run {
        val buyer = Base58.encode(ByteArray(32) { 6 })
        val flow = PurchaseFlow(InMemorySecureStore())
        val ledger = EntitlementLedger(InMemorySecureStore())
        val settlement = SettlementVerifier(
            FakeChain(
                ObservedTransaction(
                    "sig-ok", true,
                    listOf(ObservedTransfer(TEST_RECIPIENT, MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS)),
                    false
                )
            )
        ) { TEST_RECIPIENT }
        val sender = CountingSender(SendOutcome.Failed(TxFailureKind.OTHER))
        val coord = PurchaseCoordinator(
            flow, ledger, settlement, FixedBlockhash(), sender,
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true }
        ) { TEST_RECIPIENT }
        val r1 = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        sender.outcome = SendOutcome.Sent("sig-ok")
        val r2 = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("C-20 proxy: failed send closes FAILED; next purchase proceeds to Granted",
            r1 is PurchaseResult.Failed && r2 is PurchaseResult.Granted && sender.calls == 2)
    }

    println("== TEKKADAN AUDIT R1: C-21 hardening (F1-F4, 2026-10-30) ==")
    // Fakes local to this section.
    class ThrowingSender(val make: () -> Exception) : WalletSender {
        var calls = 0
        override fun sendTransaction(serializedTransaction: ByteArray): SendOutcome {
            calls++
            throw make()
        }
    }
    class ValueFailStore(val inner: InMemorySecureStore = InMemorySecureStore()) : SecureKeyValueStore {
        var failWhenValueContains: String? = null
        override fun get(key: String): String? = inner.get(key)
        override fun put(key: String, value: String): Boolean {
            val fragment = failWhenValueContains
            return if (fragment != null && value.contains(fragment)) false else inner.put(key, value)
        }
        override fun remove(key: String) = inner.remove(key)
        override fun keysWithPrefix(prefix: String): Set<String> = inner.keysWithPrefix(prefix)
    }
    class SnoopingSender(val store: SecureKeyValueStore, var outcome: SendOutcome) : WalletSender {
        var calls = 0
        var stateAtSend: String? = null
        var blockhashAtSend: String? = null
        override fun sendTransaction(serializedTransaction: ByteArray): SendOutcome {
            calls++
            val raw = store.keysWithPrefix("intent:")
                .mapNotNull { store.get(it) }
                .firstOrNull { it.contains("AWAITING_WALLET") }
            if (raw != null) {
                val j = org.json.JSONObject(raw)
                stateAtSend = j.optString("state")
                blockhashAtSend = if (j.isNull("bh")) null else j.optString("bh")
            }
            return outcome
        }
    }
    fun auditOutcome(buyer: String): PurchaseOutcome = PurchaseOutcome(
        idempotencyKey = "audit-key", state = PurchaseState.UNRESOLVED_NO_SIGNATURE,
        signature = null, errorClass = null, message = PurchaseCoordinator.UNRESOLVED_USER_MESSAGE,
        buyerIdentity = buyer, blockhash = "bh", lastValidBlockHeight = 1_000L,
        amountBaseUnits = MoneyConfig.PRICE_USDC_BASE_UNITS, mint = MoneyConfig.USDC_MINT,
        recipient = TEST_RECIPIENT, walletErrorKind = "SESSION_CLOSED", createdAtMs = 1L
    )
    run {
        // F1: expiry must fire ONLY on a conclusive no-match scan.
        val buyer = Base58.encode(ByteArray(32) { 13 })
        val outcome = auditOutcome(buyer)
        val verifiedSettlement = SettlementVerifier(
            FakeChain(
                ObservedTransaction(
                    "sig-ok", true,
                    listOf(ObservedTransfer(TEST_RECIPIENT, MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS)),
                    false
                )
            )
        ) { TEST_RECIPIENT }
        val unreachableSettlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val failedSettlement = SettlementVerifier(
            FakeChain(ObservedTransaction("sig-f", true, emptyList(), true))
        ) { TEST_RECIPIENT }
        check("AUDIT-F1 unreadable signature list + height past LVBH -> stays open",
            NoSignatureReconciler(
                FakeNoSigChain(signatures = null, height = 1_001L), unreachableSettlement
            ).reconcile(outcome) == NoSignatureReconciler.Result.StillOpen)
        check("AUDIT-F1 candidate verdict RPC_UNREACHABLE + height past LVBH -> stays open",
            NoSignatureReconciler(
                FakeNoSigChain(signatures = listOf("sig-x"), height = 1_001L), unreachableSettlement
            ).reconcile(outcome) == NoSignatureReconciler.Result.StillOpen)
        check("AUDIT-F1 VERIFIED candidate with unreadable details + height past LVBH -> stays open",
            NoSignatureReconciler(
                FakeNoSigChain(signatures = listOf("sig-ok"), details = null, height = 1_001L),
                verifiedSettlement
            ).reconcile(outcome) == NoSignatureReconciler.Result.StillOpen)
        check("AUDIT-F1 definitive-refusal candidate + height past LVBH -> expiry close stands",
            NoSignatureReconciler(
                FakeNoSigChain(signatures = listOf("sig-f"), height = 1_001L), failedSettlement
            ).reconcile(outcome) == NoSignatureReconciler.Result.ExpiredUnmatched)
        check("AUDIT-F1 VERIFIED candidate, readable non-matching details + height past LVBH -> expiry close stands",
            NoSignatureReconciler(
                FakeNoSigChain(
                    signatures = listOf("sig-ok"),
                    details = listOf(
                        ObservedTransferDetail(
                            Base58.encode(ByteArray(32) { 8 }), TEST_RECIPIENT,
                            MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS
                        )
                    ),
                    height = 1_001L
                ),
                verifiedSettlement
            ).reconcile(outcome) == NoSignatureReconciler.Result.ExpiredUnmatched)
        check("AUDIT-F1 a match wins over expiry even past LVBH",
            NoSignatureReconciler(
                FakeNoSigChain(
                    signatures = listOf("sig-ok"),
                    details = listOf(
                        ObservedTransferDetail(
                            buyer, TEST_RECIPIENT,
                            MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS
                        )
                    ),
                    height = 1_001L
                ),
                verifiedSettlement
            ).reconcile(outcome) == NoSignatureReconciler.Result.Matched("sig-ok"))
    }
    run {
        // F2: an exception escaping the sender — cancellation above
        // all — must record the ambiguous outcome, never strand the
        // intent with no record.
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val confirmer = object : SheetConfirmer {
            override fun confirm(sheet: PurchaseSheetData) = true
        }
        val buyerA = Base58.encode(ByteArray(32) { 14 })
        val flowA = PurchaseFlow(InMemorySecureStore())
        val cancelSender = ThrowingSender { java.util.concurrent.CancellationException("activity destroyed") }
        val coordA = PurchaseCoordinator(
            flowA, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            cancelSender, confirmer,
            noSignatureReconciler = NoSignatureReconciler(FakeNoSigChain(), settlement)
        ) { TEST_RECIPIENT }
        val r1 = coordA.purchase(MoneyConfig.ITEM_DAY_PASS, buyerA)
        check("AUDIT-F2 CancellationException out of the sender records UNRESOLVED_NO_SIGNATURE (kind CANCELLED)",
            r1 is PurchaseResult.UnresolvedNoSignature &&
                flowA.unresolvedNoSignatureOutcomes().singleOrNull()?.walletErrorKind == "CANCELLED" &&
                cancelSender.calls == 1)
        val r2 = coordA.purchase(MoneyConfig.ITEM_DAY_PASS, buyerA)
        check("AUDIT-F2 the recorded outcome blocks a second send after cancellation",
            r2 is PurchaseResult.UnresolvedNoSignature && cancelSender.calls == 1)
        val buyerB = Base58.encode(ByteArray(32) { 15 })
        val flowB = PurchaseFlow(InMemorySecureStore())
        val boomSender = ThrowingSender { RuntimeException("sdk exploded") }
        val coordB = PurchaseCoordinator(
            flowB, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            boomSender, confirmer,
            noSignatureReconciler = NoSignatureReconciler(FakeNoSigChain(), settlement)
        ) { TEST_RECIPIENT }
        val r3 = coordB.purchase(MoneyConfig.ITEM_DAY_PASS, buyerB)
        check("AUDIT-F2 an unclassified exception out of the sender records UNRESOLVED_NO_SIGNATURE (kind WALLET_ERROR)",
            r3 is PurchaseResult.UnresolvedNoSignature &&
                flowB.unresolvedNoSignatureOutcomes().singleOrNull()?.walletErrorKind == "WALLET_ERROR" &&
                boomSender.calls == 1)
        check("AUDIT C-21 outcome carries a real creation timestamp",
            flowB.unresolvedNoSignatureOutcomes().singleOrNull()?.let { it.createdAtMs > 0L } == true)
    }
    run {
        // F2: the built transaction's context is stamped onto the
        // intent BEFORE the sender runs (process-death evidence).
        val store = InMemorySecureStore()
        val flow = PurchaseFlow(store)
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val snooper = SnoopingSender(store, SendOutcome.UnresolvedNoSignature("SESSION_CLOSED"))
        val coord = PurchaseCoordinator(
            flow, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            snooper,
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(FakeNoSigChain(), settlement)
        ) { TEST_RECIPIENT }
        coord.purchase(MoneyConfig.ITEM_DAY_PASS, Base58.encode(ByteArray(32) { 16 }))
        check("AUDIT-F2 build context is stamped on the AWAITING_WALLET record before the sender runs",
            snooper.stateAtSend == "AWAITING_WALLET" && snooper.blockhashAtSend == "buildhash")
    }
    run {
        // F2: process death mid-send — the durable AWAITING_WALLET
        // record a dead process leaves behind converts into the
        // no-signature machinery at the next launch's reconcile,
        // and the guard covers the buyer from then on.
        val store = InMemorySecureStore()
        val buyer = Base58.encode(ByteArray(32) { 17 })
        val flow1 = PurchaseFlow(store)
        val begun = flow1.beginIntent(MoneyConfig.ITEM_DAY_PASS) as PurchaseFlow.BeginResult.Begun
        flow1.recordOutcome(
            PurchaseOutcome(
                begun.idempotencyKey, PurchaseState.AWAITING_WALLET, null, null,
                "Awaiting wallet.", buyer,
                blockhash = "bh", lastValidBlockHeight = 1_000L, createdAtMs = 5L
            )
        )
        // New process: fresh flow/coordinator over the same store.
        val flow2 = PurchaseFlow(store)
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val sender = CountingSender(SendOutcome.Sent("sig-late"))
        val coord2 = PurchaseCoordinator(
            flow2, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            sender,
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(
                FakeNoSigChain(signatures = emptyList(), height = 500L), settlement
            )
        ) { TEST_RECIPIENT }
        coord2.reconcileOnLaunch(buyer)
        val converted = flow2.outcomeFor(begun.idempotencyKey)
        check("AUDIT-F2 stranded AWAITING_WALLET converts to UNRESOLVED_NO_SIGNATURE at launch reconcile",
            converted?.state == PurchaseState.UNRESOLVED_NO_SIGNATURE &&
                converted?.recipient == TEST_RECIPIENT &&
                converted?.amountBaseUnits == MoneyConfig.PRICE_USDC_BASE_UNITS &&
                converted?.lastValidBlockHeight == 1_000L)
        val blocked = coord2.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("AUDIT-F2 converted outcome blocks a fresh purchase (sender never called)",
            blocked is PurchaseResult.UnresolvedNoSignature && sender.calls == 0)
    }
    run {
        // The C-21 guard is per-buyer: another buyer is unaffected
        // by an open outcome.
        val buyerA = Base58.encode(ByteArray(32) { 31 })
        val buyerB = Base58.encode(ByteArray(32) { 32 })
        val settleChain = object : ChainReader {
            override fun fetchTransaction(signature: String): ObservedTransaction? =
                if (signature == "sig-b") ObservedTransaction(
                    "sig-b", true,
                    listOf(ObservedTransfer(TEST_RECIPIENT, MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS)),
                    false
                ) else null
        }
        val settlement = SettlementVerifier(settleChain) { TEST_RECIPIENT }
        val sender = CountingSender(SendOutcome.UnresolvedNoSignature("SESSION_CLOSED"))
        val coord = PurchaseCoordinator(
            PurchaseFlow(InMemorySecureStore()), EntitlementLedger(InMemorySecureStore()),
            settlement, RecordingBlockhash(), sender,
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(FakeNoSigChain(), settlement)
        ) { TEST_RECIPIENT }
        coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyerA)
        sender.outcome = SendOutcome.Sent("sig-b")
        val rb = coord.purchase(MoneyConfig.ITEM_DAY_PASS, buyerB)
        check("AUDIT guard is per-buyer: buyer B purchases while A's outcome is open",
            rb is PurchaseResult.Granted && sender.calls == 2)
    }
    run {
        // F4: durability failures must fail closed — a failed
        // UNRESOLVED persist holds the in-flight slot; a failed
        // expiry-close persist keeps the outcome open.
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val confirmer = object : SheetConfirmer {
            override fun confirm(sheet: PurchaseSheetData) = true
        }
        val buyerA = Base58.encode(ByteArray(32) { 18 })
        val storeA = ValueFailStore()
        storeA.failWhenValueContains = "UNRESOLVED_NO_SIGNATURE"
        val flowA = PurchaseFlow(storeA)
        val senderA = CountingSender(SendOutcome.UnresolvedNoSignature("SESSION_CLOSED"))
        val coordA = PurchaseCoordinator(
            flowA, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            senderA, confirmer,
            noSignatureReconciler = NoSignatureReconciler(FakeNoSigChain(), settlement)
        ) { TEST_RECIPIENT }
        coordA.purchase(MoneyConfig.ITEM_DAY_PASS, buyerA)
        val rA = coordA.purchase(MoneyConfig.ITEM_DAY_PASS, buyerA)
        check("AUDIT-F4 failed UNRESOLVED persist keeps the buyer blocked in-process (no second send)",
            rA is PurchaseResult.AlreadyInFlight && senderA.calls == 1 &&
                flowA.unresolvedNoSignatureOutcomes().isEmpty())
        val buyerB = Base58.encode(ByteArray(32) { 19 })
        val storeB = ValueFailStore()
        val flowB = PurchaseFlow(storeB)
        val senderB = CountingSender(SendOutcome.UnresolvedNoSignature("WALLET_ERROR"))
        val coordB = PurchaseCoordinator(
            flowB, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            senderB, confirmer,
            noSignatureReconciler = NoSignatureReconciler(
                FakeNoSigChain(signatures = emptyList(), height = 1_001L), settlement
            )
        ) { TEST_RECIPIENT }
        coordB.purchase(MoneyConfig.ITEM_DAY_PASS, buyerB)
        val keyB = flowB.unresolvedNoSignatureOutcomes().first().idempotencyKey
        storeB.failWhenValueContains = "\"FAILED\""
        val rB = coordB.reconcileNoSignature(flowB.outcomeFor(keyB)!!)
        check("AUDIT-F4 failed expiry-close persist keeps the outcome open (no fall-through)",
            rB is PurchaseResult.UnresolvedNoSignature &&
                flowB.outcomeFor(keyB)?.state == PurchaseState.UNRESOLVED_NO_SIGNATURE)
    }
    run {
        // Rule 6: pre-C-21 records (none of the C-21 keys present)
        // still decode, with the new fields null and ts 0.
        val store = InMemorySecureStore()
        store.put(
            "intent:legacy1",
            "{\"key\":\"legacy1\",\"state\":\"FAILED\",\"sig\":null,\"err\":null,\"msg\":\"old record\",\"buyer\":null}"
        )
        val decoded = PurchaseFlow(store).outcomeFor("legacy1")
        check("AUDIT pre-C-21 record decodes with null C-21 fields + ts 0",
            decoded != null && decoded.blockhash == null && decoded.lastValidBlockHeight == null &&
                decoded.amountBaseUnits == null && decoded.mint == null && decoded.recipient == null &&
                decoded.walletErrorKind == null && decoded.createdAtMs == 0L)
    }
    run {
        // F3: production transfer-detail extraction, exercised
        // against fabricated getTransaction results (no network).
        val reader = SolanaRpcChainReader("http://127.0.0.1:1/unused")
        val buyer = Base58.encode(ByteArray(32) { 21 })
        val alice = Base58.encode(ByteArray(32) { 22 })
        val dave = Base58.encode(ByteArray(32) { 23 })
        val usdc = MoneyConfig.USDC_MINT
        fun acctKey(pub: String) = org.json.JSONObject().put("pubkey", pub)
        fun bal(idx: Int, owner: String, amount: String) = org.json.JSONObject()
            .put("accountIndex", idx).put("owner", owner).put("mint", usdc)
            .put("uiTokenAmount", org.json.JSONObject().put("amount", amount))
        fun transferChecked(source: String, dest: String, amount: String) = org.json.JSONObject()
            .put("program", "spl-token")
            .put(
                "parsed", org.json.JSONObject().put("type", "transferChecked")
                    .put(
                        "info", org.json.JSONObject()
                            .put("source", source).put("destination", dest).put("mint", usdc)
                            .put("tokenAmount", org.json.JSONObject().put("amount", amount))
                    )
            )
        fun txResult(
            instructions: org.json.JSONArray,
            pre: org.json.JSONArray,
            post: org.json.JSONArray,
            failed: Boolean = false
        ): org.json.JSONObject {
            val keys = org.json.JSONArray()
                .put(acctKey("acctB")).put(acctKey("acctA")).put(acctKey("acctR")).put(acctKey("acctD"))
            val meta = org.json.JSONObject()
                .put("err", if (failed) org.json.JSONObject() else org.json.JSONObject.NULL)
                .put("preTokenBalances", pre).put("postTokenBalances", post)
                .put("innerInstructions", org.json.JSONArray())
            return org.json.JSONObject()
                .put(
                    "transaction", org.json.JSONObject()
                        .put(
                            "message", org.json.JSONObject()
                                .put("accountKeys", keys).put("instructions", instructions)
                        )
                )
                .put("meta", meta)
        }
        val collisionPre = org.json.JSONArray()
            .put(bal(0, buyer, "7000000")).put(bal(1, alice, "7000000"))
            .put(bal(2, TEST_RECIPIENT, "0")).put(bal(3, dave, "0"))
        val collisionPost = org.json.JSONArray()
            .put(bal(0, buyer, "3500000")).put(bal(1, alice, "3500000"))
            .put(bal(2, TEST_RECIPIENT, "3500000")).put(bal(3, dave, "3500000"))
        val collisionIx = org.json.JSONArray()
            .put(transferChecked("acctB", "acctD", "3500000"))
            .put(transferChecked("acctA", "acctR", "3500000"))
        val collision = reader.extractTransferDetails(txResult(collisionIx, collisionPre, collisionPost))
        check("AUDIT-F3 colliding equal-amount legs: real legs extracted, NO fabricated buyer->recipient detail",
            collision != null && collision.size == 2 &&
                collision.any { it.fromOwner == buyer && it.toOwner == dave } &&
                collision.any { it.fromOwner == alice && it.toOwner == TEST_RECIPIENT } &&
                collision.none { it.fromOwner == buyer && it.toOwner == TEST_RECIPIENT })
        val single = reader.extractTransferDetails(
            txResult(
                org.json.JSONArray().put(transferChecked("acctB", "acctR", "3500000")),
                org.json.JSONArray().put(bal(0, buyer, "3500000")).put(bal(2, TEST_RECIPIENT, "0")),
                org.json.JSONArray().put(bal(0, buyer, "0")).put(bal(2, TEST_RECIPIENT, "3500000"))
            )
        )
        check("AUDIT-F3 single-leg payment extracts the exact buyer->recipient detail",
            single != null && single.size == 1 &&
                single[0].fromOwner == buyer && single[0].toOwner == TEST_RECIPIENT &&
                single[0].mint == usdc && single[0].amountBaseUnits == 3_500_000L)
        val ambiguousFallback = reader.extractTransferDetails(
            txResult(org.json.JSONArray(), collisionPre, collisionPost)
        )
        check("AUDIT-F3 delta fallback refuses ambiguous pairing (inconclusive, never fabricated)",
            ambiguousFallback == null)
        val uniqueFallback = reader.extractTransferDetails(
            txResult(
                org.json.JSONArray(),
                org.json.JSONArray().put(bal(0, buyer, "3500000")).put(bal(2, TEST_RECIPIENT, "0")),
                org.json.JSONArray().put(bal(0, buyer, "0")).put(bal(2, TEST_RECIPIENT, "3500000"))
            )
        )
        check("AUDIT-F3 delta fallback pairs only the forced 1-to-1 case",
            uniqueFallback != null && uniqueFallback.size == 1 &&
                uniqueFallback[0].fromOwner == buyer && uniqueFallback[0].toOwner == TEST_RECIPIENT)
        val failedTx = reader.extractTransferDetails(
            txResult(org.json.JSONArray(), org.json.JSONArray(), org.json.JSONArray(), failed = true)
        )
        check("AUDIT-F3 failed transaction yields no details (empty, not null)",
            failedTx != null && failedTx.isEmpty())
    }

    println("== TEKKADAN AUDIT R1 (Scope A): wallet-seam hardening (A-F1/A-F2/A-F4) ==")
    run {
        // A-F1: a failed re-save must not leave the SUPERSEDED
        // envelope durable — it would resurrect next process.
        val addrA = Base58.encode(ByteArray(32) { 41 })
        val addrB = Base58.encode(ByteArray(32) { 42 })
        fun auditAuth(addr: String, token: String) = StoredAuthorization(
            accounts = listOf(SeekerAccount(addr, ByteArray(32) { 5 }, "Seeker")),
            authToken = token,
            walletUriBase = "solana-wallet:/",
            chain = "solana:mainnet",
            capabilities = SeekerWalletCapabilities(1, 1, listOf("legacy"), listOf("feat"), true)
        )
        val store = PutFailStore()
        val cache = ConnectAuthorizationCache(store)
        runBlocking { cache.set(auditAuth(addrA, "token-A")) }
        store.failWhen = { key -> key == ConnectAuthorizationCache.KEY }
        runBlocking { cache.set(auditAuth(addrB, "token-B")) }
        check("AUDIT-A-F1 failed re-save evicts the superseded durable envelope",
            store.inner.get(ConnectAuthorizationCache.KEY) == null)
        check("AUDIT-A-F1 the new authorization is held in memory for the process",
            runBlocking { cache.get()?.authToken } == "token-B")
        check("AUDIT-A-F1 a fresh process restores NOTHING (no resurrection of the superseded wallet)",
            runBlocking { ConnectAuthorizationCache(store).get() } == null)
    }
    run {
        // A-F2: the degraded paths now run under the same lock as
        // clear(); functionally they must stay consistent (and
        // deadlock-free) across set/set/get/clear.
        val store = PutFailStore()
        store.failWhen = { true }
        val cache = ConnectAuthorizationCache(store)
        fun auditAuth(token: String) = StoredAuthorization(
            accounts = listOf(SeekerAccount(Base58.encode(ByteArray(32) { 43 }), ByteArray(32) { 5 }, "Seeker")),
            authToken = token,
            walletUriBase = "solana-wallet:/",
            chain = "solana:mainnet",
            capabilities = SeekerWalletCapabilities(1, 1, listOf("legacy"), listOf("feat"), true)
        )
        val consistent = runBlocking {
            cache.set(auditAuth("token-C"))
            cache.set(auditAuth("token-D"))
            val got = cache.get()
            cache.clear()
            got?.authToken == "token-D" && cache.get() == null
        }
        check("AUDIT-A-F2 degraded get/set/clear stay consistent under the one lock", consistent)
    }
    runBlocking {
        // A-F4: cancellation of the deauthorize attempt completes
        // the unconditional forget + unbind, then PROPAGATES —
        // it is never reported as an ordinary revoke failure.
        var forgotten = false
        var unbound = false
        var propagated = false
        try {
            DisconnectSequence.run(
                attemptDeauthorize = { throw java.util.concurrent.CancellationException("caller gone") },
                forgetLocally = { forgotten = true },
                unbind = { unbound = true }
            )
        } catch (e: java.util.concurrent.CancellationException) {
            propagated = true
        }
        check("AUDIT-A-F4 cancellation completes forget+unbind, then propagates",
            propagated && forgotten && unbound)
    }

    println("== TEKKADAN AUDIT R2: fix-round regressions (N-1, N-2, F4 residual, 2026-10-30) ==")
    run {
        // N-1: a system-program SOL transfer shares the parsed
        // type name "transfer" — it must not poison token-transfer
        // extraction for the whole transaction.
        val reader = SolanaRpcChainReader("http://127.0.0.1:1/unused")
        val buyer = Base58.encode(ByteArray(32) { 41 })
        val usdc = MoneyConfig.USDC_MINT
        fun acctKey(pub: String) = org.json.JSONObject().put("pubkey", pub)
        fun bal(idx: Int, owner: String, amount: String) = org.json.JSONObject()
            .put("accountIndex", idx).put("owner", owner).put("mint", usdc)
            .put("uiTokenAmount", org.json.JSONObject().put("amount", amount))
        fun tokenChecked(source: String, dest: String, amount: String) = org.json.JSONObject()
            .put("program", "spl-token")
            .put(
                "parsed", org.json.JSONObject().put("type", "transferChecked")
                    .put(
                        "info", org.json.JSONObject()
                            .put("source", source).put("destination", dest).put("mint", usdc)
                            .put("tokenAmount", org.json.JSONObject().put("amount", amount))
                    )
            )
        fun systemTransfer(source: String, dest: String) = org.json.JSONObject()
            .put("program", "system")
            .put(
                "parsed", org.json.JSONObject().put("type", "transfer")
                    .put(
                        "info", org.json.JSONObject()
                            .put("source", source).put("destination", dest).put("lamports", 5000)
                    )
            )
        fun txResult(
            instructions: org.json.JSONArray,
            pre: org.json.JSONArray,
            post: org.json.JSONArray,
            inner: org.json.JSONArray = org.json.JSONArray()
        ): org.json.JSONObject {
            val keys = org.json.JSONArray().put(acctKey("acctB")).put(acctKey("acctR"))
            val meta = org.json.JSONObject()
                .put("err", org.json.JSONObject.NULL)
                .put("preTokenBalances", pre).put("postTokenBalances", post)
                .put("innerInstructions", inner)
            return org.json.JSONObject()
                .put(
                    "transaction", org.json.JSONObject()
                        .put(
                            "message", org.json.JSONObject()
                                .put("accountKeys", keys).put("instructions", instructions)
                        )
                )
                .put("meta", meta)
        }
        val pre = org.json.JSONArray().put(bal(0, buyer, "7000000")).put(bal(1, TEST_RECIPIENT, "0"))
        val post = org.json.JSONArray().put(bal(0, buyer, "3500000")).put(bal(1, TEST_RECIPIENT, "3500000"))
        val mixed = reader.extractTransferDetails(
            txResult(
                org.json.JSONArray()
                    .put(systemTransfer("acctB", "acctR"))
                    .put(tokenChecked("acctB", "acctR", "3500000")),
                pre, post
            )
        )
        check("R2-N1 system SOL transfer beside a token leg: token leg still extracted (no poisoning)",
            mixed != null && mixed.size == 1 && mixed[0].fromOwner == buyer &&
                mixed[0].toOwner == TEST_RECIPIENT && mixed[0].amountBaseUnits == 3_500_000L)
        val systemOnly = reader.extractTransferDetails(
            txResult(org.json.JSONArray().put(systemTransfer("acctB", "acctR")), pre, pre)
        )
        check("R2-N1 system-only transaction extracts as no token transfers (empty, not null)",
            systemOnly != null && systemOnly.isEmpty())
        val innerMixed = reader.extractTransferDetails(
            txResult(
                org.json.JSONArray().put(tokenChecked("acctB", "acctR", "3500000")),
                pre, post,
                org.json.JSONArray().put(
                    org.json.JSONObject().put("index", 0)
                        .put("instructions", org.json.JSONArray().put(systemTransfer("acctB", "acctR")))
                )
            )
        )
        check("R2-N1 system transfer as an INNER instruction does not poison extraction either",
            innerMixed != null && innerMixed.size == 1 && innerMixed[0].toOwner == TEST_RECIPIENT)
    }
    run {
        // N-2 (reconciler arm): with the send gate in place, a null
        // LVBH proves the transaction was never handed to the
        // wallet — a conclusive no-match scan closes the intent;
        // an inconclusive scan still leaves it open; a match wins.
        val buyer = Base58.encode(ByteArray(32) { 42 })
        val noHeight = auditOutcome(buyer).copy(blockhash = null, lastValidBlockHeight = null)
        val noChainSettlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val verifiedSettlement = SettlementVerifier(
            FakeChain(
                ObservedTransaction(
                    "sig-ok", true,
                    listOf(ObservedTransfer(TEST_RECIPIENT, MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS)),
                    false
                )
            )
        ) { TEST_RECIPIENT }
        check("R2-N2 null LVBH + conclusive no-match scan -> non-submission close",
            NoSignatureReconciler(
                FakeNoSigChain(signatures = emptyList(), height = 500L), noChainSettlement
            ).reconcile(noHeight) == NoSignatureReconciler.Result.ExpiredUnmatched)
        check("R2-N2 null LVBH + unreadable signature list -> stays open",
            NoSignatureReconciler(
                FakeNoSigChain(signatures = null, height = 500L), noChainSettlement
            ).reconcile(noHeight) == NoSignatureReconciler.Result.StillOpen)
        check("R2-N2 null LVBH + matching candidate -> match wins",
            NoSignatureReconciler(
                FakeNoSigChain(
                    signatures = listOf("sig-ok"),
                    details = listOf(
                        ObservedTransferDetail(
                            buyer, TEST_RECIPIENT,
                            MoneyConfig.USDC_MINT, MoneyConfig.PRICE_USDC_BASE_UNITS
                        )
                    ),
                    height = 500L
                ),
                verifiedSettlement
            ).reconcile(noHeight) == NoSignatureReconciler.Result.Matched("sig-ok"))
    }
    run {
        // N-2 (stamp gate): if the build-context stamp cannot be
        // made durable, the purchase refuses BEFORE the wallet is
        // asked — the sender is never called and the durable
        // record is FAILED (nothing submitted, nothing charged).
        val store = ValueFailStore()
        store.failWhenValueContains = "buildhash"
        val flow = PurchaseFlow(store)
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val sender = CountingSender(SendOutcome.Sent("sig-should-not-exist"))
        val coord = PurchaseCoordinator(
            flow, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            sender,
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(FakeNoSigChain(), settlement)
        ) { TEST_RECIPIENT }
        val r = coord.purchase(MoneyConfig.ITEM_DAY_PASS, Base58.encode(ByteArray(32) { 43 }))
        val key = store.keysWithPrefix("intent:").single().removePrefix("intent:")
        val durable = PurchaseFlow(store).outcomeFor(key)
        check("R2-N2 failed build-context stamp refuses the purchase before the wallet (no send, FAILED durable)",
            r is PurchaseResult.Failed && sender.calls == 0 && durable?.state == PurchaseState.FAILED)
    }
    run {
        // N-2 (end to end): process death during the build leaves
        // an AWAITING_WALLET record with NO build context. The
        // next launch converts it and — null LVBH under the send
        // gate proving it was never sent — closes it on a
        // conclusive scan, freeing the buyer.
        val store = InMemorySecureStore()
        val buyer = Base58.encode(ByteArray(32) { 44 })
        val flow1 = PurchaseFlow(store)
        val begun = flow1.beginIntent(MoneyConfig.ITEM_DAY_PASS) as PurchaseFlow.BeginResult.Begun
        flow1.recordOutcome(
            PurchaseOutcome(
                begun.idempotencyKey, PurchaseState.AWAITING_WALLET, null, null,
                "Awaiting wallet.", buyer, createdAtMs = 5L
            )
        )
        val flow2 = PurchaseFlow(store)
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val sender = CountingSender(SendOutcome.Sent("sig-after"))
        val coord2 = PurchaseCoordinator(
            flow2, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            sender,
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(
                FakeNoSigChain(signatures = emptyList(), height = 500L), settlement
            )
        ) { TEST_RECIPIENT }
        coord2.reconcileOnLaunch(buyer)
        val closed = flow2.outcomeFor(begun.idempotencyKey)
        check("R2-N2 stranded record with no build context closes FAILED at launch on a conclusive scan",
            closed?.state == PurchaseState.FAILED)
        val after = coord2.purchase(MoneyConfig.ITEM_DAY_PASS, buyer)
        check("R2-N2 the freed buyer can purchase again (sender called, not blocked)",
            after !is PurchaseResult.UnresolvedNoSignature && sender.calls == 1)
    }
    run {
        // N-2 (recipient re-stamp): an open UNRESOLVED record with
        // no recipient is re-stamped from the provider at launch
        // and reconciles, instead of wedging its buyer forever.
        val store = InMemorySecureStore()
        val buyer = Base58.encode(ByteArray(32) { 45 })
        val flow = PurchaseFlow(store)
        flow.recordOutcome(
            PurchaseOutcome(
                "restamp-key", PurchaseState.UNRESOLVED_NO_SIGNATURE, null, null,
                PurchaseCoordinator.UNRESOLVED_USER_MESSAGE, buyer,
                blockhash = "bh", lastValidBlockHeight = 1_000L,
                amountBaseUnits = MoneyConfig.PRICE_USDC_BASE_UNITS, mint = MoneyConfig.USDC_MINT,
                recipient = null, walletErrorKind = "SESSION_CLOSED", createdAtMs = 1L
            )
        )
        val settlement = SettlementVerifier(FakeChain(null)) { TEST_RECIPIENT }
        val coord = PurchaseCoordinator(
            flow, EntitlementLedger(InMemorySecureStore()), settlement, RecordingBlockhash(),
            CountingSender(SendOutcome.Sent("sig-x")),
            object : SheetConfirmer { override fun confirm(sheet: PurchaseSheetData) = true },
            noSignatureReconciler = NoSignatureReconciler(
                FakeNoSigChain(signatures = emptyList(), height = 1_001L), settlement
            )
        ) { TEST_RECIPIENT }
        coord.reconcileOnLaunch(buyer)
        val after = flow.outcomeFor("restamp-key")
        check("R2-N2 recipient-less UNRESOLVED record is re-stamped and expiry-closed at launch",
            after?.state == PurchaseState.FAILED && after?.recipient == TEST_RECIPIENT)
    }
    run {
        // R2 residual (B-F4 doctrine in flow.reconcileOnLaunch): a
        // failed UNKNOWN marking neither reports a conversion nor
        // disturbs the durable state; healed, the next pass
        // converts.
        val store = PutFailStore()
        val buyer = Base58.encode(ByteArray(32) { 46 })
        val flow1 = PurchaseFlow(store)
        val begun = flow1.beginIntent(MoneyConfig.ITEM_DAY_PASS) as PurchaseFlow.BeginResult.Begun
        flow1.recordOutcome(
            PurchaseOutcome(
                begun.idempotencyKey, PurchaseState.AWAITING_WALLET, null, null,
                "Awaiting wallet.", buyer
            )
        )
        store.failWhen = { it == "intent:" + begun.idempotencyKey }
        val flow2 = PurchaseFlow(store)
        val first = flow2.reconcileOnLaunch()
        check("R2-F4r failed UNKNOWN marking reports no conversion and keeps the durable state",
            first.isEmpty() &&
                flow2.outcomeFor(begun.idempotencyKey)?.state == PurchaseState.AWAITING_WALLET)
        store.failWhen = { false }
        val second = flow2.reconcileOnLaunch()
        check("R2-F4r healed store converts on the next pass",
            second.size == 1 && second[0].state == PurchaseState.UNKNOWN &&
                flow2.outcomeFor(begun.idempotencyKey)?.state == PurchaseState.UNKNOWN)
    }

    println("== AC-09: single price source ==")
    check("AC-09 price anchor $3.50 (P6-b)", MoneyConfig.PRICE_USD_CENTS == 350L && MoneyConfig.priceUsdDisplay() == "$3.50")
    check("AC-09 rail 3.50 USDC at 6dp = anchor x10^6 (P6-c 1:1)", MoneyConfig.PRICE_USDC_BASE_UNITS == MoneyConfig.PRICE_USD_CENTS * 10_000L)
    check("AC-09 pass duration 24h (P6-a)", MoneyConfig.PASS_DURATION_MS == 86_400_000L)

    println("\n$checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
