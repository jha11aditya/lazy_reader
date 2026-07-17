package com.lazyreader.epub

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Parsed EPUB ready for display: spine chapters in reading order, as files
 * extracted under the app's private storage.
 */
data class EpubBook(
    val title: String?,
    /** Absolute paths of the spine's XHTML documents, in reading order. */
    val chapterFiles: List<File>,
    /**
     * Real chapters from the book's nav TOC, in document order. Often finer-
     * grained than [chapterFiles]: one spine file commonly packs many actual
     * chapters (e.g. Project Gutenberg books). Empty if the book has no
     * EPUB3 nav document.
     */
    val toc: List<TocEntry> = emptyList(),
)

/** One entry of the book's table of contents. */
data class TocEntry(
    val title: String,
    /** Index into [EpubBook.chapterFiles] of the spine file holding this entry. */
    val chapterIndex: Int,
    /** Element id within that file to scroll to, or null for the file start. */
    val anchor: String?,
)

/**
 * Deliberately lightweight EPUB 2/3 parser (CLAUDE.md Section 1): unzip,
 * read META-INF/container.xml to locate the OPF package document, then read
 * the OPF's manifest + spine to order the chapter XHTML files. No external
 * EPUB library — those bring DRM/rendering machinery this app doesn't need.
 */
object EpubParser {

    /**
     * Extracts (once) and parses the EPUB at [uri]. Safe to call repeatedly;
     * re-parsing after the initial extraction only reads two small XML files.
     */
    fun open(context: Context, uri: Uri): EpubBook {
        val bookDir = extractIfNeeded(context, uri)
        val opfFile = locateOpf(bookDir)
            ?: throw EpubFormatException("META-INF/container.xml missing or has no rootfile")
        return parseOpf(opfFile)
    }

    /** Extraction is keyed by URI hash so each imported book unpacks only once. */
    private fun extractIfNeeded(context: Context, uri: Uri): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val bookDir = File(context.filesDir, "epubs/$key")
        val marker = File(bookDir, ".extracted")
        if (marker.exists()) return bookDir

        bookDir.deleteRecursively()
        bookDir.mkdirs()
        val canonicalRoot = bookDir.canonicalPath + File.separator
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(bookDir, entry.name)
                        // Zip-slip guard: refuse entries escaping the book dir.
                        if (!out.canonicalPath.startsWith(canonicalRoot)) {
                            throw EpubFormatException("Illegal zip entry path: ${entry.name}")
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
        } ?: throw EpubFormatException("Cannot open stream for $uri")
        marker.createNewFile()
        return bookDir
    }

    private fun locateOpf(bookDir: File): File? {
        val container = File(bookDir, "META-INF/container.xml")
        if (!container.exists()) return null
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(FileInputStream(container), null)
        }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path") ?: continue
                return File(bookDir, fullPath).takeIf { it.exists() }
            }
        }
        return null
    }

    private fun parseOpf(opfFile: File): EpubBook {
        val opfDir = opfFile.parentFile!!
        var title: String? = null
        val manifest = mutableMapOf<String, String>() // id -> href
        val spineIdrefs = mutableListOf<String>()
        var navHref: String? = null

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(FileInputStream(opfFile), null)
        }
        var inMetadata = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "metadata" -> inMetadata = true
                    "title" -> if (inMetadata && title == null) title = parser.nextText().trim()
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = href
                        // EPUB3 marks the TOC document with properties="nav".
                        val properties = parser.getAttributeValue(null, "properties")
                        if (href != null && properties?.split(' ')?.contains("nav") == true) navHref = href
                    }
                    "itemref" -> {
                        parser.getAttributeValue(null, "idref")?.let { spineIdrefs.add(it) }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "metadata") inMetadata = false
            }
        }

        val chapters = spineIdrefs.mapNotNull { idref ->
            manifest[idref]?.let { href ->
                File(opfDir, Uri.decode(href)).takeIf { it.exists() }
            }
        }
        if (chapters.isEmpty()) throw EpubFormatException("Spine has no readable chapters")
        val toc = navHref
            ?.let { File(opfDir, Uri.decode(it)).takeIf(File::exists) }
            ?.let { parseNavToc(it, chapters) }
            .orEmpty()
        return EpubBook(title = title, chapterFiles = chapters, toc = toc)
    }

    /**
     * Reads the `<nav epub:type="toc">` section of the EPUB3 nav document into
     * [TocEntry]s. Entries pointing at files that aren't in the spine (or at a
     * missing file) are dropped. Best-effort: a malformed nav yields an empty
     * list rather than failing the whole book.
     */
    private fun parseNavToc(navFile: File, chapters: List<File>): List<TocEntry> {
        val chapterIndexByName = chapters.withIndex().associate { (i, f) -> f.name to i }
        val entries = mutableListOf<TocEntry>()
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(FileInputStream(navFile), null)
            }
            var navDepth = 0
            var inTocNav = false
            var currentHref: String? = null
            val currentText = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "nav" -> {
                            if (!inTocNav) {
                                // epub:type is namespaced; match any-namespace "type".
                                val type = (0 until parser.attributeCount)
                                    .firstOrNull { parser.getAttributeName(it) == "type" }
                                    ?.let(parser::getAttributeValue)
                                if (type == "toc") {
                                    inTocNav = true
                                    navDepth = 1
                                }
                            } else {
                                navDepth++
                            }
                        }
                        "a" -> if (inTocNav) {
                            currentHref = parser.getAttributeValue(null, "href")
                            currentText.clear()
                        }
                    }
                    XmlPullParser.TEXT -> if (currentHref != null) currentText.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "nav" -> if (inTocNav && --navDepth == 0) return entries
                        "a" -> {
                            val href = currentHref
                            currentHref = null
                            val label = currentText.toString().trim().replace(Regex("\\s+"), " ")
                            if (href != null && label.isNotEmpty()) {
                                val fileName = Uri.decode(href.substringBefore('#').substringAfterLast('/'))
                                val anchor = href.substringAfter('#', "").ifEmpty { null }
                                chapterIndexByName[fileName]?.let { index ->
                                    entries.add(TocEntry(title = label, chapterIndex = index, anchor = anchor))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("EpubParser", "Failed to parse nav TOC, falling back to spine sections", e)
            return emptyList()
        }
        return entries
    }
}

class EpubFormatException(message: String) : Exception(message)
