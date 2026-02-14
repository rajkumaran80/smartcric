package com.example.smartcric

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

data class StreamEntry(val label: String, val url: String)

sealed class Screen {
    object Loading : Screen()
    data class Ready(val streams: List<StreamEntry>) : Screen()
    data class Player(val streamUrl: String) : Screen()
    data class Error(val message: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Loading) }

            var lastStreams by remember { mutableStateOf<List<StreamEntry>>(emptyList()) }

            BackHandler(enabled = currentScreen !is Screen.Loading) {
                currentScreen = when (currentScreen) {
                    is Screen.Player -> Screen.Ready(lastStreams)
                    is Screen.Ready -> Screen.Loading
                    is Screen.Error -> Screen.Loading
                    else -> Screen.Loading
                }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D1117)) {
                    when (val screen = currentScreen) {
                        is Screen.Loading -> LoadingScreen(
                            onStreamsFound = { streams ->
                                lastStreams = streams
                                currentScreen = Screen.Ready(streams)
                            },
                            onError = { currentScreen = Screen.Error(it) }
                        )
                        is Screen.Ready -> {
                            var castTargetUrl by remember { mutableStateOf<String?>(null) }

                            ReadyScreen(
                                streams = screen.streams,
                                onPlay = { url -> currentScreen = Screen.Player(url) },
                                onRefresh = { currentScreen = Screen.Loading },
                                onSendToTv = { url -> castTargetUrl = url }
                            )

                            castTargetUrl?.let { url ->
                                TvDiscoveryDialog(
                                    streamUrl = url,
                                    onDismiss = { castTargetUrl = null }
                                )
                            }
                        }
                        is Screen.Player -> {
                            LaunchedEffect(Unit) {
                                WindowCompat.setDecorFitsSystemWindows(window, false)
                                val controller = WindowInsetsControllerCompat(window, window.decorView)
                                controller.hide(WindowInsetsCompat.Type.systemBars())
                                controller.systemBarsBehavior =
                                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                            DisposableEffect(Unit) {
                                onDispose {
                                    WindowCompat.setDecorFitsSystemWindows(window, true)
                                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                                    controller.show(WindowInsetsCompat.Type.systemBars())
                                }
                            }
                            PlayerScreen(screen.streamUrl)
                        }
                        is Screen.Error -> ErrorScreen(
                            message = screen.message,
                            onRetry = { currentScreen = Screen.Loading }
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoadingScreen(onStreamsFound: (List<StreamEntry>) -> Unit, onError: (String) -> Unit) {
    var statusText by remember { mutableStateOf("Finding streams...") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var alreadyHandled by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.size(0.dp),
        factory = { context ->
            WebView(context).apply {
                visibility = android.view.View.GONE
                layoutParams = ViewGroup.LayoutParams(0, 0)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                val startUrl = "https://smartcric.co.uk/live/"
                val maxLoadRetries = 5
                var loadRetry = 0
                var pageLoaded = false

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (alreadyHandled) return
                        pageLoaded = true
                        statusText = "Page loaded, scanning for streams..."

                        // Click the Star Sports tab if available
                        evaluateJavascript(
                            """
                            (function() {
                                var tabLink = document.querySelector('a[href="#tab-star"]');
                                if (tabLink) tabLink.click();
                            })();
                            """.trimIndent(), null
                        )

                        val maxAttempts = 3
                        val intervalMs = 60_000L
                        var attempt = 0

                        val scanJs = """
                            (function() {
                                var results = [];
                                var ids = ['flowplayer-willow', 'flowplayer-bbl', 'flowplayer-astro'];
                                for (var i = 0; i < ids.length; i++) {
                                    var iframe = document.getElementById(ids[i]);
                                    if (iframe) {
                                        var src = iframe.src || iframe.getAttribute('src') || '';
                                        if (src.indexOf('http') === 0) {
                                            results.push(ids[i] + '||' + src);
                                        }
                                    }
                                }
                                var allIframes = document.querySelectorAll('iframe[id^="flowplayer-"]');
                                for (var j = 0; j < allIframes.length; j++) {
                                    var id = allIframes[j].id;
                                    var alreadyAdded = false;
                                    for (var k = 0; k < results.length; k++) {
                                        if (results[k].indexOf(id + '||') === 0) { alreadyAdded = true; break; }
                                    }
                                    if (!alreadyAdded) {
                                        var src = allIframes[j].src || allIframes[j].getAttribute('src') || '';
                                        if (src.indexOf('http') === 0) {
                                            results.push(id + '||' + src);
                                        }
                                    }
                                }
                                return results.join('@@');
                            })();
                        """.trimIndent()

                        fun pollForStreams() {
                            if (alreadyHandled) return
                            attempt++
                            val remaining = (maxAttempts - attempt) * 60
                            statusText = "Scanning for streams... (attempt $attempt/$maxAttempts, ${remaining}s left)"

                            evaluateJavascript(scanJs) { result ->
                                if (alreadyHandled) return@evaluateJavascript
                                val raw = result.trim('"').replace("\\\"", "\"").trim()
                                val streams = if (raw.isNotEmpty()) {
                                    raw.split("@@").mapIndexedNotNull { index, entry ->
                                        val parts = entry.split("||", limit = 2)
                                        if (parts.size == 2 && parts[1].startsWith("http")) {
                                            StreamEntry("Player ${index + 1}", parts[1])
                                        } else null
                                    }
                                } else emptyList()

                                if (streams.isNotEmpty()) {
                                    alreadyHandled = true
                                    onStreamsFound(streams)
                                } else if (attempt >= maxAttempts) {
                                    alreadyHandled = true
                                    onError("No streams found. Tap Retry.")
                                } else {
                                    postDelayed({ pollForStreams() }, intervalMs)
                                }
                            }
                        }

                        // First attempt after 5s, then every 60s
                        postDelayed({ pollForStreams() }, 5000)
                    }

                    override fun onReceivedError(
                        view: WebView?, errorCode: Int,
                        description: String?, failingUrl: String?
                    ) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        if (alreadyHandled || pageLoaded) return
                        loadRetry++
                        if (loadRetry <= maxLoadRetries) {
                            statusText = "Connection slow, retrying... ($loadRetry/$maxLoadRetries)"
                            postDelayed({ loadUrl(startUrl) }, 15_000)
                        } else {
                            alreadyHandled = true
                            onError("Failed to load after $maxLoadRetries retries. Check your connection.")
                        }
                    }
                }

                loadUrl(startUrl)
                webViewRef = this
            }
        }
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SmartCric", color = Color(0xFF4CAF50), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Live Cricket Streaming", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
        Spacer(modifier = Modifier.height(48.dp))
        CircularProgressIndicator(color = Color(0xFF4CAF50))
        Spacer(modifier = Modifier.height(16.dp))
        Text(statusText, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
    }

    DisposableEffect(Unit) {
        onDispose { webViewRef?.destroy() }
    }
}

@Composable
fun ReadyScreen(
    streams: List<StreamEntry>,
    onPlay: (String) -> Unit,
    onRefresh: () -> Unit,
    onSendToTv: (String) -> Unit
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SmartCric", color = Color(0xFF4CAF50), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Live Cricket Streaming", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
        Spacer(modifier = Modifier.height(48.dp))
        Text("${streams.size} stream(s) found", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
        Spacer(modifier = Modifier.height(32.dp))

        streams.forEachIndexed { index, stream ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvButton(
                    label = "▶  ${stream.label}",
                    modifier = Modifier
                        .width(180.dp)
                        .height(56.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    onClick = { onPlay(stream.url) }
                )
                TvButton(
                    label = "TV",
                    modifier = Modifier.width(64.dp).height(56.dp),
                    isSecondary = true,
                    onClick = { onSendToTv(stream.url) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TvButton(
            label = "Refresh",
            modifier = Modifier.width(140.dp),
            isSecondary = true,
            onClick = onRefresh
        )
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { retryFocus.requestFocus() }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SmartCric", color = Color(0xFF4CAF50), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(48.dp))
        Text(message, color = Color(0xFFFF5252), fontSize = 16.sp)
        Spacer(modifier = Modifier.height(24.dp))

        TvButton(
            label = "Retry",
            modifier = Modifier.focusRequester(retryFocus),
            onClick = onRetry
        )
    }
}

@Composable
fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    isSecondary: Boolean = false,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1.0f, label = "btnScale")

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isFocused -> Color(0xFF4CAF50)
                isSecondary -> Color.Transparent
                else -> Color(0xFF4CAF50)
            },
            contentColor = when {
                isFocused -> Color.White
                isSecondary -> Color.White.copy(alpha = 0.5f)
                else -> Color.White
            }
        ),
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .scale(scale)
            .focusable()
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun TvDiscoveryDialog(streamUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val castState by SmartTvManager.stateFlow.collectAsState()

    LaunchedEffect(Unit) {
        SmartTvManager.discoverTvs(context, scope)
    }

    val dismiss = {
        SmartTvManager.reset()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Color(0xFF1E2D40),
        title = {
            Text("Send to TV", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            when (val state = castState) {
                is CastState.Idle, is CastState.Discovering -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Searching for Smart TVs...", color = Color.White)
                    }
                }
                is CastState.Found -> {
                    if (state.tvs.isEmpty()) {
                        Text(
                            "No Smart TVs found on your WiFi network.",
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    } else {
                        Column {
                            Text(
                                "Select your TV:",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            state.tvs.forEach { tv ->
                                TvButton(
                                    label = tv.name,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    onClick = {
                                        SmartTvManager.connectAndCast(tv, streamUrl, context, scope)
                                    }
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
                is CastState.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Connecting to ${state.tv.name}...", color = Color.White)
                    }
                }
                is CastState.WaitingForPairing -> {
                    Column {
                        Text(
                            "Accept the pairing request on your TV",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "A dialog should appear on ${state.tv.name}. Select 'Allow' to continue.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                }
                is CastState.Launching -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Opening stream on ${state.tv.name}...", color = Color.White)
                    }
                }
                is CastState.Success -> {
                    Text(
                        "Stream sent! Check your TV.",
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }
                is CastState.Failure -> {
                    Text("Failed: ${state.reason}", color = Color(0xFFFF5252))
                }
            }
        },
        confirmButton = {
            when (castState) {
                is CastState.Found, is CastState.Failure -> {
                    TextButton(onClick = { SmartTvManager.discoverTvs(context, scope) }) {
                        Text("Retry", color = Color(0xFF4CAF50))
                    }
                }
                is CastState.Success -> {
                    TextButton(onClick = dismiss) {
                        Text("Done", color = Color(0xFF4CAF50))
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.5f))
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerScreen(streamUrl: String) {
    var isLoading by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .focusable(),
            factory = { context ->
                WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowContentAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                isFocusable = true
                isFocusableInTouchMode = true

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ): Boolean = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isLoading = false

                        val autoPlayScript = """
                            (function() {
                                var videos = document.querySelectorAll('video');
                                for (var i = 0; i < videos.length; i++) {
                                    videos[i].muted = true;
                                    videos[i].play().then(function() {
                                        setTimeout(function() {
                                            var vids = document.querySelectorAll('video');
                                            for (var j = 0; j < vids.length; j++) { vids[j].muted = false; }
                                        }, 1000);
                                    }).catch(function(){});
                                }

                                var selectors = [
                                    '.plyr__control--overlaid', '.plyr__play-large',
                                    'button.plyr__control', '.fp-play', '.fp-play-rounded-fill',
                                    '#play-pause-button', '[data-plyr="play"]',
                                    '.vjs-big-play-button', '.play-button',
                                    'button[aria-label="Play"]', '.ytp-large-play-button'
                                ];
                                for (var s = 0; s < selectors.length; s++) {
                                    var btn = document.querySelector(selectors[s]);
                                    if (btn) btn.click();
                                }
                                if (typeof player !== 'undefined' && player.play) player.play();

                                var userClicked = false;
                                document.addEventListener('click', () => {
                                    userClicked = true;
                                    setTimeout(() => { userClicked = false; }, 3000);
                                });

                                var loadCounter = 0;
                                var itv = setInterval(() => {
                                    loadCounter++;
                                    var v = document.querySelector('video');
                                    if (v && v.paused && !userClicked && loadCounter < 15) {
                                        v.muted = false;
                                        v.play().catch(()=>{});
                                    }
                                    if (loadCounter < 8) {
                                        for (var s = 0; s < selectors.length; s++) {
                                            var btn = document.querySelector(selectors[s]);
                                            if (btn) btn.click();
                                        }
                                    }
                                }, 1500);
                                setTimeout(() => clearInterval(itv), 25000);
                            })();
                        """.trimIndent()

                        evaluateJavascript(autoPlayScript, null)

                        // Simulate touch to trigger play (works on TV boxes)
                        postDelayed({
                            val x = width / 2f
                            val y = height / 2f
                            val t = SystemClock.uptimeMillis()
                            dispatchTouchEvent(
                                MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
                            )
                            dispatchTouchEvent(
                                MotionEvent.obtain(
                                    SystemClock.uptimeMillis(),
                                    SystemClock.uptimeMillis(),
                                    MotionEvent.ACTION_UP, x, y, 0
                                )
                            )
                        }, 4000)
                    }
                }

                webChromeClient = WebChromeClient()

                // D-pad center / Enter key simulates touch (for TV remote)
                setOnKeyListener { v, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            val x = v.width / 2f
                            val y = v.height / 2f
                            val time = SystemClock.uptimeMillis()
                            dispatchTouchEvent(
                                MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
                            )
                            dispatchTouchEvent(
                                MotionEvent.obtain(
                                    SystemClock.uptimeMillis(),
                                    SystemClock.uptimeMillis(),
                                    MotionEvent.ACTION_UP, x, y, 0
                                )
                            )
                            return@setOnKeyListener true
                        }
                    }
                    false
                }

                loadUrl(streamUrl)
            }
        }
        )

        // Loading overlay - covers WebView until page finishes
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D1117)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SmartCric", color = Color(0xFF4CAF50), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator(color = Color(0xFF4CAF50))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading player...", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                }
            }
        }
    }
}
