package com.hindsight.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hindsight.app.BuildConfig
import com.hindsight.app.PaperMoonApp
import com.hindsight.app.R
import com.hindsight.app.wallet.WalletUiState

/** Native main menu. The arcade cabinet front panel. */
@Composable
fun HomeScreen(navController: NavController) {
    val app = LocalContext.current.applicationContext as PaperMoonApp
    val walletState by app.requireWalletGateway().uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Title block (owner, 2026-10-27): the Paper Moon emblem —
        // the launcher/store icon of record's own artwork (the
        // adaptive icon's foreground resource, referenced directly
        // so it can never drift from the shipped icon) — above the
        // wordmark, with the slogan directly under the block. The
        // slogan appears exactly once on this screen (the Text
        // below); it is not repeated anywhere else in this layout.
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = "Paper Moon logo",
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(24.dp))
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "PAPER MOON",
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 5.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Paper money. Real charts. The moon you missed.",
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "3 rookie rides free per wallet · day pass ${com.hindsight.app.money.MoneyConfig.priceUsdDisplay()} USDC / 24h",
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))

        MenuButton("▶  PLAY", onClick = { navController.navigate("game") })

        Spacer(Modifier.height(12.dp))
        val walletLabel = when (val s = walletState) {
            is WalletUiState.Connected ->
                "WALLET · ${s.publicKeyBase58.take(4)}…${s.publicKeyBase58.takeLast(4)}"
            WalletUiState.Working -> "WALLET · …"
            // T1-S15: an error must be visible from Home, not swallowed
            // into the same label as a plain disconnected state.
            is WalletUiState.Error -> "WALLET · ERROR — TAP TO RETRY"
            WalletUiState.Disconnected -> "WALLET"
        }
        MenuButton(walletLabel, onClick = { navController.navigate("wallet") }, outlined = true)

        Spacer(Modifier.height(12.dp))
        MenuButton("SETTINGS", onClick = { navController.navigate("settings") }, outlined = true)

        Spacer(Modifier.height(40.dp))
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit, outlined: Boolean = false) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(0.8f).height(56.dp)
        ) { Text(text, letterSpacing = 2.sp) }
    } else {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(0.8f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) { Text(text, letterSpacing = 2.sp, fontWeight = FontWeight.Bold) }
    }
}
