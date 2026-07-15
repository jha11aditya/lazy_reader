package com.lazyreader.ui.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Reflowable EPUB reader: each spine chapter is loaded into a WebView and
 * split into screen-sized "pages" with CSS multi-columns; page turns slide
 * the body with translateX. Voice "go"/"backward" turn pages and roll over
 * across chapter boundaries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    documentUri: String,
    displayName: String,
    startChapter: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: EpubReaderViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(documentUri) {
        viewModel.open(Uri.parse(documentUri), startChapter)
    }

    val view = LocalView.current
    DisposableEffect(uiState.locked) {
        view.keepScreenOn = !uiState.locked
        onDispose { view.keepScreenOn = false }
    }

    var pageInChapter by remember { mutableIntStateOf(0) }
    var pagesInChapter by remember { mutableIntStateOf(1) }

    val context = LocalContext.current
    val webView = remember {
        @SuppressLint("SetJavaScriptEnabled")
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            // true so the injected viewport meta (see onPageFinished) is honored:
            // EPUB chapters ship without one, and the fallback desktop-width
            // layout (~1216 CSS px here) gets scale-fitted — text misframed.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            // TEXT_AUTOSIZING (the default) inflates fonts after layout and
            // breaks the fixed column pagination math.
            settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
            settings.textZoom = 100
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
    }

    fun showPage(page: Int) {
        pageInChapter = page
        // clientWidth, not innerWidth: must be the SAME unit the pagination
        // styles were computed in, or pages drift/clip when the two disagree.
        webView.evaluateJavascript(
            "document.body.style.transform='translateX('+(-($page*document.documentElement.clientWidth))+'px)';",
            null,
        )
    }

    fun goForward() {
        if (pageInChapter + 1 < pagesInChapter) showPage(pageInChapter + 1) else viewModel.nextChapter()
    }

    fun goBackward() {
        if (pageInChapter > 0) showPage(pageInChapter - 1) else viewModel.previousChapter()
    }

    // Configure client once; it re-paginates every time a chapter finishes loading.
    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep navigation inside the extracted book; ignore external links.
                return request.url.scheme != "file"
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(VIEWPORT_META_JS, null)
                // Give the engine a beat to relayout at device width before measuring.
                view.postDelayed({
                    view.evaluateJavascript(PAGINATE_JS) { result ->
                        val count = result?.trim('"')?.toIntOrNull() ?: 1
                        pagesInChapter = count.coerceAtLeast(1)
                        val target = if (viewModel.uiState.value.openAtLastPage) pagesInChapter - 1 else 0
                        showPage(target)
                    }
                }, 100)
            }
        }
        onDispose { webView.destroy() }
    }

    LaunchedEffect(uiState.chapterUrl) {
        uiState.chapterUrl?.let { url ->
            pageInChapter = 0
            pagesInChapter = 1
            webView.loadUrl(url)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.pageDeltas.collect { delta ->
            if (delta > 0) goForward() else goBackward()
        }
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(displayName, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        VoiceMicAction(
                            voiceActive = uiState.voiceActive,
                            onStartVoice = viewModel::startVoiceControl,
                            onStopVoice = viewModel::stopVoiceControl,
                        )
                    },
                )
            },
        ) { padding ->
            when {
                uiState.errorMessage != null -> {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(
                            uiState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    // Swipe left/right to turn pages, matching the PDF pager feel.
                    // The gesture overlay only consumes horizontal drags; plain
                    // taps still fall through to the WebView underneath.
                    val swipeThresholdPx = with(LocalDensity.current) { 60.dp.toPx() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .pointerInput(swipeThresholdPx) {
                                var dragTotal = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragTotal = 0f },
                                    onHorizontalDrag = { change, dragAmount ->
                                        dragTotal += dragAmount
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        when {
                                            dragTotal <= -swipeThresholdPx -> goForward()
                                            dragTotal >= swipeThresholdPx -> goBackward()
                                        }
                                    },
                                )
                            },
                    ) {
                        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        ) {
                            Text(
                                text = "Ch ${uiState.chapterIndex + 1}/${uiState.chapterCount}" +
                                    "  ·  Pg ${pageInChapter + 1}/$pagesInChapter",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        if (uiState.locked) {
            LockOverlay(onUnlock = viewModel::unlock)
        }
    }
}

/**
 * Splits the chapter into viewport-width pages and reports the page count.
 * Geometry: horizontal padding lives on <html>; columns are (vw - padding)
 * wide with a gap equal to the padding, so each page stride is EXACTLY one
 * viewport width — translateX(-page * vw) always lands on a page boundary.
 * Idempotent: re-running (e.g. after a repaint) returns the cached count.
 */
/** Forces layout at real device width, scale 1 — EPUB chapters ship no viewport meta. */
private val VIEWPORT_META_JS = """
    (function() {
      var d = document;
      var m = d.querySelector('meta[name="viewport"]');
      if (!m) {
        m = d.createElement('meta');
        m.setAttribute('name', 'viewport');
        d.head.appendChild(m);
      }
      m.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no');
    })()
""".trimIndent()

private val PAGINATE_JS = """
    (function() {
      if (window.__lrPageCount) return window.__lrPageCount;
      // Integer geometry throughout, based on the document's real layout
      // width (clientWidth) — innerWidth can disagree with it when WebView
      // applies a scale, which shows up as zoomed/clipped pages.
      var d = document, pad = 24;
      var vw = d.documentElement.clientWidth, vh = d.documentElement.clientHeight;
      var colw = vw - 2 * pad;
      var s = d.createElement('style');
      s.textContent =
        'html{margin:0 !important;box-sizing:border-box !important;' +
        'width:' + vw + 'px !important;height:' + vh + 'px !important;' +
        'padding:28px ' + pad + 'px 56px ' + pad + 'px !important;' +  // extra bottom: page indicator floats there
        'overflow:hidden !important;}' +
        'body{margin:0 !important;padding:0 !important;' +
        'width:' + colw + 'px !important;height:100% !important;' +
        'column-width:' + colw + 'px;column-gap:' + (2 * pad) + 'px;column-fill:auto;' +
        'font-size:19px;line-height:1.6;' +
        'transition:transform 200ms ease;will-change:transform;}' +
        'img,svg,video,table{max-width:' + colw + 'px !important;height:auto !important;}';
      d.head.appendChild(s);
      // Re-read after styling: scrollWidth reflects the new column layout.
      window.__lrPageCount = Math.max(1, Math.ceil(d.body.scrollWidth / vw));
      window.__lrPageWidth = vw;
      return window.__lrPageCount;
    })()
""".trimIndent()
