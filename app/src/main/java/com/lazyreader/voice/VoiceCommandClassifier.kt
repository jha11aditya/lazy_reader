package com.lazyreader.voice

import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifierResult
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class VoiceCommand { GO, BACKWARD, STOP }

/**
 * Continuously listens to the microphone and emits [VoiceCommand]s using the
 * bundled keyword-spotting model (assets/voice_commands.tflite: 4 classes —
 * go, backward, stop, background — over 0.975s @ 16kHz mono windows).
 *
 * Caller must hold RECORD_AUDIO permission before [start].
 * [onCommand] is delivered on the main thread — never on MediaPipe's result
 * thread, so handlers may safely call [stop] (closing the classifier from its
 * own result callback deadlocks).
 */
class VoiceCommandClassifier(
    private val context: Context,
    private val onCommand: (VoiceCommand) -> Unit,
) {
    private var classifier: AudioClassifier? = null
    private var record: AudioRecord? = null
    private var executor: ScheduledThreadPoolExecutor? = null
    private var lastCommandAtMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // Sliding window: only ~CLASSIFY_INTERVAL_MS of new audio arrives per tick,
    // so a fresh buffer each tick would be mostly zeros — the model would see
    // truncated words (long words like "stop" suffer most). Instead keep the
    // last NUM_SAMPLES of real audio and slide new samples in.
    private val window = FloatArray(NUM_SAMPLES)
    private val readBuf = FloatArray(NUM_SAMPLES)
    private var stalledTicks = 0

    val isRunning: Boolean get() = classifier != null

    fun start() {
        if (classifier != null) return
        window.fill(0f)
        stalledTicks = 0
        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.AUDIO_STREAM)
            // Threshold applied in onResult, not here: keeps all scores visible
            // for logging/tuning, and lets v2's sensitivity slider adjust live.
            .setResultListener(this::onResult)
            .setErrorListener { e -> Log.e(TAG, "Voice classification error", e) }
            .build()
        val created = AudioClassifier.createFromOptions(context, options)
        classifier = created
        record = created.createAudioRecord(1, SAMPLE_RATE, BUFFER_SIZE_BYTES)
            .also { it.startRecording() }
        executor = ScheduledThreadPoolExecutor(1).also {
            it.scheduleWithFixedDelay(
                ::classifyCurrentWindow, 0, CLASSIFY_INTERVAL_MS, TimeUnit.MILLISECONDS,
            )
        }
        Log.i(TAG, "Voice classification started")
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        record?.let {
            it.stop()
            it.release()
        }
        record = null
        classifier?.close()
        classifier = null
        Log.i(TAG, "Voice classification stopped")
    }

    private fun classifyCurrentWindow() {
        val record = record ?: return
        try {
            var total = 0
            while (total < readBuf.size) {
                val n = record.read(readBuf, total, readBuf.size - total, AudioRecord.READ_NON_BLOCKING)
                if (n <= 0) break
                total += n
            }
            if (total == 0) {
                // Some devices' AudioRecord silently stops delivering samples
                // (observed on Nokia C21 Plus); restart instead of endlessly
                // re-classifying a stale window.
                if (++stalledTicks >= STALL_RESTART_TICKS) {
                    stalledTicks = 0
                    Log.w(TAG, "AudioRecord stalled, restarting")
                    record.stop()
                    record.startRecording()
                }
                return
            }
            stalledTicks = 0
            System.arraycopy(window, total, window, 0, NUM_SAMPLES - total)
            System.arraycopy(readBuf, 0, window, NUM_SAMPLES - total, total)

            val audioData = AudioData.create(
                AudioData.AudioDataFormat.create(record.format), NUM_SAMPLES,
            )
            audioData.load(window)
            classifier?.classifyAsync(audioData, SystemClock.uptimeMillis())
        } catch (t: Throwable) {
            // Catch everything: an uncaught throwable would silently cancel
            // scheduleWithFixedDelay's future runs and kill voice control.
            Log.e(TAG, "Failed to classify audio window", t)
        }
    }

    private fun onResult(result: AudioClassifierResult) {
        val categories = result.classificationResults().firstOrNull()
            ?.classifications()?.firstOrNull()
            ?.categories()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        Log.d(TAG, categories.joinToString { "${it.categoryName()}=%.2f".format(it.score()) })
        val top = categories.maxByOrNull { it.score() } ?: return
        if (top.score() < SCORE_THRESHOLD) return
        val command = when (top.categoryName()) {
            "go" -> VoiceCommand.GO
            "backward" -> VoiceCommand.BACKWARD
            "stop" -> VoiceCommand.STOP
            else -> return // background
        }
        // Overlapping windows re-detect the same utterance; suppress repeats.
        val now = SystemClock.uptimeMillis()
        if (now - lastCommandAtMs < DEBOUNCE_MS) return
        lastCommandAtMs = now
        mainHandler.post { onCommand(command) }
    }

    private companion object {
        const val TAG = "VoiceCommandClassifier"
        const val MODEL_ASSET = "voice_commands.tflite"
        const val SAMPLE_RATE = 16000
        // Model input is fixed at 0.975s @ 16kHz.
        const val NUM_SAMPLES = 15600
        // Float samples, double-buffered so the recorder never overruns.
        const val BUFFER_SIZE_BYTES = NUM_SAMPLES * Float.SIZE_BYTES * 2
        // ~50% window overlap so a word straddling a window edge is still caught.
        const val CLASSIFY_INTERVAL_MS = 500L
        const val DEBOUNCE_MS = 1000L
        const val SCORE_THRESHOLD = 0.7f
        // Ticks with zero samples read before the recorder is restarted.
        const val STALL_RESTART_TICKS = 3
    }
}
