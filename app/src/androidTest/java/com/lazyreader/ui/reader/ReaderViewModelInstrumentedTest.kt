package com.lazyreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lazyreader.pdf.PdfFixture
import com.lazyreader.voice.VoiceCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Real PdfRenderer-backed coverage that Robolectric can't provide (no native
 * pdfium off-device) — pairs with the pure computeTargetPage clamping tests
 * in the JVM suite (ReaderViewModelTest).
 */
@RunWith(AndroidJUnit4::class)
class ReaderViewModelInstrumentedTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newViewModel(): ReaderViewModel {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        return ReaderViewModel(app)
    }

    private suspend fun ReaderViewModel.openFixtureAndAwaitLoaded(pageCount: Int) {
        val pdf = PdfFixture.writeMinimalPdf(tempFolder.newFile("book-${System.nanoTime()}.pdf"), pageCount)
        open(Uri.fromFile(pdf), startPage = 0)
        // isLoading flips false before the page-0 render (kicked off in the same
        // coroutine right after) finishes, so wait for the bitmap too.
        withTimeout(15_000) { uiState.first { !it.isLoading && it.bitmaps.containsKey(0) } }
    }

    @Test
    fun openReadsRealPageCountFromPdfRenderer() = runBlocking {
        val viewModel = newViewModel()

        viewModel.openFixtureAndAwaitLoaded(pageCount = 3)

        val state = viewModel.uiState.value
        assertEquals(3, state.pageCount)
        assertEquals(0, state.currentPage)
        assertTrue(state.bitmaps.containsKey(0))
    }

    @Test
    fun onPageChangedRendersWindowAndRecyclesOutsideIt() = runBlocking {
        val viewModel = newViewModel()
        viewModel.openFixtureAndAwaitLoaded(pageCount = 5)

        viewModel.onPageChanged(2)
        // The render window (pages 1..3) fills in one page at a time, each
        // triggering its own uiState update, so wait for the FULL expected
        // window rather than just the center page — otherwise this races
        // ahead of the later pages rendering and the stale-page cleanup.
        withTimeout(15_000) {
            while (viewModel.uiState.value.bitmaps.keys != setOf(1, 2, 3)) delay(20)
        }

        val bitmaps = viewModel.uiState.value.bitmaps
        assertTrue(bitmaps.containsKey(1))
        assertTrue(bitmaps.containsKey(2))
        assertTrue(bitmaps.containsKey(3))
        assertTrue(!bitmaps.containsKey(0))
        assertTrue(!bitmaps.containsKey(4))
    }

    @Test
    fun voiceGoAdvancesOnePageWithRealBounds() = runBlocking {
        val viewModel = newViewModel()
        viewModel.openFixtureAndAwaitLoaded(pageCount = 3)

        val received = mutableListOf<Int>()
        val job = launch { viewModel.pageRequests.collect { received.add(it) } }
        // pageRequests is a hot SharedFlow (replay = 0); give the collector a
        // moment to actually subscribe before the command fires.
        delay(50)

        viewModel.onVoiceCommand(VoiceCommand.GO)
        withTimeout(5_000) {
            while (received.isEmpty()) delay(20)
        }

        job.cancel()
        assertEquals(listOf(1), received)
    }
}
