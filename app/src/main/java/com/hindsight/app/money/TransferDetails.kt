package com.hindsight.app.money

/**
 * One observed token transfer with BOTH endpoints (Gate 0 C-21):
 * derived from a transaction's pre/post token-balance deltas for
 * every owner — a decrease of [amountBaseUnits] of [mint] at
 * [fromOwner] paired with the matching increase at [toOwner].
 *
 * The frozen [ObservedTransaction] deliberately carries only
 * recipient-side transfers (that is all settlement verification
 * needs). No-signature reconciliation needs the SOURCE too: a
 * candidate payment counts only when its source is the intent's
 * buyer — someone else's identical payment to the same recipient
 * never settles this buyer's intent.
 */
data class ObservedTransferDetail(
    val fromOwner: String,
    val toOwner: String,
    val mint: String,
    val amountBaseUnits: Long
)
