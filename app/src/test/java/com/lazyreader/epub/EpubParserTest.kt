package com.lazyreader.epub

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `valid epub parses title and spine order`() {
        val epub = EpubFixture.writeValidEpub(
            tempFolder.newFile("book.epub"),
            title = "My Book",
            chapterTexts = listOf("One", "Two"),
        )

        val book = EpubParser.open(context, Uri.fromFile(epub))

        assertEquals("My Book", book.title)
        assertEquals(listOf("chapter1.xhtml", "chapter2.xhtml"), book.chapterFiles.map { it.name })
    }

    @Test
    fun `missing container xml throws`() {
        val entries = EpubFixture.validEpubEntries().filterKeys { it != "META-INF/container.xml" }
        val epub = EpubFixture.zipOf(tempFolder.newFile("no-container.epub"), entries)

        assertThrows(EpubFormatException::class.java) {
            EpubParser.open(context, Uri.fromFile(epub))
        }
    }

    @Test
    fun `empty spine throws`() {
        val epub = EpubFixture.writeValidEpub(tempFolder.newFile("empty-spine.epub"), chapterTexts = emptyList())

        assertThrows(EpubFormatException::class.java) {
            EpubParser.open(context, Uri.fromFile(epub))
        }
    }

    @Test
    fun `zip entry escaping the book dir is rejected`() {
        val entries = EpubFixture.validEpubEntries() + mapOf("../evil.txt" to "malicious".toByteArray())
        val epub = EpubFixture.zipOf(tempFolder.newFile("zip-slip.epub"), entries)

        assertThrows(EpubFormatException::class.java) {
            EpubParser.open(context, Uri.fromFile(epub))
        }
    }

    @Test
    fun `nav toc parses titles anchors and chapter indices`() {
        val entries = EpubFixture.validEpubEntries(
            chapterTexts = listOf("One", "Two"),
            tocEntries = listOf(
                Triple("Chapter I", 1, "ch1"),
                Triple("Chapter II", 1, "ch2"),
                Triple("Chapter III", 2, null),
            ),
        )
        val epub = EpubFixture.zipOf(tempFolder.newFile("toc.epub"), entries)

        val book = EpubParser.open(context, Uri.fromFile(epub))

        assertEquals(
            listOf(
                TocEntry("Chapter I", 0, "ch1"),
                TocEntry("Chapter II", 0, "ch2"),
                TocEntry("Chapter III", 1, null),
            ),
            book.toc,
        )
    }

    @Test
    fun `book without nav doc yields empty toc`() {
        val epub = EpubFixture.writeValidEpub(tempFolder.newFile("no-toc.epub"))

        val book = EpubParser.open(context, Uri.fromFile(epub))

        assertEquals(emptyList<TocEntry>(), book.toc)
    }

    @Test
    fun `re-opening the same uri is idempotent`() {
        val epub = EpubFixture.writeValidEpub(tempFolder.newFile("reopen.epub"))
        val uri = Uri.fromFile(epub)

        val first = EpubParser.open(context, uri)
        val second = EpubParser.open(context, uri)

        assertEquals(first.title, second.title)
        assertEquals(first.chapterFiles.map(File::getPath), second.chapterFiles.map(File::getPath))
    }
}
