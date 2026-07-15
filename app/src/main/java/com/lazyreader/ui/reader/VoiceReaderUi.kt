package com.lazyreader.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

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
