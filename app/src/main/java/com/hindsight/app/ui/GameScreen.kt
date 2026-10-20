package com.hindsight.app.ui

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.hindsight.app.PaperMoonApp
import com.hindsight.app.game.GameConfig
import com.hindsight.app.wallet.PaperMoonWalletBridge

/**
 * The game screen. The *game* renders in a WebView, but everything around it
 * is native: navigation, wallet, menus, and lifecycle are all Android.
 *
 * The [PaperMoonWalletBridge] (JS name `window.PaperMoonWallet`) is the seam
 * where the native Mobile Wallet Adapter session meets the game — connect,
 * sign, and identity flow through it.
 *
 * The WebView is locked to the bundled game file (T1-S18): the
 * navigation allowlist admits `file:///android_asset/` URLs only, and
 * because the wallet bridge is injected into whatever page loads, no
 * other content may ever render here. Load failures and renderer
 * deaths surface a recoverable error screen with RETRY (T1-S17) —
 * never an unrecoverable blank.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GameScreen(navController: NavController, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val walletManager = remember { (context.applicationContext as PaperMoonApp).walletManager }
    val webView = remember { mutableStateOf<WebView?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var rendererGone by remember { mutableStateOf(false) }
    // Bumped to force a fresh WebView instance after a renderer death —
    // a WebView whose renderer process is gone can never be reused.
    var incarnation by remember { mutableIntStateOf(0) }

    // Back: step back through game history first, exit the screen at the root.
    BackHandler {
        val wv = webView.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onExit()
    }

    // Pause/resume/destroy the WebView with the Activity lifecycle so the game
    // loop never runs away in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView.value?.onPause()
                Lifecycle.Event.ON_RESUME -> webView.value?.onResume()
                Lifecycle.Event.ON_DESTROY -> webView.value?.destroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        key(incarnation) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            // Allowlist: only the bundled game assets may
                            // ever load. Everything else — remote pages,
                            // data: URLs, intent schemes — is blocked and
                            // ignored, so the injected wallet bridge can
                            // never be inherited by foreign content.
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return true
                                return !url.startsWith(ALLOWED_URL_PREFIX)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    loadError = true
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                rendererGone = true
                                loadError = true
                                return true
                            }
                        }
                        val bridge = PaperMoonWalletBridge(
                            activity = activity,
                            walletManager = walletManager,
                            evaluateJs = { js -> post { evaluateJavascript(js, null) } }
                        )
                        addJavascriptInterface(bridge, GameConfig.BRIDGE_NAME)
                        loadUrl(GameConfig.GAME_URL)
                        webView.value = this
                    }
                }
            )
        }

        if (loadError) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "THE GAME COULDN'T LOAD",
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Something interrupted the arcade. Your session restarts fresh — nothing was charged.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        loadError = false
                        if (rendererGone) {
                            rendererGone = false
                            incarnation++
                        } else {
                            webView.value?.reload()
                        }
                    }
                ) { Text("RETRY") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onExit) { Text("BACK TO MENU") }
            }
        }
    }
}

private const val ALLOWED_URL_PREFIX = "file:///android_asset/"
