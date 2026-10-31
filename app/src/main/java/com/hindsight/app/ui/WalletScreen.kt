package com.hindsight.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hindsight.app.PaperMoonApp
import com.hindsight.app.wallet.ConnectWalletGateway
import com.hindsight.app.wallet.WalletUiException
import com.hindsight.app.wallet.WalletUiState
import com.solanamobile.seekerconnect.WalletAvailability
import com.solanamobile.seekerconnect.compose.SeekerConnectButton
import kotlinx.coroutines.launch

/**
 * Native wallet screen — connect / disconnect the game wallet.
 *
 * v0.2.10 (Seeker Connect migration, Gate 0 C-26): the connect
 * control follows the probed availability — the branded Connect
 * button when a certified wallet is present (CERTIFIED), the same
 * plain CONNECT WALLET label otherwise (OTHER), and a
 * rides-first explanation when no wallet exists (NONE leads with
 * what the user CAN do). Availability is probed exactly once,
 * here, when this surface opens (C-12): never at startup, never
 * logged, never bridged, never persisted.
 *
 * The gateway is constructed in MainActivity before setContent
 * (C-17), so this screen can never reach an uninitialized wallet
 * collaborator — the v0.2.5 crash class stays closed by
 * construction.
 */
@Composable
fun WalletScreen(navController: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperMoonApp
    val gateway = remember { app.requireWalletGateway() }
    val state by gateway.uiState.collectAsState()
    val migrationNotice by gateway.migrationNotice.collectAsState()
    // C-12: the single availability probe, at surface-open.
    val availability = remember { gateway.walletAvailability() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var boundIdentity by remember { mutableStateOf(app.shellVerifier.boundIdentity) }
    var residueNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state) { boundIdentity = app.shellVerifier.boundIdentity }

    fun doConnect() {
        scope.launch {
            gateway.connect().fold(
                onSuccess = {
                    residueNote = null
                    if (migrationNotice) gateway.dismissMigrationNotice()
                    snackbar.showSnackbar("Wallet connected")
                },
                onFailure = { e ->
                    // The gateway seals every failure at its
                    // boundary (C-14): userMessage is plain copy,
                    // never a raw SDK or wallet string.
                    snackbar.showSnackbar(
                        (e as? WalletUiException)?.userMessage
                            ?: ConnectWalletGateway.CONNECT_FAILED_MESSAGE
                    )
                }
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "WALLET",
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 4.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Paper Moon uses your wallet for sign-in and purchases.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        when (val s = state) {
            WalletUiState.Working -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Waiting for wallet…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is WalletUiState.Connected -> {
                Text(
                    text = "CONNECTED",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = s.publicKeyBase58,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                // C-26b: connected is not bound — say why the one
                // signature exists, in plain words, where the user
                // can see the state.
                Text(
                    text = if (boundIdentity != null) {
                        "Signed in — purchases and claims use this identity."
                    } else {
                        "Connected — your wallet remembers Paper Moon. " +
                            "One quick signature proves it's you when you buy or claim."
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val outcome = gateway.disconnect()
                            if (outcome.deauthorizeSucceeded) {
                                snackbar.showSnackbar("Wallet disconnected")
                            } else {
                                // C-26c: the failed-deauthorize
                                // residue is disclosed, never
                                // silently stranded.
                                residueNote =
                                    "Paper Moon has forgotten this wallet on this device. " +
                                        "If your wallet still lists Paper Moon as connected, " +
                                        "you can remove it there too."
                                snackbar.showSnackbar("Disconnected on this device")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
                ) { Text("DISCONNECT", letterSpacing = 2.sp) }
            }
            is WalletUiState.Error -> {
                Text(
                    text = "CONNECTION ERROR",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = s.message,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { doConnect() },
                    modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
                ) { Text("TRY AGAIN", letterSpacing = 2.sp) }
            }
            WalletUiState.Disconnected -> {
                if (migrationNotice) {
                    // C-26a: the migration prompt — point-of-need
                    // only (this surface), never a startup modal.
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "Reconnect your wallet once to continue",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Your passes and rides are unchanged.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { doConnect() },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("Reconnect wallet") }
                            TextButton(
                                onClick = { gateway.dismissMigrationNotice() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Not now") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                when (availability) {
                    WalletAvailability.CERTIFIED -> {
                        // C-26d: the branded button — the one
                        // place branding carries weight (UMBRA S-9).
                        // Rendered in its dark form so it reads
                        // identically in both system themes (C-27).
                        SeekerConnectButton(
                            onClick = { doConnect() },
                            modifier = Modifier.fillMaxWidth(0.8f).height(52.dp),
                            darkTheme = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = ConnectSubcopy,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    WalletAvailability.OTHER -> {
                        // C-26d: the SAME plain label — no second
                        // class of user.
                        Button(
                            onClick = { doConnect() },
                            modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
                        ) { Text("CONNECT WALLET", letterSpacing = 2.sp) }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = ConnectSubcopy,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    WalletAvailability.NONE -> {
                        // C-26d: leads with what the user CAN do.
                        Text(
                            text = "You can play right now — your first rides are free, no wallet needed.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Add an MWA wallet (for example the Seeker Wallet) any time to buy a day pass.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        residueNote?.let { note ->
            Spacer(Modifier.height(20.dp))
            Text(
                text = note,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
        ) { Text("← BACK", letterSpacing = 2.sp) }
        Spacer(Modifier.height(8.dp))
        SnackbarHost(snackbar)
    }
}

/** C-26e: the connect moment in plain words — the consent copy is
 *  honest about what connecting is (VISIONARY §6). */
private const val ConnectSubcopy: String =
    "Opens your wallet to approve the connection. Paper Moon never sees your keys."
