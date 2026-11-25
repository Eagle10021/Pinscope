package io.github.eagle10021.pinscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuzzerSynthTest {
    @Test fun silentWithoutTones() {
        val buf = ShortArray(480)
        assertFalse(BuzzerSynth(48_000).fill(buf, FloatArray(0)))
        assertTrue(buf.all { it.toInt() == 0 })
    }

    @Test fun playsTheRequestedPitch() {
        val buf = ShortArray(48_000)
        assertTrue(BuzzerSynth(48_000).fill(buf, floatArrayOf(932f)))
        val cycles = (1 until buf.size).count { buf[it - 1] < 0 && buf[it] >= 0 }
        assertEquals(932.0, cycles.toDouble(), 2.0)
    }

    @Test fun fadesOutAfterTheNoteEnds() {
        val synth = BuzzerSynth(48_000)
        synth.fill(ShortArray(480), floatArrayOf(440f))
        synth.fill(ShortArray(480), floatArrayOf(0f))
        assertFalse(synth.fill(ShortArray(480), floatArrayOf(0f)))
    }
}
