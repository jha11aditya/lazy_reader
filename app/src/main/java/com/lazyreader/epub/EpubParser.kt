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
        return EpubBook(title = title, chapterFiles = chapters)
    }
}

class EpubFormatException(message: String) : Exception(message)
