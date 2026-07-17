package com.lazyreader.ui.reader

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    documentUri: String,
    displayName: String,
    startPage: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ReaderViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(documentUri) {
        viewModel.open(Uri.parse(documentUri), startPage)
    }

    // Keep-awake only while actively reading: once "stop" locks the session,
    // release it so the system screen timeout can dim and lock the phone.
    val view = LocalView.current
    DisposableEffect(uiState.locked) {
        view.keepScreenOn = !uiState.locked
        onDispose { view.keepScreenOn = false }
    }

    // Content renders full-bleed (no Scaffold content inset) so the top bar
    // and bottom indicator can float on top and auto-hide/reappear without
    // resizing the pager underneath.
    val (chromeVisible, pokeChrome) = rememberChromeVisibility()
    var showJumpDialog by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        when {
            uiState.errorMessage != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(uiState.errorMessage.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            uiState.isLoading || uiState.pageCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                val pagerState = rememberPagerState(initialPage = uiState.currentPage) { uiState.pageCount }

                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { page ->
                            viewModel.onPageChanged(page)
                            pokeChrome()
                        }
                }

                LaunchedEffect(pagerState) {
                    viewModel.pageRequests.collect { page ->
                        pagerState.animateScrollToPage(page)
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { pokeChrome() }) },
                ) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                            val bitmap = uiState.bitmaps[page]
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = chromeVisible,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            modifier = Modifier.clickable { showJumpDialog = true },
                        ) {
                            Text(
                                text = "${uiState.currentPage + 1} / ${uiState.pageCount}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                if (showJumpDialog) {
                    JumpToDialog(
                        title = "Jump to page",
                        itemLabel = "Page",
                        currentIndex = uiState.currentPage,
                        totalCount = uiState.pageCount,
                        onJumpTo = viewModel::jumpToPage,
                        onDismiss = { showJumpDialog = false },
                    )
                }
            }
        }

        // Kept reachable during loading/error (not gated on chromeVisible
        // alone) so the back button never auto-hides before the user has
        // had a chance to see it.
        AnimatedVisibility(
            visible = chromeVisible || uiState.isLoading || uiState.errorMessage != null,
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
