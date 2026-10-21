package com.hindsight.app.money

import com.hindsight.app.util.Base58
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Supplies a recent blockhash. Production: Solana JSON-RPC. */
interface BlockhashProvider {
    /** 32-byte blockhash, or null when the RPC is unreachable. */
    fun recentBlockhash(): ByteArray?
}

/**
 * Shell-side-only transaction construction (AC-03 item 3, AC-04).
 *
 * The ONLY purchase transaction this builder can produce is: an SPL
 * token TransferChecked of exactly [MoneyConfig.PRICE_USDC_BASE_UNITS]
 * of the USDC mint, from the buyer's associated token account to the
 * configured dedicated receiving wallet's associated token account,
 * preceded by an idempotent create for the recipient ATA.
 *
 * Structural guarantee: the builder's inputs are the buyer's public
 * key and a blockhash — nothing else. There is NO parameter for
 * recipient, amount, mint, or memo anywhere in this API, and the
 * recipient is read from [ReceivingWalletConfig] at build time.
 * A page attempt to alter amount/recipient is therefore not merely
 * rejected — there is no parameter path by which it could be
 * expressed (the AC-03 PASS condition).
 *
 * While the rail is unconfigured this builder refuses to build
 * (returns null): fail closed, again.
 */
object TransactionBuilder {
    private const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private const val ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

    /**
     * Build the serialized (unsigned, signature-placeholders present)
     * legacy transaction for the day-pass purchase, or null when the
     * rail is closed or the blockhash is unavailable.
     */
    fun buildDayPassPurchase(
        buyerPublicKeyBase58: String,
        blockhashProvider: BlockhashProvider,
        receivingWalletProvider: () -> String? =
            { ReceivingWalletConfig.configuredReceivingWallet }
    ): ByteArray? {
        val recipient = receivingWalletProvider() ?: return null
        val blockhash = blockhashProvider.recentBlockhash() ?: return null
        if (blockhash.size != 32) return null
        val buyer = runCatching { Base58.decode(buyerPublicKeyBase58) }.getOrNull() ?: return null
        val recipientBytes = runCatching { Base58.decode(recipient) }.getOrNull() ?: return null
        val mint = Base58.decode(MoneyConfig.USDC_MINT)
        val tokenProgram = Base58.decode(TOKEN_PROGRAM)
        val ataProgram = Base58.decode(ATA_PROGRAM)
        val systemProgram = Base58.decode(SYSTEM_PROGRAM)

        val buyerAta = associatedTokenAddress(buyer, mint) ?: return null
        val recipientAta = associatedTokenAddress(recipientBytes, mint) ?: return null

        // Account keys, Solana ordering: signers first (fee payer = buyer),
        // then writable, then read-only. Indices are computed from this list.
        val keys = listOf(
            buyer,          // 0 signer, writable (fee payer + transfer authority)
            buyerAta,       // 1 writable
            recipientAta,   // 2 writable
            recipientBytes, // 3 read-only (ATA owner for create)
            mint,           // 4 read-only
            systemProgram,  // 5 read-only
            tokenProgram,   // 6 read-only
            ataProgram      // 7 read-only (instruction program)
        )
        fun idx(bytes: ByteArray): Int = keys.indexOfFirst { it.contentEquals(bytes) }

        // Instruction 1: create recipient ATA, idempotent (data = [1]).
        val createAta = Instruction(
            programIndex = idx(ataProgram),
            accountIndices = listOf(
                idx(buyer), idx(recipientAta), idx(recipientBytes),
                idx(mint), idx(systemProgram), idx(tokenProgram)
            ),
            data = byteArrayOf(1)
        )
        // Instruction 2: TransferChecked, amount from the single price
        // source, decimals = 6 (USDC). data = [12] + u64 LE + [6].
        val amountLe = ByteArray(8)
        var remaining = MoneyConfig.PRICE_USDC_BASE_UNITS
        for (i in 0..7) {
            amountLe[i] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        val transfer = Instruction(
            programIndex = idx(tokenProgram),
            accountIndices = listOf(idx(buyerAta), idx(mint), idx(recipientAta), idx(buyer)),
            data = byteArrayOf(12) + amountLe + byteArrayOf(6)
        )

        val message = serializeMessage(
            header = byteArrayOf(1, 0, 5), // 1 signer, 0 readonly signers, 5 readonly non-signers
            accountKeys = keys,
            blockhash = blockhash,
            instructions = listOf(createAta, transfer)
        )
        // Serialized transaction: signature count + placeholders + message.
        val out = ByteArrayOutputStream()
        out.write(shortVec(1))
        out.write(ByteArray(64))
        out.write(message)
        return out.toByteArray()
    }

    /** Associated token address derivation (PDA, off-curve by construction). */
    fun associatedTokenAddress(owner: ByteArray, mint: ByteArray): ByteArray? {
        val tokenProgram = Base58.decode(TOKEN_PROGRAM)
        val ataProgram = Base58.decode(ATA_PROGRAM)
        return findProgramAddress(listOf(owner, tokenProgram, mint), ataProgram)
    }

    fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): ByteArray? {
        val marker = "ProgramDerivedAddress".toByteArray(Charsets.UTF_8)
        for (bump in 255 downTo 0) {
            val hashInput = ByteArrayOutputStream()
            for (seed in seeds) hashInput.write(seed)
            hashInput.write(bump)
            hashInput.write(programId)
            hashInput.write(marker)
            val hash = MessageDigest.getInstance("SHA-256").digest(hashInput.toByteArray())
            if (!Ed25519.isOnCurve(hash)) return hash
        }
        return null
    }

    private data class Instruction(
        val programIndex: Int,
        val accountIndices: List<Int>,
        val data: ByteArray
    )

    private fun serializeMessage(
        header: ByteArray,
        accountKeys: List<ByteArray>,
        blockhash: ByteArray,
        instructions: List<Instruction>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(shortVec(accountKeys.size))
        for (key in accountKeys) out.write(key)
        out.write(blockhash)
        out.write(shortVec(instructions.size))
        for (ix in instructions) {
            out.write(ix.programIndex)
            out.write(shortVec(ix.accountIndices.size))
            for (account in ix.accountIndices) out.write(account)
            out.write(shortVec(ix.data.size))
            out.write(ix.data)
        }
        return out.toByteArray()
    }

    private fun shortVec(value: Int): ByteArray {
        var v = value
        val bytes = ArrayList<Byte>()
        while (true) {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            bytes.add(b.toByte())
            if (v == 0) break
        }
        return ByteArray(bytes.size) { bytes[it] }
    }
}
