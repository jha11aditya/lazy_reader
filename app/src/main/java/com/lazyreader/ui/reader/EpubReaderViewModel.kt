package com.lazyreader.ui.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazyreader.data.AppDatabase
import com.lazyreader.epub.EpubBook
import com.lazyreader.epub.EpubParser
import com.lazyreader.voice.VoiceCommand
import com.lazyreader.voice.VoiceCommandClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EpubReaderUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val chapterCount: Int = 0,
    val chapterIndex: Int = 0,
    /** file:// URL of the current chapter for the WebView; null while loading. */
    val chapterUrl: String? = null,
    /** True when this chapter was entered by paging backward — open at its last page. */
    val openAtLastPage: Boolean = false,
    val voiceActive: Boolean = false,
    val locked: Boolean = false,
)

class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).recentDocumentDao()

    private var openUri: Uri? = null
    private var book: EpubBook? = null

    private val _uiState = MutableStateFlow(EpubReaderUiState())
    val uiState: StateFlow<EpubReaderUiState> = _uiState.asStateFlow()

    /**
     * Voice page-turn deltas (+1/-1). The screen resolves them against the
     * in-chapter page position (which only the WebView layer knows) and calls
     * [nextChapter]/[previousChapter] at chapter edges.
     */
    private val _pageDeltas = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val pageDeltas: SharedFlow<Int> = _pageDeltas.asSharedFlow()

    private var voiceClassifier: VoiceCommandClassifier? = null

    fun open(uri: Uri, startChapter: Int) {
        if (openUri == uri) return
        openUri = uri
        _uiState.value = EpubReaderUiState(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parsed = EpubParser.open(getApplication(), uri)
                book = parsed
                val chapter = startChapter.coerceIn(0, parsed.chapterFiles.lastIndex)
                _uiState.value = EpubReaderUiState(
                    isLoading = false,
                    chapterCount = parsed.chapterFiles.size,
                    chapterIndex = chapter,
                    // Uri.fromFile: proper file:// form + percent-encoding (File.toURI
                    // yields single-slash file:/ URLs WebView handles inconsistently).
                    chapterUrl = Uri.fromFile(parsed.chapterFiles[chapter]).toString(),
                )
            } catch (e: Exception) {
                openUri = null
                _uiState.value = EpubReaderUiState(
                    isLoading = false,
                    errorMessage = "Couldn't open this EPUB. (${e.message})",
                )
            }
        }
    }

    fun nextChapter() = goToChapter(_uiState.value.chapterIndex + 1, openAtLastPage = false)

    fun previousChapter() = goToChapter(_uiState.value.chapterIndex - 1, openAtLastPage = true)

    private fun goToChapter(index: Int, openAtLastPage: Boolean) {
        val chapters = book?.chapterFiles ?: return
        if (index !in chapters.indices) return
        _uiState.update {
            it.copy(
                chapterIndex = index,
                chapterUrl = Uri.fromFile(chapters[index]).toString(),
                openAtLastPage = openAtLastPage,
            )
        }
        persistProgress(index)
    }

    private fun persistProgress(chapterIndex: Int) {
        val uri = openUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateProgress(uri.toString(), chapterIndex, System.currentTimeMillis())
        }
    }

    /** Caller must ensure RECORD_AUDIO permission is granted first. */
    fun startVoiceControl() {
        if (voiceClassifier?.isRunning == true) return
        val classifier = voiceClassifier
            ?: VoiceCommandClassifier(getApplication(), ::onVoiceCommand)
                .also { voiceClassifier = it }
        try {
            classifier.start()
            _uiState.update { it.copy(voiceActive = true) }
        } catch (e: Exception) {
            android.util.Log.e("EpubReaderViewModel", "Failed to start voice control", e)
            _uiState.update { it.copy(voiceActive = false) }
        }
    }

    fun stopVoiceControl() {
        voiceClassifier?.stop()
        _uiState.update { it.copy(voiceActive = false) }
    }

    fun unlock() {
        _uiState.update { it.copy(locked = false) }
    }

    private fun onVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.GO -> _pageDeltas.tryEmit(+1)
            VoiceCommand.BACKWARD -> _pageDeltas.tryEmit(-1)
            VoiceCommand.STOP -> {
                stopVoiceControl()
                _uiState.update { it.copy(locked = true) }
            }
        }
    }

    override fun onCleared() {
        voiceClassifier?.stop()
    }
}
