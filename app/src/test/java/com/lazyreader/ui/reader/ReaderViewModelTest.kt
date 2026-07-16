package com.lazyreader.ui.reader

import androidx.test.core.app.ApplicationProvider
import com.lazyreader.voice.VoiceCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real PdfRenderer-backed open()/render behavior needs a device (no native
 * pdfium under Robolectric) — covered separately as an instrumented test.
 * This class covers computeTargetPage's pure bounds math plus the ViewModel
 * state transitions that don't require a rendered document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderViewModelTest {

    private fun newViewModel(): ReaderViewModel =
        ReaderViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun `computeTargetPage is a no-op at the first page going backward`() {
        assertNull(computeTargetPage(current = 0, pageCount = 5, delta = -1))
    }

    @Test
    fun `computeTargetPage is a no-op at the last page going forward`() {
        assertNull(computeTargetPage(current = 4, pageCount = 5, delta = +1))
    }

    @Test
    fun `computeTargetPage advances within bounds`() {
        assertEquals(3, computeTargetPage(current = 2, pageCount = 5, delta = +1))
        assertEquals(1, computeTargetPage(current = 2, pageCount = 5, delta = -1))
    }

    @Test
    fun `computeTargetPage returns null when there are no pages`() {
        assertNull(computeTargetPage(current = 0, pageCount = 0, delta = +1))
    }

    @Test
    fun `unlock clears the locked flag`() {
        val viewModel = newViewModel()
        viewModel.onVoiceCommand(VoiceCommand.STOP)
        assertTrue(viewModel.uiState.value.locked)

        viewModel.unlock()

        assertFalse(viewModel.uiState.value.locked)
    }

    @Test
    fun `stop command locks and stops voice without a real classifier`() {
        val viewModel = newViewModel()

        viewModel.onVoiceCommand(VoiceCommand.STOP)

        assertTrue(viewModel.uiState.value.locked)
        assertFalse(viewModel.uiState.value.voiceActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `go and backward before a document loads emit nothing`() = runTest {
        val viewModel = newViewModel()
        val received = mutableListOf<Int>()
        val job = launch { viewModel.pageRequests.collect { received.add(it) } }
        runCurrent() // let the collector subscribe first

        viewModel.onVoiceCommand(VoiceCommand.GO)
        viewModel.onVoiceCommand(VoiceCommand.BACKWARD)
        runCurrent()

        job.cancel()
        assertTrue(received.isEmpty())
    }
}
