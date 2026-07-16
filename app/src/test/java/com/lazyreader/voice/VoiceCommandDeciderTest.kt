package com.lazyreader.voice

import com.lazyreader.voice.VoiceCommandClassifier.Companion.decideCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandDeciderTest {

    @Test
    fun `score below threshold is ignored`() {
        assertNull(decideCommand("go", 0.69f, nowMs = 10_000, lastCommandAtMs = 0))
    }

    @Test
    fun `score at threshold is accepted`() {
        assertEquals(VoiceCommand.GO, decideCommand("go", 0.70f, nowMs = 10_000, lastCommandAtMs = 0))
    }

    @Test
    fun `score above threshold is accepted`() {
        assertEquals(VoiceCommand.GO, decideCommand("go", 0.99f, nowMs = 10_000, lastCommandAtMs = 0))
    }

    @Test
    fun `each category name maps to its command`() {
        assertEquals(VoiceCommand.GO, decideCommand("go", 0.9f, nowMs = 10_000, lastCommandAtMs = 0))
        assertEquals(VoiceCommand.BACKWARD, decideCommand("backward", 0.9f, nowMs = 10_000, lastCommandAtMs = 0))
        assertEquals(VoiceCommand.STOP, decideCommand("stop", 0.9f, nowMs = 10_000, lastCommandAtMs = 0))
    }

    @Test
    fun `background category is ignored`() {
        assertNull(decideCommand("background", 0.99f, nowMs = 10_000, lastCommandAtMs = 0))
    }

    @Test
    fun `unrecognized category is ignored`() {
        assertNull(decideCommand("unknown", 0.99f, nowMs = 10_000, lastCommandAtMs = 0))
    }

    @Test
    fun `null category is ignored`() {
        assertNull(decideCommand(null, 0.99f, nowMs = 10_000, lastCommandAtMs = 0))
    }

    @Test
    fun `repeat within debounce window is suppressed`() {
        assertNull(decideCommand("go", 0.9f, nowMs = 1_500, lastCommandAtMs = 1_000))
    }

    @Test
    fun `repeat exactly at debounce window is accepted`() {
        assertEquals(VoiceCommand.GO, decideCommand("go", 0.9f, nowMs = 2_000, lastCommandAtMs = 1_000))
    }

    @Test
    fun `repeat after debounce window is accepted`() {
        assertEquals(VoiceCommand.GO, decideCommand("go", 0.9f, nowMs = 5_000, lastCommandAtMs = 1_000))
    }
}
