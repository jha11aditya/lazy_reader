package com.lazyreader.ui.dashboard

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lazyreader.data.AppDatabase
import com.lazyreader.data.RecentDocument
import com.lazyreader.data.RecentDocumentDao
import com.lazyreader.epub.EpubFixture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The PDF import path (needs a real PdfRenderer) is covered separately as an
 * instrumented test; this class covers the EPUB import path, which only
 * needs EpubParser (zip/XML, no native rendering).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var viewModel: DashboardViewModel
    private lateinit var dao: RecentDocumentDao

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        viewModel = DashboardViewModel(application)
        dao = AppDatabase.getInstance(application).recentDocumentDao()
    }

    @Test
    fun `importing a new epub creates a recent document with chapter count as total pages`() = runTest {
        val epub = EpubFixture.writeValidEpub(tempFolder.newFile("book.epub"), chapterTexts = listOf("One", "Two", "Three"))
        val uri = Uri.fromFile(epub)

        val returnedUri = viewModel.importDocument(uri)

        assertEquals(uri.toString(), returnedUri)
        val stored = dao.getByUri(uri.toString())
        assertEquals(RecentDocument.TYPE_EPUB, stored?.type)
        assertEquals(3, stored?.totalPages)
    }

    @Test
    fun `re-importing the same uri updates instead of duplicating`() = runTest {
        val epub = EpubFixture.writeValidEpub(tempFolder.newFile("book.epub"))
        val uri = Uri.fromFile(epub)

        viewModel.importDocument(uri)
        viewModel.importDocument(uri)

        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun `deleteDocument removes the row`() = runBlocking {
        // Real runBlocking (not runTest's virtual time): deleteDocument fires
        // on viewModelScope(Dispatchers.IO), a real background dispatcher, so
        // waiting for it needs a real wall-clock delay, not virtual time.
        val epub = EpubFixture.writeValidEpub(tempFolder.newFile("book.epub"))
        val uri = Uri.fromFile(epub)
        viewModel.importDocument(uri)
        val stored = requireNotNull(dao.getByUri(uri.toString()))

        viewModel.deleteDocument(stored)
        var attempts = 0
        while (dao.getByUri(uri.toString()) != null && attempts++ < 50) {
            delay(10)
        }

        assertNull(dao.getByUri(uri.toString()))
    }
}
