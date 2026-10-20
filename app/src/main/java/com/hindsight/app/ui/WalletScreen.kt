package com.hindsight.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.hindsight.app.PaperMoonApp
import com.hindsight.app.wallet.WalletUiException
import com.hindsight.app.wallet.WalletUiState
import kotlinx.coroutines.launch

/**
 * Native wallet screen: connect / disconnect through Mobile Wallet
 * Adapter against the Seeker's Seed Vault wallet. No web content —
 * this is what makes the app an app, not a wrapper.
 *
 * Every outcome produces visible feedback (T1-S02): state changes are
 * backed by snackbar confirmations for connect and disconnect, in
 * plain copy — never raw SDK text (T1-S08).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val sender = remember { ActivityResultSender(activity) }
    val walletManager = (context.applicationContext as PaperMoonApp).walletManager
    val state by walletManager.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun doConnect() {
        scope.launch {
            walletManager.connect(sender).fold(
                onSuccess = {
                    snackbarHostState.showSnackbar("Wallet connected.")
                },
                onFailure = { e ->
                    // Snackbar text is a plain constant by failure class:
                    // WalletManager seals every failure at the Result
                    // boundary as a WalletUiException whose message is
                    // user-facing copy (T1-S08) — raw SDK text can never
                    // reach this call. Anything unexpected falls back to
                    // the generic failure constant, not e.message.
                    snackbarHostState.showSnackbar(
                        (e as? WalletUiException)?.userMessage
                            ?: "Wallet connection failed. Please try again."
                    )
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WALLET", letterSpacing = 3.sp) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                WalletUiState.Disconnected -> {
                    Text("Not connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { doConnect() },
                        modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
                    ) { Text("CONNECT WALLET", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Opens your Seeker wallet to approve the connection.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                WalletUiState.Working -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(12.dp))
                    Text("Waiting for wallet…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is WalletUiState.Connected -> {
                    Text(
                        "CONNECTED",
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    // T1-S30: the address is selectable so the user can
                    // copy it out of the app.
                    SelectionContainer {
                        Text(
                            s.publicKeyBase58,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val revoked = walletManager.disconnect(sender)
                                snackbarHostState.showSnackbar(
                                    if (revoked) "Wallet disconnected."
                                    else "Couldn't disconnect — the wallet session is still active."
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
                    ) { Text("DISCONNECT") }
                }
                is WalletUiState.Error -> {
                    Text("Error", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { doConnect() },
                        modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
                    ) { Text("TRY AGAIN", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
