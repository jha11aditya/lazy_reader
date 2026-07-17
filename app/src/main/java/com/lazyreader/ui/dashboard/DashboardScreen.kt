package com.lazyreader.ui.dashboard

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lazyreader.R
import com.lazyreader.data.RecentDocument

// Palette chosen against the mossy-forest background image:
// - Header is opaque white (per design), so its text/icons use the deep
//   forest green that also underlines it.
// - Card borders: a WARM mid-dark brown — a truly dark brown would vanish
//   into the image's near-black soil patches; this shade still reads as
//   dark brown but keeps contrast against both moss green and wet earth.
// - Card fill: faint black tint (not fully transparent) so white text stays
//   readable over the brightest moss/dew areas of the photo.
private val ForestGreen = Color(0xFF1E4D2B)
private val WarmBrown = Color(0xFF8B5E3C)
private val CardTint = Color.Black.copy(alpha = 0.35f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    documents: List<RecentDocument>,
    onImportClick: () -> Unit,
    onDocumentClick: (RecentDocument) -> Unit,
    onDeleteClick: (RecentDocument) -> Unit,
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.dashboard_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Even scrim: the photo is dark overall but has bright dew sparkles;
        // a light uniform darkening keeps white card text readable everywhere
        // without hiding the texture.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f)),
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                "Lazy Reader",
                                fontFamily = FontFamily.Cursive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp,
                                color = ForestGreen,
                            )
                        },
                        actions = {
                            IconButton(onClick = onAboutClick) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = "About Lazy Reader",
                                    tint = ForestGreen,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                    )
                    HorizontalDivider(thickness = 3.dp, color = ForestGreen)
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onImportClick,
                    containerColor = ForestGreen,
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Import PDF")
                }
            },
        ) { padding ->
            if (documents.isEmpty()) {
                EmptyState(modifier = Modifier.padding(padding))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 12.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(documents, key = { it.uri }) { document ->
                        RecentDocumentCard(
                            document = document,
                            onClick = { onDocumentClick(document) },
                            onDeleteClick = { onDeleteClick(document) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No books yet",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = "Tap + to import a PDF and start reading hands-free.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun RecentDocumentCard(
    document: RecentDocument,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (document.totalPages > 0) {
        document.currentPage.toFloat() / document.totalPages
    } else {
        0f
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = CardTint),
        border = BorderStroke(1.5.dp, WarmBrown),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 16.dp)) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
                Text(
                    text = relativeLastOpened(document.lastOpenedAt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                )
                LinearProgressIndicator(
                    progress = { progress },
                    color = Color.White,
                    trackColor = WarmBrown.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                val unit = if (document.type == RecentDocument.TYPE_EPUB) "Chapter" else "Page"
                val pagesLabel = if (document.totalPages > 0) {
                    "$unit ${document.currentPage} of ${document.totalPages} · ${(progress * 100).toInt()}%"
                } else {
                    "Unable to read page count"
                }
                Text(
                    text = pagesLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${document.displayName} from history",
                    tint = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

private fun relativeLastOpened(lastOpenedAt: Long): String =
    DateUtils.getRelativeTimeSpanString(
        lastOpenedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
