package com.lazyreader.voice

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-level only: driving real speech through the mic isn't automatable
 * here, so this exercises the real MediaPipe/AudioClassifier/AudioRecord
 * lifecycle (loading voice_commands.tflite, opening the mic, tearing down)
 * that Robolectric can't touch off-device. Command decisioning itself is
 * covered separately by the pure decideCommand unit tests.
 */
@RunWith(AndroidJUnit4::class)
class VoiceCommandClassifierInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private fun newClassifier(): VoiceCommandClassifier {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return VoiceCommandClassifier(context) { }
    }

    @Test
    fun startCreatesTheClassifierAndRecorder() {
        val classifier = newClassifier()
        assertFalse(classifier.isRunning)

        classifier.start()
        try {
            assertTrue(classifier.isRunning)
        } finally {
            classifier.stop()
        }
    }

    @Test
    fun stopTearsDownCleanly() {
        val classifier = newClassifier()
        classifier.start()

        classifier.stop()

        assertFalse(classifier.isRunning)
    }

    @Test
    fun startStopStartDoesNotCrashOrLeak() {
        val classifier = newClassifier()

        classifier.start()
        classifier.stop()
        classifier.start()
        try {
            assertTrue(classifier.isRunning)
        } finally {
            classifier.stop()
        }
    }
}
