package mano.algo.caster.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"

private data class DetectedMedia(val url: String, val mimeType: String)

/**
 * In-app browser that sniffs playable media from page traffic.
 *
 * @param onMediaDetected called on the main thread for each new media URL found on the current page.
 * @param onMediaSelected called when the user picks a detected item from the list.
 */
@Composable
fun CastingBrowserScreen(
    modifier: Modifier = Modifier,
    initialUrl: String = "https://www.google.com",
    onMediaDetected: (url: String, mimeType: String) -> Unit = { _, _ -> },
    onMediaSelected: (url: String, mimeType: String) -> Unit = { _, _ -> },
) {
    val currentOnDetected by rememberUpdatedState(onMediaDetected)
    val detected = remember { mutableStateListOf<DetectedMedia>() }
    var addressText by rememberSaveable { mutableStateOf(initialUrl) }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // The listener is delivered on the main thread (see the client), so mutating Compose state here is safe.
    val client = remember {
        MediaInterceptingWebViewClient(
            listener = { url, mime ->
                if (detected.none { it.url == url }) detected.add(DetectedMedia(url, mime))
                currentOnDetected(url, mime)
            },
            onNavigationStarted = { detected.clear() },
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { webView?.loadUrl(normalizeInput(addressText)) }),
            )
            Button(
                onClick = { menuOpen = true },
                enabled = detected.isNotEmpty(),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Media (${detected.size})")
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    detected.forEach { item ->
                        DropdownMenuItem(
                            text = { Text("${item.mimeType.substringAfter('/')} · ${item.url.take(60)}", maxLines = 2) },
                            onClick = {
                                menuOpen = false
                                onMediaSelected(item.url, item.mimeType)
                            },
                        )
                    }
                }
            }
        }

        if (progress < 100) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    configureForCasting()
                    webViewClient = client
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                            canGoBack = view.canGoBack()
                            if (newProgress == 100) view.url?.let { addressText = it }
                        }
                    }
                    loadUrl(normalizeInput(initialUrl))
                    webView = this
                }
            },
            onRelease = { view ->
                webView = null
                view.stopLoading()
                view.destroy()
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForCasting() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        userAgentString = MOBILE_USER_AGENT
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        // This browser never needs local files or other apps' content providers.
        allowFileAccess = false
        allowContentAccess = false
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/** Turns address-bar text into a loadable URL, falling back to a web search for non-URL input. */
private fun normalizeInput(raw: String): String {
    val text = raw.trim()
    return when {
        text.startsWith("http://", true) || text.startsWith("https://", true) -> text
        text.contains(' ') || !text.contains('.') -> "https://www.google.com/search?q=${Uri.encode(text)}"
        else -> "https://$text"
    }
}
