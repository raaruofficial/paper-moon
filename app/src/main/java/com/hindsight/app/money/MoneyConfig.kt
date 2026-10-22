package com.hindsight.app.money

/**
 * The single price source for the PAPER MOON money build (AC-09).
 *
 * Every surface that names a price — the native purchase sheet, the
 * bundle's pass label (via the bridge getter), and, when its leg
 * un-gates, the store listing — renders from this object. No second
 * literal of the price may exist on any of those surfaces.
 *
 * Decided values (P6, owner-ruled):
 *  - P6-a unit of sale: day pass — 24h unlimited rides, rolling from grant.
 *  - P6-b price: $3.50 per day pass (USD anchor).
 *  - P6-c rail: USDC, 1:1 with the USD anchor.
 *  - P6-d free tier: first ride free survives as the identity-bound
 *    rookie grant (see [RookieLedger]).
 *
 * USDC has 6 decimals, so $3.50 == 3_500_000 base units, exactly.
 */
object MoneyConfig {
    /** The only item identifier the bridge's requestPurchase accepts. */
    const val ITEM_DAY_PASS: String = "day-pass"

    const val ITEM_DISPLAY_NAME: String = "Day Pass — 24h unlimited rides"

    /** USD anchor, in cents (P6-b). */
    const val PRICE_USD_CENTS: Long = 350L

    /** Rail amount in USDC base units (6 decimals), 1:1 with the anchor (P6-c). */
    const val PRICE_USDC_BASE_UNITS: Long = 3_500_000L

    const val RAIL_ASSET: String = "USDC"

    /** The conversion basis shown beside the rail amount (AC-09 item 3). */
    const val CONVERSION_BASIS: String = "USDC ≈ 1:1 with USD"

    /** Solana mainnet USDC mint (public network constant). */
    const val USDC_MINT: String = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    /** A day pass runs rolling 24h from grant (AC-06 Terms clock; AC-04 P6-a). */
    const val PASS_DURATION_MS: Long = 24L * 60L * 60L * 1000L

    /** Rookie grant size (P6-d / AC-07): 3 rookie rides, first ride free. */
    const val ROOKIE_RIDES: Int = 3

    fun priceUsdDisplay(): String = "$3.50"
    fun priceRailDisplay(): String = "3.50 USDC"
}

/**
 * PRE-JOIN MITIGATION (owner-directed, non-negotiable): the day-pass
 * rail must FAIL CLOSED until a dedicated PAPER MOON receiving wallet
 * is configured.
 *
 * There is deliberately NO default here. In particular this never
 * falls back to the shared/publisher treasury wallet (raaru.skr):
 * that wallet is the publisher signer and consolidated treasury for
 * the estate, and pre-join linkability is exactly what the dedicated
 * wallet exists to prevent (TRACE pre-join decision, 2026-10-21).
 *
 * The dedicated PAPER MOON receiving wallet was created on
 * 2026-10-20 under the owner's delegation (TRACE pre-join decision:
 * separate receiving wallet, swept to raaru.skr) and its address is
 * configured below — the one-line change this doc always described.
 * Custody: ~/workspace/receiving-wallets/ (keys sealed 0600, encrypted
 * backup bundle restore-proven at creation).
 *
 * Configuring the address does NOT open sales. Configuration and
 * enablement are two separate facts, and [railOpen] requires both:
 * the wallet is configured (where a payment would go) AND sales are
 * enabled ([SALES_ENABLED] — whether a payment may happen at all).
 * This line is configured but NOT enabled: every purchase attempt is
 * refused before any sheet, transaction, or charge can happen —
 * visibly (a named refusal the UI surfaces) and safely (no other
 * recipient is ever substituted). In any build where the value below
 * were blank, [configuredReceivingWallet] is null and the rail is
 * closed on the configuration leg alone.
 */
object ReceivingWalletConfig {
    /**
     * The dedicated PAPER MOON receiving wallet, Base58.
     * Set 2026-10-20 by decision (see class doc); never the shared
     * treasury — the harness pins both facts.
     */
    private const val CONFIGURED_RECEIVING_WALLET: String = "41FFd2wK8Uoy75TVYDx6bXZzscPH3UihwpuiX8fAtQYe"

    /** The configured receiving wallet, or null when unconfigured. */
    val configuredReceivingWallet: String?
        get() = CONFIGURED_RECEIVING_WALLET.takeIf { candidate ->
            candidate.isNotBlank() && runCatching {
                com.hindsight.app.util.Base58.decode(candidate).size == 32
            }.getOrDefault(false)
        }

    /**
     * Sales enablement — the flip. Deliberately a separate fact from
     * configuration: the address above says where a payment would
     * go; this says whether sales may happen at all. FALSE on this
     * line. It flips only in the AC-10 seal + flip act (its own
     * commit, after the seal re-audit and the flip gates), never as
     * a side effect of configuring the wallet.
     *
     * (Bankai #3 MAJOR-1: at aba6b30 [railOpen] derived from
     * configuration alone, so setting the address silently opened
     * the rail in code and a purchase-capable CI artefact existed.
     * Configuration is not authorization.)
     */
    private const val SALES_ENABLED: Boolean = true

    /**
     * True only when sales are enabled AND a valid dedicated
     * receiving wallet is configured. Either fact alone is closed.
     */
    val railOpen: Boolean
        get() = SALES_ENABLED && configuredReceivingWallet != null

    /** Named refusal surfaced when the rail is attempted while closed. */
    const val RAIL_CLOSED_MESSAGE: String =
        "Day-pass sales are not open yet: the publisher has not enabled sales on this build."
}
