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
        val meta = response.optJSONObject("result")?.optJSONObject("meta") ?: return null
        if (!meta.isNull("err")) return emptyList() // failed tx: no payment happened
        // Per-(owner, mint) deltas across EVERY token balance —
        // unlike fetchTransaction (recipient-only, all settlement
        // needs), reconciliation must see the source account too.
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
        val negatives = ArrayList<Triple<String, String, Long>>() // owner, mint, amount>0
        val positives = ArrayList<Triple<String, String, Long>>()
        for (key in pre.keys + post.keys) {
            val delta = (post[key] ?: 0L) - (pre[key] ?: 0L)
            when {
                delta < 0 -> negatives.add(Triple(key.first, key.second, -delta))
                delta > 0 -> positives.add(Triple(key.first, key.second, delta))
            }
        }
        val details = ArrayList<ObservedTransferDetail>()
        val consumed = BooleanArray(negatives.size)
        for ((toOwner, mint, amount) in positives) {
            val idx = negatives.indices.firstOrNull { i ->
                !consumed[i] && negatives[i].second == mint && negatives[i].third == amount
            } ?: continue
            consumed[idx] = true
            details.add(ObservedTransferDetail(negatives[idx].first, toOwner, mint, amount))
        }
        return details
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
