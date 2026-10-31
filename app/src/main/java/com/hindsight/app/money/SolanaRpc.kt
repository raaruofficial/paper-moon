package com.hindsight.app.money

import com.hindsight.app.util.Base58
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production chain access over Solana JSON-RPC (AC-05 item 4).
 *
 * Endpoint semantics, as specified in [SettlementVerifier]: a single
 * endpoint — the public mainnet RPC — configurable at build time via
 * [ENDPOINT]. No silent failover to another trust domain. Any
 * transport or parse failure surfaces as null, which every caller
 * treats as fail-closed (pending, never a grant).
 */
object RpcConfig {
    const val ENDPOINT: String = "https://api.mainnet-beta.solana.com"
}

class SolanaRpcChainReader(
    private val endpoint: String = RpcConfig.ENDPOINT,
    private val receivingWalletProvider: () -> String? =
        { ReceivingWalletConfig.configuredReceivingWallet }
) : ChainReader, BlockhashProvider, NoSignatureReconciler.NoSignatureChainReader {

    override fun fetchTransaction(signature: String): ObservedTransaction? {
        val response = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "getTransaction")
                .put(
                    "params", JSONArray()
                        .put(signature)
                        .put(
                            JSONObject()
                                .put("encoding", "jsonParsed")
                                .put("commitment", SettlementVerifier.COMMITMENT)
                                .put("maxSupportedTransactionVersion", 0)
                        )
                )
        ) ?: return null
        if (response.isNull("result")) {
            // Found nothing at the named commitment (yet).
            return ObservedTransaction(signature, foundAtCommitment = false, transfers = emptyList(), failed = false)
        }
        val result = response.getJSONObject("result")
        val meta = result.optJSONObject("meta")
            ?: return ObservedTransaction(signature, foundAtCommitment = true, transfers = emptyList(), failed = true)
        val failed = !meta.isNull("err")
        val recipient = receivingWalletProvider()
        // Transfers are derived from token-balance deltas for the
        // recipient: post minus pre for (owner == recipient). This is
        // robust to which instruction shape moved the funds.
        val transfers = ArrayList<ObservedTransfer>()
        if (recipient != null) {
            val pre = tokenBalances(meta.optJSONArray("preTokenBalances"), recipient)
            val post = tokenBalances(meta.optJSONArray("postTokenBalances"), recipient)
            for ((accountIndex, postBalance) in post) {
                val delta = postBalance.amount - (pre[accountIndex]?.amount ?: 0L)
                if (delta != 0L) {
                    transfers.add(ObservedTransfer(recipient, postBalance.mint, delta))
                }
            }
        }
        return ObservedTransaction(signature, foundAtCommitment = true, transfers = transfers, failed = failed)
    }

    override fun recentBlockhash(): ByteArray? =
        recentBlockhashWithHeight()?.first

    /**
     * The latest blockhash WITH its lastValidBlockHeight (C-21):
     * the pair is recorded on an UNRESOLVED_NO_SIGNATURE intent at
     * build time so reconciliation can later prove non-submission
     * (current height > lastValidBlockHeight, no matching
     * transfer). The frozen [BlockhashProvider] interface carries
     * only the hash; this is the height-aware read beside it.
     */
    fun recentBlockhashWithHeight(): Pair<ByteArray, Long>? {
        val response = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "getLatestBlockhash")
                .put("params", JSONArray().put(JSONObject().put("commitment", SettlementVerifier.COMMITMENT)))
        ) ?: return null
        val value = response.optJSONObject("result")?.optJSONObject("value") ?: return null
        val base58 = value.optString("blockhash").takeIf { it.isNotEmpty() } ?: return null
        val bytes = runCatching { Base58.decode(base58) }.getOrNull() ?: return null
        if (!value.has("lastValidBlockHeight")) return null
        return bytes to value.optLong("lastValidBlockHeight")
    }

    override fun signaturesForAddress(addressBase58: String, limit: Int): List<String>? {
        val response = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "getSignaturesForAddress")
                .put(
                    "params", JSONArray()
                        .put(addressBase58)
                        .put(
                            JSONObject()
                                .put("limit", limit)
                                .put("commitment", SettlementVerifier.COMMITMENT)
                        )
                )
        ) ?: return null
        val result = response.optJSONArray("result") ?: return null
        val out = ArrayList<String>(result.length())
        for (i in 0 until result.length()) {
            val entry = result.optJSONObject(i) ?: continue
            // A failed transaction cannot be a payment: skip it.
            if (!entry.isNull("err")) continue
            val signature = entry.optString("signature")
            if (signature.isNotEmpty()) out.add(signature)
        }
        return out
    }

    override fun currentBlockHeight(): Long? {
        val response = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "getBlockHeight")
                .put("params", JSONArray().put(JSONObject().put("commitment", SettlementVerifier.COMMITMENT)))
        ) ?: return null
        if (response.isNull("result")) return null
        return response.optLong("result", -1L).takeIf { it >= 0 }
    }

    override fun fetchTransferDetails(signature: String): List<ObservedTransferDetail>? {
        val response = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "getTransaction")
                .put(
                    "params", JSONArray()
                        .put(signature)
                        .put(
                            JSONObject()
                                .put("encoding", "jsonParsed")
                                .put("commitment", SettlementVerifier.COMMITMENT)
                                .put("maxSupportedTransactionVersion", 0)
                        )
                )
        ) ?: return null
        if (response.isNull("result")) return null
        val result = response.optJSONObject("result") ?: return null
        return extractTransferDetails(result)
    }

    /**
     * Pure extraction of all-owner transfer details from a
     * getTransaction (jsonParsed) RESULT object — factored out of
     * [fetchTransferDetails] so the money harness exercises it
     * against fabricated transactions with no network.
     *
     * PRIMARY: the transaction's parsed instructions (outer +
     * inner) name each transfer leg exactly — source and
     * destination token accounts resolved to owners through the
     * token-balance table. Aggregated balance deltas are NEVER
     * paired across legs by amount: when two legs of the same
     * mint and amount collide, amount-pairing can fabricate a
     * transfer that never happened (TEKKADAN audit F3) — and a
     * fabricated buyer→recipient detail here grants a pass paid
     * for by a stranger. A transfer instruction whose accounts
     * cannot be resolved makes the whole read inconclusive
     * (null), never partially conclusive.
     *
     * FALLBACK (no parsed transfer instructions at all): delta
     * pairing only where it is unambiguous — a (mint, amount)
     * group with exactly one negative and one positive delta.
     * Any group with both sides present but not exactly 1-to-1
     * returns null (inconclusive), which the reconciler treats
     * as still-open, never as no-match.
     */
    internal fun extractTransferDetails(result: JSONObject): List<ObservedTransferDetail>? {
        val meta = result.optJSONObject("meta") ?: return null
        if (!meta.isNull("err")) return emptyList() // failed tx: no payment happened
        // Token account pubkey -> (owner, mint), from the balance
        // table (an account appears there iff the transaction
        // touched it, pre and/or post).
        val accountKeys = result.optJSONObject("transaction")
            ?.optJSONObject("message")?.optJSONArray("accountKeys")
        fun accountPubkey(index: Int): String? =
            accountKeys?.optJSONObject(index)?.optString("pubkey")?.takeIf { it.isNotEmpty() }
        val accountInfo = HashMap<String, Pair<String, String>>()
        for (balances in listOf(meta.optJSONArray("preTokenBalances"), meta.optJSONArray("postTokenBalances"))) {
            if (balances == null) continue
            for (i in 0 until balances.length()) {
                val entry = balances.optJSONObject(i) ?: continue
                val pubkey = accountPubkey(entry.optInt("accountIndex", -1)) ?: continue
                val owner = entry.optString("owner")
                val mint = entry.optString("mint")
                if (owner.isNotEmpty() && mint.isNotEmpty()) accountInfo[pubkey] = owner to mint
            }
        }
        val details = ArrayList<ObservedTransferDetail>()
        var sawTransfer = false
        var unresolvedTransfer = false
        fun scanInstructions(instructions: JSONArray?) {
            if (instructions == null) return
            for (i in 0 until instructions.length()) {
                val instruction = instructions.optJSONObject(i) ?: continue
                // Only token-program instructions can be token
                // transfers. A system-program SOL transfer shares
                // the parsed type name "transfer" but carries
                // lamports and wallet accounts, not a token amount;
                // counting it here poisoned the whole extraction
                // to null and stalled reconciliation for any
                // transaction bundling a SOL leg (TEKKADAN R2
                // audit N-1). Non-token instructions are skipped
                // entirely — never transfers, never unresolved.
                val program = instruction.optString("program")
                if (program != "spl-token" && program != "spl-token-2022") continue
                val parsed = instruction.optJSONObject("parsed") ?: continue
                val type = parsed.optString("type")
                if (type != "transfer" && type != "transferChecked") continue
                sawTransfer = true
                val info = parsed.optJSONObject("info")
                val source = info?.optString("source")?.takeIf { it.isNotEmpty() }
                val destination = info?.optString("destination")?.takeIf { it.isNotEmpty() }
                val amount = if (type == "transferChecked") {
                    info?.optJSONObject("tokenAmount")?.optString("amount")?.toLongOrNull()
                } else {
                    info?.optString("amount")?.toLongOrNull()
                }
                val mint = if (type == "transferChecked") {
                    info?.optString("mint")?.takeIf { it.isNotEmpty() }
                } else {
                    source?.let { accountInfo[it]?.second }
                }
                val fromOwner = source?.let { accountInfo[it]?.first }
                val toOwner = destination?.let { accountInfo[it]?.first }
                if (amount == null || mint == null || fromOwner == null || toOwner == null) {
                    unresolvedTransfer = true
                    continue
                }
                details.add(ObservedTransferDetail(fromOwner, toOwner, mint, amount))
            }
        }
        scanInstructions(
            result.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("instructions")
        )
        val inner = meta.optJSONArray("innerInstructions")
        if (inner != null) {
            for (i in 0 until inner.length()) {
                scanInstructions(inner.optJSONObject(i)?.optJSONArray("instructions"))
            }
        }
        if (unresolvedTransfer) return null
        if (sawTransfer) return details
        // FALLBACK: per-(owner, mint) deltas, paired only where
        // the pairing is forced (exactly one negative and one
        // positive in the (mint, amount) group).
        fun balances(array: JSONArray?): Map<Pair<String, String>, Long> {
            if (array == null) return emptyMap()
            val out = HashMap<Pair<String, String>, Long>()
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val owner = entry.optString("owner")
                val mint = entry.optString("mint")
                val amount = entry.optJSONObject("uiTokenAmount")
                    ?.optString("amount")?.toLongOrNull() ?: continue
                if (owner.isEmpty() || mint.isEmpty()) continue
                val key = owner to mint
                out[key] = (out[key] ?: 0L) + amount
            }
            return out
        }
        val pre = balances(meta.optJSONArray("preTokenBalances"))
        val post = balances(meta.optJSONArray("postTokenBalances"))
        val negatives = HashMap<Pair<String, Long>, MutableList<String>>() // (mint, amount) -> owners
        val positives = HashMap<Pair<String, Long>, MutableList<String>>()
        for (key in pre.keys + post.keys) {
            val delta = (post[key] ?: 0L) - (pre[key] ?: 0L)
            when {
                delta < 0 -> negatives.getOrPut(key.second to -delta) { ArrayList() }.add(key.first)
                delta > 0 -> positives.getOrPut(key.second to delta) { ArrayList() }.add(key.first)
            }
        }
        val out = ArrayList<ObservedTransferDetail>()
        for ((group, fromOwners) in negatives) {
            val toOwners = positives[group] ?: continue
            if (fromOwners.size != 1 || toOwners.size != 1) return null // ambiguous: inconclusive
            out.add(ObservedTransferDetail(fromOwners.single(), toOwners.single(), group.first, group.second))
        }
        return out
    }

    /**
     * The owner's USDC balance in base units (preflight for the
     * INSUFFICIENT_FUNDS taxonomy class), or null when unknown —
     * unknown never blocks a purchase; the wallet itself is the
     * authority, and settlement verification is the grant gate.
     */
    fun usdcBalanceBaseUnits(ownerBase58: String): Long? {
        val response = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "getTokenAccountsByOwner")
                .put(
                    "params", JSONArray()
                        .put(ownerBase58)
                        .put(JSONObject().put("mint", MoneyConfig.USDC_MINT))
                        .put(JSONObject().put("encoding", "jsonParsed"))
                )
        ) ?: return null
        val accounts = response.optJSONObject("result")?.optJSONArray("value") ?: return null
        var total = 0L
        var seen = false
        for (i in 0 until accounts.length()) {
            val info = accounts.optJSONObject(i)
                ?.optJSONObject("account")?.optJSONObject("data")
                ?.optJSONObject("parsed")?.optJSONObject("info")
                ?: continue
            val amount = info.optJSONObject("tokenAmount")?.optString("amount")?.toLongOrNull() ?: continue
            total += amount
            seen = true
        }
        return if (seen) total else 0L
    }

    private data class TokenBalance(val mint: String, val amount: Long)

    private fun tokenBalances(array: JSONArray?, owner: String): Map<Int, TokenBalance> {
        if (array == null) return emptyMap()
        val out = HashMap<Int, TokenBalance>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            if (entry.optString("owner") != owner) continue
            val amount = entry.optJSONObject("uiTokenAmount")?.optString("amount")?.toLongOrNull() ?: continue
            out[entry.optInt("accountIndex")] = TokenBalance(entry.optString("mint"), amount)
        }
        return out
    }

    private fun post(body: JSONObject): JSONObject? = runCatching {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode != 200) return null
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
        JSONObject(text)
    }.getOrNull()
}
