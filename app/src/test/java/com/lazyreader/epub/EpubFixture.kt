package com.lazyreader.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds EPUB zip fixtures for tests. [zipOf] writes arbitrary raw entries
 * (used by EpubParserTest for malformed-EPUB cases); [writeValidEpub] wraps
 * it with a minimal-but-real container.xml + OPF + chapter set, reused by
 * any test that just needs a working book to open.
 */
object EpubFixture {

    fun zipOf(target: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return target
    }

    // Built with flush-left, no-trimIndent string concatenation rather than
    // trimIndent() on a triple-quoted template: trimIndent() strips the
    // *smallest* common indentation across every line, and manifestItems/
    // spineItems below are themselves multi-line with their own (different)
    // indentation once joined in — that skews the computed strip amount and
    // was leaving stray whitespace before the leading "<?xml", which real
    // XML parsers reject outright (the declaration must be the first thing
    // in the document).
    fun validEpubEntries(
        title: String = "Test Book",
        chapterTexts: List<String> = listOf("Chapter One", "Chapter Two"),
    ): Map<String, ByteArray> {
        val containerXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
            "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
            "media-type=\"application/oebps-package+xml\"/></rootfiles>\n" +
            "</container>"

        val manifestItems = chapterTexts.indices.joinToString("\n") { i ->
            "<item id=\"chap${i + 1}\" href=\"chapter${i + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>"
        }
        val spineItems = chapterTexts.indices.joinToString("\n") { i ->
            "<itemref idref=\"chap${i + 1}\"/>"
        }
        val contentOpf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"BookId\">\n" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>$title</dc:title></metadata>\n" +
            "<manifest>\n$manifestItems\n</manifest>\n" +
            "<spine>\n$spineItems\n</spine>\n" +
            "</package>"

        val entries = mutableMapOf(
            "META-INF/container.xml" to containerXml.toByteArray(),
            "OEBPS/content.opf" to contentOpf.toByteArray(),
        )
        chapterTexts.forEachIndexed { i, text ->
            entries["OEBPS/chapter${i + 1}.xhtml"] = (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>$text</p></body></html>"
                ).toByteArray()
        }
        return entries
    }

    fun writeValidEpub(
        target: File,
        title: String = "Test Book",
        chapterTexts: List<String> = listOf("Chapter One", "Chapter Two"),
    ): File = zipOf(target, validEpubEntries(title, chapterTexts))
}
