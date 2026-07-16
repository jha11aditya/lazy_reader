package com.lazyreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lazyreader.epub.EpubFixture
import com.lazyreader.voice.VoiceCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubReaderViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newViewModel(): EpubReaderViewModel =
        EpubReaderViewModel(ApplicationProvider.getApplicationContext())

    private suspend fun EpubReaderViewModel.openFixtureAndAwaitLoaded(
        chapterTexts: List<String> = listOf("One", "Two", "Three"),
    ) {
        val epub = EpubFixture.writeValidEpub(
            tempFolder.newFile("book-${System.nanoTime()}.epub"),
            chapterTexts = chapterTexts,
        )
        open(Uri.fromFile(epub), startChapter = 0)
        // open() launches on the real Dispatchers.IO, not the test scheduler's
        // virtual time, so waiting for it needs a real (runBlocking) timeout —
        // withTimeout under runTest's virtual clock fires instantly instead of
        // actually waiting for that background work to finish.
        withTimeout(15_000) { uiState.first { !it.isLoading } }
    }

    @Test
    fun `open populates chapter state from the parsed book`() = runBlocking {
        val viewModel = newViewModel()

        viewModel.openFixtureAndAwaitLoaded(chapterTexts = listOf("One", "Two", "Three"))

        val state = viewModel.uiState.value
        assertEquals(3, state.chapterCount)
        assertEquals(0, state.chapterIndex)
        assertTrue(state.chapterUrl?.endsWith("chapter1.xhtml") == true)
    }

    @Test
    fun `nextChapter and previousChapter respect spine bounds`() = runBlocking {
        val viewModel = newViewModel()
        viewModel.openFixtureAndAwaitLoaded(chapterTexts = listOf("One", "Two"))

        viewModel.previousChapter() // already at index 0: no-op
        assertEquals(0, viewModel.uiState.value.chapterIndex)

        viewModel.nextChapter()
        assertEquals(1, viewModel.uiState.value.chapterIndex)
        assertFalse(viewModel.uiState.value.openAtLastPage)

        viewModel.nextChapter() // already at last chapter: no-op
        assertEquals(1, viewModel.uiState.value.chapterIndex)

        viewModel.previousChapter()
        assertEquals(0, viewModel.uiState.value.chapterIndex)
        assertTrue(viewModel.uiState.value.openAtLastPage)
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
    fun `go and backward emit deltas on pageDeltas`() = runTest {
        val viewModel = newViewModel()
        val received = mutableListOf<Int>()
        val job = launch { viewModel.pageDeltas.collect { received.add(it) } }
        runCurrent() // let the collector subscribe before we emit

        viewModel.onVoiceCommand(VoiceCommand.GO)
        runCurrent()
        viewModel.onVoiceCommand(VoiceCommand.BACKWARD)
        runCurrent()

        job.cancel()
        assertEquals(listOf(1, -1), received)
    }
}
