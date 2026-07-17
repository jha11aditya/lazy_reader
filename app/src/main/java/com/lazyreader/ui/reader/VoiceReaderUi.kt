package com.lazyreader.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Top-bar mic toggle shared by the PDF and EPUB readers. Handles the
 * RECORD_AUDIO permission request on first activation.
 */
@Composable
fun VoiceMicAction(
    voiceActive: Boolean,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
) {
    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onStartVoice()
    }

    IconButton(
        onClick = {
            if (voiceActive) {
                onStopVoice()
            } else {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) onStartVoice() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    ) {
        Icon(
            if (voiceActive) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = if (voiceActive) {
                "Voice control on. Say go, backward, or stop."
            } else {
                "Turn on voice control"
            },
            tint = if (voiceActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Full-screen "pocket lock" scrim shown after the "stop" voice command:
 * swallows every touch except the lock icon, so the phone can lie face-up
 * without accidental page turns (CLAUDE.md Section 2B). For a full device
 * lock the user presses the power button — an in-app lock would need the
 * device-admin permission, deliberately avoided to keep the app trustworthy.
 */
@Composable
fun LockOverlay(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onUnlock, modifier = Modifier.size(72.dp)) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Unlock reading mode",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Voice Controls Stopped.\nTap Lock icon to Unlock.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Chrome (top bar + page indicator) starts visible and auto-hides after
 * [hideAfterMs] of no interaction; call the returned poke function on
 * tap/swipe to reveal it again and restart the countdown. Restarting a
 * [LaunchedEffect] on every poke is a simpler way to get "show now, hide
 * after N ms of no further pokes" than a hand-rolled timer/job.
 */
@Composable
fun rememberChromeVisibility(hideAfterMs: Long = 3_000L): Pair<Boolean, () -> Unit> {
    var pokeCount by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(pokeCount) {
        visible = true
        delay(hideAfterMs)
        visible = false
    }
    val poke: () -> Unit = { pokeCount++ }
    return visible to poke
}

/**
 * Popup slider for jumping directly to a page (PDF) or chapter (EPUB)
 * instead of swiping one at a time through a long book. [onJumpTo] only
 * fires once, on release, not on every intermediate drag frame — a PDF
 * bitmap render or EPUB chapter load per drag pixel would be wasteful.
 */
@Composable
fun JumpToDialog(
    title: String,
    itemLabel: String,
    currentIndex: Int,
    totalCount: Int,
    onJumpTo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var sliderValue by remember(currentIndex) { mutableFloatStateOf(currentIndex.toFloat()) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    "$itemLabel ${sliderValue.roundToInt() + 1} of $totalCount",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..(totalCount - 1).toFloat().coerceAtLeast(0f),
                    steps = (totalCount - 2).coerceAtLeast(0),
                    onValueChangeFinished = {
                        onJumpTo(sliderValue.roundToInt())
                        onDismiss()
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * Popup list of a book's real chapters (from its nav TOC) — used instead of
 * [JumpToDialog]'s slider for EPUBs that ship a table of contents, where
 * spine-file "sections" don't match the chapters readers actually know.
 */
@Composable
fun TocDialog(
    titles: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    "Jump to chapter",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    itemsIndexed(titles) { index, title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(index)
                                    onDismiss()
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
