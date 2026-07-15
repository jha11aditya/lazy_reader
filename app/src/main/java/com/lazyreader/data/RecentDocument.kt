package com.lazyreader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_documents")
data class RecentDocument(
    @PrimaryKey val uri: String,
    val displayName: String,
    /** For EPUBs, "pages" are spine items (chapters); text reflows, so real pages aren't fixed. */
    val totalPages: Int,
    val currentPage: Int = 0,
    val lastOpenedAt: Long,
    val type: String = TYPE_PDF,
) {
    companion object {
        const val TYPE_PDF = "pdf"
        const val TYPE_EPUB = "epub"
    }
}
