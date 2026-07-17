package com.lazyreader.ui.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    // Covers the WebView with a spinner until PAGINATE_JS has run: without
    // it, bigger chapters visibly flash their unstyled (wide, unpaginated)
    // layout for the ~100ms+ it takes the pagination CSS to apply and the
    // engine to reflow, then snap to the correct narrow columned view.
    var isPaginated by remember { mutableStateOf(false) }
    // Content renders full-bleed (no Scaffold content inset) so the top bar
    // and bottom indicator can float on top and auto-hide/reappear without
    // resizing the WebView underneath — critical here, since the pagination
    // math is keyed off the WebView's real measured height. Two separate
    // visibilities: page turns reveal only the bottom indicator (feedback
    // that a voice command worked, positioned where it never covers the
    // start of the text) — the header overlays exactly where reading
    // begins, so it appears on tap only.
    val (headerVisible, pokeHeader) = rememberChromeVisibility()
    val (indicatorVisible, pokeIndicator) = rememberChromeVisibility()
    var showJumpDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val webView = remember {
        @SuppressLint("SetJavaScriptEnabled")
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            // false, not true: per WebSettings docs this makes layout width
            // ALWAYS equal the real device width in CSS px, regardless of any
            // viewport meta tag. EPUB chapters ship with no viewport meta, so
            // useWideViewPort=true fell back to a wide (~1216 CSS px) desktop
            // layout that a JS-injected meta tag was supposed to override —
            // but injecting it after onPageFinished didn't reliably force a
            // re-layout before PAGINATE_JS measured, producing pages many
            // times wider than the screen (content ran off the right edge,
            // "swipe" just panned across it instead of turning a page).
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            // TEXT_AUTOSIZING (the default) inflates fonts after layout and
            // breaks the fixed column pagination math.
            settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
            settings.textZoom = 100
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            // Hiding the scrollbars above doesn't stop WebView's own native
            // touch panning, which was fighting with the swipe gesture below
            // (Compose's pointerInput.consume() doesn't block the AndroidView
            // child's native touch dispatch) — the two combined into a small
            // leftover scroll offset stacking with showPage()'s translateX,
            // clipping a few characters off the page edge after each swipe.
            // This is a read-only reflowable page, not a scrollable one, so
            // fully absorbing touch here and leaving paging to the Compose
            // drag detector below is correct, not just a workaround.
            //
            // Taps, however, must be recognized HERE, not in a Compose
            // detectTapGestures on the wrapping Box: because this listener
            // absorbs everything, a plain tap is consumed at the child level
            // and the parent's tap detector never fires (drags still work —
            // they're recognized over a longer event stream that the parent
            // drag detector claims before the child swallows it).
            val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(event.x - downX) <= touchSlop &&
                            kotlin.math.abs(event.y - downY) <= touchSlop
                        ) {
                            pokeHeader()
                            pokeIndicator()
                        }
                    }
                }
                true
            }
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
        pokeIndicator()
    }

    fun goBackward() {
        if (pageInChapter > 0) showPage(pageInChapter - 1) else viewModel.previousChapter()
        pokeIndicator()
    }

    // Configure client once; it re-paginates every time a chapter finishes loading.
    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep navigation inside the extracted book; ignore external links.
                return request.url.scheme != "file"
            }

            override fun onPageFinished(view: WebView, url: String) {
                // document.documentElement.clientHeight was found (via device
                // logging) to report more than 2x the real WebView height here
                // — clientWidth matched the real Android View width exactly,
                // but height didn't, for reasons that weren't worth chasing
                // further. Passing the real, Kotlin-measured pixel height in
                // sidesteps whatever WebView-internal quirk was inflating it,
                // which is what every CSS padding/height tweak was invisibly
                // fighting: a ~2x-too-tall page swallows a 16-44px nudge.
                // Give the engine a beat to settle layout before measuring —
                // read view.height after that same delay, not before: at the
                // instant onPageFinished fires, the Android View's own layout
                // pass isn't guaranteed to have completed yet (most visible
                // in a freshly-created host, e.g. a bare test Activity).
                view.postDelayed({
                    val trustedHeightPx = (view.height / view.resources.displayMetrics.density).toInt()
                    view.evaluateJavascript(buildPaginateJs(trustedHeightPx)) { result ->
                        val count = result?.trim('"')?.toIntOrNull() ?: 1
                        pagesInChapter = count.coerceAtLeast(1)
                        val state = viewModel.uiState.value
                        val anchor = state.pendingAnchor
                        if (anchor != null) {
                            // TOC jump into this file: land on the page holding
                            // the anchor element, not the file's first page.
                            view.evaluateJavascript(anchorPageJs(anchor, currentPage = 0)) { pageResult ->
                                val page = pageResult?.trim('"')?.toIntOrNull() ?: 0
                                showPage(page.coerceIn(0, pagesInChapter - 1))
                                viewModel.clearPendingAnchor()
                                isPaginated = true
                            }
                        } else {
                            showPage(if (state.openAtLastPage) pagesInChapter - 1 else 0)
                            isPaginated = true
                        }
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
            isPaginated = false
            // loadUrl() lets WebView infer the MIME type from the .xhtml
            // extension, which parses it as strict application/xhtml+xml —
            // in that mode a <style> element created and appended via JS is
            // silently never registered as a stylesheet, so the column-width
            // pagination CSS never applies. Explicitly loading as text/html
            // forces the normal, lenient HTML parser instead. baseUrl stays
            // the chapter's real file:// location so relative references
            // (images etc.) still resolve correctly.
            val html = withContext(Dispatchers.IO) { File(Uri.parse(url).path!!).readText() }
            webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.pageDeltas.collect { delta ->
            if (delta > 0) goForward() else goBackward()
        }
    }

    // TOC jump within the ALREADY-loaded chapter file: chapterUrl doesn't
    // change, so no reload/onPageFinished fires — honor the anchor directly.
    // Cross-file jumps arrive here with isPaginated=false and are skipped;
    // the onPageFinished path above handles those after the reload.
    LaunchedEffect(uiState.pendingAnchor) {
        val anchor = uiState.pendingAnchor
        if (anchor != null && isPaginated) {
            webView.evaluateJavascript(anchorPageJs(anchor, currentPage = pageInChapter)) { pageResult ->
                val page = pageResult?.trim('"')?.toIntOrNull() ?: 0
                showPage(page.coerceIn(0, pagesInChapter - 1))
                viewModel.clearPendingAnchor()
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            uiState.errorMessage != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        uiState.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                // Swipe left/right to turn pages, matching the PDF pager feel;
                // a plain tap reveals chrome instead. Both live in their own
                // pointerInput block on the same Box — Compose gives each an
                // independent copy of the event stream, so the drag detector
                // (which only consumes once a real drag is recognized) and
                // the tap detector coexist without stepping on each other.
                val swipeThresholdPx = with(LocalDensity.current) { 60.dp.toPx() }
                Box(
                    Modifier
                        .fillMaxSize()
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
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                pokeHeader()
                                pokeIndicator()
                            })
                        },
                ) {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

                    if (!isPaginated) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    AnimatedVisibility(
                        visible = headerVisible || indicatorVisible,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            modifier = Modifier.clickable { showJumpDialog = true },
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

                if (showJumpDialog) {
                    val toc = uiState.toc
                    if (toc.isNotEmpty()) {
                        // Real chapters from the book's nav TOC. Spine files
                        // often pack many actual chapters each (e.g. Project
                        // Gutenberg), so a spine-based slider would show
                        // "chapters" that don't match the book's own.
                        TocDialog(
                            titles = toc.map { it.title },
                            onSelect = { index -> viewModel.jumpToTocEntry(toc[index]) },
                            onDismiss = { showJumpDialog = false },
                        )
                    } else {
                        JumpToDialog(
                            title = "Jump to section",
                            itemLabel = "Section",
                            currentIndex = uiState.chapterIndex,
                            totalCount = uiState.chapterCount,
                            onJumpTo = viewModel::jumpToChapter,
                            onDismiss = { showJumpDialog = false },
                        )
                    }
                }
            }
        }

        // Kept reachable during loading/error (not gated on headerVisible
        // alone) so the back button never auto-hides before the user has
        // had a chance to see it.
        AnimatedVisibility(
            visible = headerVisible || uiState.isLoading || uiState.errorMessage != null,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
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
        }

        if (uiState.locked) {
            LockOverlay(onUnlock = viewModel::unlock)
        }
    }
}

/**
 * Which page (0-based column index) the element with id [anchor] sits on.
 * getBoundingClientRect().left is relative to the CURRENT translated
 * viewport, so the real column x is rect.left + currentPage * viewport
 * width; dividing by the viewport width (the exact page stride — see
 * [buildPaginateJs] geometry) yields the page. Returns 0 if the id is
 * missing so a bad anchor degrades to the chapter start, not a crash.
 */
private fun anchorPageJs(anchor: String, currentPage: Int): String {
    val escaped = anchor.replace("\\", "\\\\").replace("'", "\\'")
    return """
        (function() {
          var el = document.getElementById('$escaped');
          if (!el) return 0;
          var vw = document.documentElement.clientWidth;
          var x = el.getBoundingClientRect().left + ($currentPage * vw);
          return Math.max(0, Math.floor(x / vw));
        })()
    """.trimIndent()
}

/**
 * Splits the chapter into viewport-width pages and reports the page count.
 * Geometry: horizontal padding lives on <html>; columns are (vw - padding)
 * wide with a gap equal to the padding, so each page stride is EXACTLY one
 * viewport width — translateX(-page * vw) always lands on a page boundary.
 * Idempotent: re-running (e.g. after a repaint) returns the cached count.
 */
private fun buildPaginateJs(trustedHeightPx: Int): String = """
    (function() {
      if (window.__lrPageCount) return window.__lrPageCount;
      // Integer geometry throughout, based on the document's real layout
      // width (clientWidth) — innerWidth can disagree with it when WebView
      // applies a scale, which shows up as zoomed/clipped pages.
      var d = document, pad = 24, topPad = 28, bottomPad = 100;
      var vw = d.documentElement.clientWidth;
      // NOT document.documentElement.clientHeight: on-device logging showed
      // it reporting more than double the WebView's real pixel height here,
      // while clientWidth matched perfectly — so height comes from Kotlin
      // (the real Android View height / density) instead of trusting it.
      var vh = $trustedHeightPx;
      var colw = vw - 2 * pad;
      // column-fill:auto fills each column to exactly this element's height,
      // and if that height isn't a whole multiple of line-height, the line
      // straddling the boundary gets sliced in half by html's overflow:hidden
      // (a known column-pagination issue, e.g. readium/swift-toolkit#804).
      // Rounding down to the nearest full line avoids that: a small blank
      // gap at the bottom beats a clipped line.
      var fontSize = 19, lineHeight = fontSize * 1.6;
      var rawContentH = vh - topPad - bottomPad;
      var contentH = Math.max(lineHeight, Math.floor(rawContentH / lineHeight) * lineHeight);
      var htmlH = contentH + topPad + bottomPad;
      var s = d.createElement('style');
      s.textContent =
        'html{margin:0 !important;box-sizing:border-box !important;' +
        'width:' + vw + 'px !important;height:' + htmlH + 'px !important;' +
        'padding:' + topPad + 'px ' + pad + 'px ' + bottomPad + 'px ' + pad + 'px !important;' +
        'overflow:hidden !important;}' +
        'body{margin:0 !important;padding:0 !important;' +
        // overflow stays visible (not hidden) here: the columns MUST paint
        // beyond body's own declared (one-column-wide) box for the multicol
        // trick to work at all — html's overflow:hidden above is what clips
        // the viewport to one page; hiding it here too clipped every column
        // after the first, leaving later pages blank.
        'width:' + colw + 'px !important;height:100% !important;' +
        'column-width:' + colw + 'px;column-gap:' + (2 * pad) + 'px;column-fill:auto;' +
        'font-size:' + fontSize + 'px;line-height:1.6;' +
        'transition:transform 200ms ease;will-change:transform;}' +
        // break-inside:avoid is only a hint the engine can ignore when an
        // image is simply taller than one page to begin with — capping
        // max-height (alongside max-width, both with auto on the other
        // axis so aspect ratio is preserved) guarantees it never is, which
        // is what actually stops images being sliced across a page break.
        'img,svg,video,table{max-width:' + colw + 'px !important;' +
        'max-height:' + contentH + 'px !important;width:auto !important;height:auto !important;' +
        'break-inside:avoid;-webkit-column-break-inside:avoid;page-break-inside:avoid;}' +
        // Real EPUBs commonly wrap figures in a container with its OWN
        // hardcoded inline width (e.g. Project Gutenberg's
        // <div class="figcenter" style="width: 550px;">) — capping the img
        // alone still leaves that wider container spanning the column
        // boundary, which is what a "split image" actually was. max-width
        // as !important overrides a plain (non-!important) inline width
        // regardless of specificity, and applies safely to every element
        // since it's just an upper bound, not a fixed size.
        'body *{max-width:' + colw + 'px !important;}' +
        // Keep the image+caption block together where possible, same
        // reasoning as break-inside:avoid on the image itself above.
        'body :has(> img){break-inside:avoid;-webkit-column-break-inside:avoid;page-break-inside:avoid;}';
      d.head.appendChild(s);
      // Re-read after styling: scrollWidth reflects the new column layout.
      window.__lrPageCount = Math.max(1, Math.ceil(d.body.scrollWidth / vw));
      window.__lrPageWidth = vw;
      return window.__lrPageCount;
    })()
""".trimIndent()
