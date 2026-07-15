package com.lazyreader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazyreader.data.AppDatabase
import com.lazyreader.voice.VoiceCommand
import com.lazyreader.voice.VoiceCommandClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReaderUiState(
    val isLoading: Boolean = true,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val bitmaps: Map<Int, Bitmap> = emptyMap(),
    val errorMessage: String? = null,
    val voiceActive: Boolean = false,
    val locked: Boolean = false,
)

/**
 * Only renders the current page +/- 1 and recycles bitmaps outside that window,
 * since PDFs can run to hundreds of pages and each rendered page can be several MB.
 */
private const val RENDER_WINDOW = 1
private const val RENDER_SCALE = 2f

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).recentDocumentDao()
    private val renderMutex = Mutex()

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var openUri: Uri? = null
    private var renderJob: Job? = null

    private val bitmapCache = LinkedHashMap<Int, Bitmap>()

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** Voice-initiated page targets; the screen animates the pager to these. */
    private val _pageRequests = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val pageRequests: SharedFlow<Int> = _pageRequests.asSharedFlow()

    private var voiceClassifier: VoiceCommandClassifier? = null

    fun open(uri: Uri, startPage: Int) {
        if (openUri == uri) return
        openUri = uri
        _uiState.value = ReaderUiState(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            var openFailure: Exception? = null
            val pageCount = try {
                renderMutex.withLock {
                    closeRendererLocked()
                    val pfd = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
                    if (pfd == null) {
                        null
                    } else {
                        fileDescriptor = pfd
                        pdfRenderer = PdfRenderer(pfd)
                        pdfRenderer?.pageCount
                    }
                }
            } catch (e: Exception) {
                openFailure = e
                null
            }

            if (openFailure != null) openUri = null

            if (pageCount == null) {
                val message = if (openFailure is SecurityException) {
                    "Lost access to this file. Please re-import it from the dashboard."
                } else {
                    "Couldn't open this PDF."
                }
                _uiState.value = ReaderUiState(isLoading = false, errorMessage = message)
                return@launch
            }

            val initialPage = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            _uiState.value = ReaderUiState(isLoading = false, pageCount = pageCount, currentPage = initialPage)
            if (pageCount > 0) renderWindow(initialPage)
        }
    }

    fun onPageChanged(page: Int) {
        _uiState.update { it.copy(currentPage = page) }
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.IO) {
            renderWindow(page)
            openUri?.let { uri -> dao.updateProgress(uri.toString(), page, System.currentTimeMillis()) }
        }
    }

    private suspend fun renderWindow(centerPage: Int) {
        val wanted = (centerPage - RENDER_WINDOW..centerPage + RENDER_WINDOW).filter { it >= 0 }
        for (page in wanted) {
            if (!bitmapCache.containsKey(page)) {
                val bitmap = renderPage(page) ?: continue
                bitmapCache[page] = bitmap
                _uiState.update { it.copy(bitmaps = bitmapCache.toMap()) }
            }
        }
        val stale = bitmapCache.keys.filter { it !in wanted }
        if (stale.isNotEmpty()) {
            stale.forEach { bitmapCache.remove(it)?.recycle() }
            _uiState.update { it.copy(bitmaps = bitmapCache.toMap()) }
        }
    }

    private suspend fun renderPage(index: Int): Bitmap? = renderMutex.withLock {
        val renderer = pdfRenderer ?: return@withLock null
        if (index !in 0 until renderer.pageCount) return@withLock null
        val page = renderer.openPage(index)
        try {
            val bitmap = Bitmap.createBitmap(
                (page.width * RENDER_SCALE).toInt().coerceAtLeast(1),
                (page.height * RENDER_SCALE).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
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
            android.util.Log.e("ReaderViewModel", "Failed to start voice control", e)
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
            VoiceCommand.GO -> requestPageDelta(+1)
            VoiceCommand.BACKWARD -> requestPageDelta(-1)
            VoiceCommand.STOP -> {
                stopVoiceControl()
                _uiState.update { it.copy(locked = true) }
            }
        }
    }

    private fun requestPageDelta(delta: Int) {
        val state = _uiState.value
        if (state.pageCount == 0) return
        val target = (state.currentPage + delta).coerceIn(0, state.pageCount - 1)
        if (target != state.currentPage) _pageRequests.tryEmit(target)
    }

    private fun closeRendererLocked() {
        bitmapCache.values.forEach { it.recycle() }
        bitmapCache.clear()
        pdfRenderer?.close()
        fileDescriptor?.close()
        pdfRenderer = null
        fileDescriptor = null
    }

    override fun onCleared() {
        voiceClassifier?.stop()
        renderJob?.cancel()
        runBlocking { renderMutex.withLock { closeRendererLocked() } }
    }
}
