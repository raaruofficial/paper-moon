package com.hindsight.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hindsight.app.ui.GameScreen
import com.hindsight.app.ui.HomeScreen
import com.hindsight.app.ui.SettingsScreen
import com.hindsight.app.ui.SplashScreen
import com.hindsight.app.ui.WalletScreen
import com.hindsight.app.ui.theme.PaperMoonTheme
import com.hindsight.app.wallet.ConnectWalletGateway
import com.solanamobile.seekerconnect.compose.SeekerConnectDialogState
import com.solanamobile.seekerconnect.compose.SeekerConnectDialogs

class MainActivity : ComponentActivity() {

    /**
     * The one wallet gateway (Gate 0 C-17): constructed in
     * [onCreate] BEFORE [setContent] — the v0.2.5 crash class was
     * an uninitialized wallet collaborator reached from composition;
     * construction here cannot regress to it — and installed on
     * [PaperMoonApp] so an activity recreation within the process
     * reuses the same instance (exactly one SeekerConnect per
     * process). There is deliberately no app-owned
     * ActivityResultSender and no registerForActivityResult
     * anywhere in this app: result registration is the SDK's,
     * per call (v029 audit A3).
     */
    private lateinit var gateway: ConnectWalletGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as PaperMoonApp
        gateway = app.walletGateway ?: ConnectWalletGateway(
            context = this,
            store = app.secureStore,
            shellVerifier = app.shellVerifier,
            dialogState = SeekerConnectDialogState()
        ).also { app.walletGateway = it }
        enableEdgeToEdge()
        setContent {
            PaperMoonTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        NavHost(
                            navController = navController,
                            startDestination = "splash"
                        ) {
                            composable("splash") {
                                SplashScreen(
                                    onDone = {
                                        navController.navigate("home") {
                                            popUpTo("splash") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable("home") { HomeScreen(navController) }
                            composable("game") {
                                GameScreen(
                                    navController = navController,
                                    onExit = { navController.popBackStack() }
                                )
                            }
                            composable("wallet") { WalletScreen(navController) }
                            composable("settings") { SettingsScreen(navController) }
                        }
                        // C-18: the Connect dialogs mount at the
                        // single composition root, ABOVE both the
                        // native wallet surface and the game — an
                        // interaction started from either surface
                        // renders its progress/error here.
                        // darkTheme is pinned: the shell is
                        // dark-only, and the SDK dialog must render
                        // identically in both system themes (C-27).
                        SeekerConnectDialogs(gateway.dialogState, darkTheme = true)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // C-17 current-activity provider: weak reference, set here.
        if (::gateway.isInitialized) gateway.activityResumed(this)
    }

    override fun onPause() {
        // Cleared only when identity-equal (handled gateway-side),
        // so a predecessor's onPause cannot clobber a successor.
        if (::gateway.isInitialized) gateway.activityPaused(this)
        super.onPause()
    }
}
