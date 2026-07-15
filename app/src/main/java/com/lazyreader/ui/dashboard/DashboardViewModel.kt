package com.lazyreader.ui.dashboard

import android.app.Application
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazyreader.data.AppDatabase
import com.lazyreader.data.RecentDocument
import com.lazyreader.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).recentDocumentDao()

    val recentDocuments: StateFlow<List<RecentDocument>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun importDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers don't support persistable grants; access still
            // works for this session via the grant SAF already attached to the result.
        }

        val uriString = uri.toString()
        val existing = dao.getByUri(uriString)
        if (existing != null) {
            dao.upsert(existing.copy(lastOpenedAt = System.currentTimeMillis()))
            return@withContext uriString
        }

        val displayName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "Untitled"
        val isEpub = resolver.getType(uri) == "application/epub+zip" ||
            displayName.endsWith(".epub", ignoreCase = true)

        val totalPages: Int
        val type: String
        if (isEpub) {
            // Extract + parse now: counts chapters and warms the cache the reader uses.
            totalPages = EpubParser.open(getApplication(), uri).chapterFiles.size
            type = RecentDocument.TYPE_EPUB
        } else {
            totalPages = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            } ?: 0
            type = RecentDocument.TYPE_PDF
        }

        dao.upsert(
            RecentDocument(
                uri = uriString,
                displayName = displayName,
                totalPages = totalPages,
                currentPage = 0,
                lastOpenedAt = System.currentTimeMillis(),
                type = type,
            ),
        )
        uriString
    }

    fun deleteDocument(document: RecentDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteByUri(document.uri)
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }
}
