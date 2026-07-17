package com.lazyreader.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lazyreader.R

private const val DEVELOPER_NAME = "Aditya Kumar"
private const val DEVELOPER_EMAIL = "ansuloco@gmail.com"

/**
 * Warm gold accent for borders/titles: stands out against both the artwork's
 * cool blue night tones and the red blanket, unlike white (blends with the
 * pillow/window light) or red/blue (blend with the scene itself).
 */
private val AccentGold = Color(0xFFFFD54F)

/**
 * About page: the app's privacy story (fully offline, OS-enforced no-network,
 * on-device voice recognition, no ads), voice command reference, and the
 * developer contact. The Santa artwork sits full-bleed behind a scrim so
 * the text cards stay readable on top of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.about_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Light vertical scrim: just enough for text contrast — the cards are
        // near-transparent by design so the artwork stays clearly visible.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.2f),
                        0.5f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("About Lazy Reader", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    "Lazy Reader",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Text(
                    "Version $versionName · Hands-free PDF & EPUB reading",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(20.dp))

                AboutCard(
                    icon = Icons.Filled.CloudOff,
                    title = "100% offline — enforced by Android",
                    body = "Lazy Reader does not request internet permission, so the " +
                        "operating system itself forbids it from ever sending anything " +
                        "anywhere. You can verify this in the app's system settings: " +
                        "there is no network access to grant. Your books and reading " +
                        "habits never leave your device.",
                )
                Spacer(Modifier.height(12.dp))
                AboutCard(
                    icon = Icons.Filled.MicOff,
                    title = "The microphone listens, but nothing is recorded",
                    body = "Voice commands (“go”, “backward”, “stop”) are recognized " +
                        "by a tiny model running entirely on your phone. Audio is " +
                        "analyzed in memory the moment it's heard and immediately " +
                        "discarded — never saved to storage, never uploaded (the app " +
                        "can't upload anything — see above). The mic is only active " +
                        "while you turn voice control on, and saying “stop” shuts " +
                        "it off completely.",
                )
                Spacer(Modifier.height(12.dp))
                AboutCard(
                    icon = Icons.Filled.Block,
                    title = "No ads, no tracking, no accounts",
                    body = "No advertising, no analytics, no sign-in, and no data " +
                        "collection of any kind. The app stores exactly one thing: " +
                        "your reading progress, locally on your device.",
                )
                Spacer(Modifier.height(20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Developed by $DEVELOPER_NAME",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                        Text(
                            DEVELOPER_EMAIL,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:$DEVELOPER_EMAIL")
                                putExtra(Intent.EXTRA_SUBJECT, "Lazy Reader feedback")
                            }
                            // No email app is fine — just do nothing rather than crash.
                            runCatching { context.startActivity(intent) }
                        },
                    ) {
                        Icon(Icons.Filled.Email, contentDescription = null, tint = AccentGold)
                        Spacer(Modifier.width(6.dp))
                        Text("Contact", color = AccentGold)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AboutCard(icon: ImageVector, title: String, body: String) {
    Card(
        // Near-transparent so the artwork shows through; the faint dark tint
        // keeps text readable over the busiest parts of the illustration.
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.25f)),
        border = BorderStroke(1.5.dp, AccentGold),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = AccentGold)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = AccentGold)
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}
