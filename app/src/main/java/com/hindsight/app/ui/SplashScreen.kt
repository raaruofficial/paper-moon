package com.hindsight.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Splash: the brand moment, then hands off to home.
 *
 * T1-S12: there is deliberately NO fixed delay here — the old
 * unconditional 1.2 s timer gated nothing (no readiness work exists
 * for it to wait on) and taxed every cold start. The splash dismisses
 * as soon as it composes; if real loading work ever lands, gate the
 * handoff on that work, not on a timer.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        onDone()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "PAPER MOON",
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 6.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "PAPER MONEY. REAL CHARTS. THE MOON YOU MISSED.",
            fontSize = 14.sp,
            letterSpacing = 3.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
