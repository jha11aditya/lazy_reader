package com.lazyreader.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal EPUB builder for instrumented tests. Deliberately duplicated from
 * the JVM-test EpubFixture (app/src/test) rather than shared: the test and
 * androidTest source sets compile independently in this project, and a
 * ~30-line helper isn't worth wiring up a shared testFixtures source set for.
 */
object EpubTestFixture {

    fun write(target: File, chapterHtmlBodies: List<String>): File {
        val containerXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
            "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
            "media-type=\"application/oebps-package+xml\"/></rootfiles>\n" +
            "</container>"

        val manifestItems = chapterHtmlBodies.indices.joinToString("\n") { i ->
            "<item id=\"chap${i + 1}\" href=\"chapter${i + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>"
        }
        val spineItems = chapterHtmlBodies.indices.joinToString("\n") { i ->
            "<itemref idref=\"chap${i + 1}\"/>"
        }
        val contentOpf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"BookId\">\n" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Test Book</dc:title></metadata>\n" +
            "<manifest>\n$manifestItems\n</manifest>\n" +
            "<spine>\n$spineItems\n</spine>\n" +
            "</package>"

        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("META-INF/container.xml", containerXml.toByteArray())
            entry("OEBPS/content.opf", contentOpf.toByteArray())
            chapterHtmlBodies.forEachIndexed { i, body ->
                val xhtml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$body</body></html>"
                entry("OEBPS/chapter${i + 1}.xhtml", xhtml.toByteArray())
            }
        }
        return target
    }

    /**
     * Enough repeated paragraphs to force multi-page CSS-column pagination on
     * any phone screen. Deliberately modest (not hundreds of paragraphs):
     * PAGINATE_JS measures column overflow ~100ms after page load and caches
     * the result, so very large content risks the measurement racing ahead
     * of layout completion on a slow device — this amount reflows quickly
     * while still safely exceeding one page (~8-10 short paragraphs/page).
     */
    fun longChapterBody(paragraphCount: Int = 24): String {
        val paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod " +
            "tempor incididunt ut labore et dolore magna aliqua."
        return (1..paragraphCount).joinToString("") { "<p>$paragraph</p>" }
    }
}
