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
) : ChainReader, BlockhashProvider {

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

    override fun recentBlockhash(): ByteArray? {
        val response = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "getLatestBlockhash")
                .put("params", JSONArray().put(JSONObject().put("commitment", SettlementVerifier.COMMITMENT)))
        ) ?: return null
        val value = response.optJSONObject("result")?.optJSONObject("value") ?: return null
        val base58 = value.optString("blockhash").takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Base58.decode(base58) }.getOrNull()
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
