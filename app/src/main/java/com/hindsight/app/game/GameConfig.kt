package com.hindsight.app.game

/**
 * How the game content reaches the APK.
 *
 * DECIDED: BUNDLED_ASSET. The game is a static export of HINDSIGHT
 * ("Hindsight Seeker" artifact), exported to a single HTML file and placed at
 * app/src/main/assets/game/index.html. Fully offline-capable: all 35 scenarios'
 * candles, catalysts, newspaper stories, and art are bundled in the file.
 *
 * The [HindsightWallet] JS bridge is the seam where the native MWA wallet
 * meets the game. The game detects `window.HindsightWallet` and uses it for
 * wallet identity when present; otherwise it runs fully standalone with its
 * simulated arcade wallet. All money stays simulated until the production
 * payments cutover.
 */
object GameConfig {
    /** Bundled static game. */
    const val GAME_URL: String = "file:///android_asset/game/index.html"

    /** Name the game uses to reach the native wallet: `window.HindsightWallet`. */
    const val BRIDGE_NAME: String = "HindsightWallet"
}
