package io.github.eagle10021.pinscope

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

// Plays the simulator's buzzers through the speakers.
// A passive buzzer sounds at the pitch the sketch drives it with; a pin held HIGH sounds like an active buzzer.

// Turns the pitches heard right now into samples: one soft square wave per buzzer.
class BuzzerSynth(private val rate: Int) {
    private val phase = DoubleArray(VOICES)
    private val pitch = FloatArray(VOICES)
    private val gain = FloatArray(VOICES)
    private var filtered = 0f

    // Fills the buffer and reports whether anything in it can be heard.
    fun fill(buf: ShortArray, tones: FloatArray): Boolean {
        val ramp = 1f / (rate * 0.003f) // 3 ms fade in and out, so notes do not click
        var audible = false
        for (i in buf.indices) {
            var mix = 0f
            for (v in 0 until VOICES) {
                val hz = tones.getOrElse(v) { 0f }
                if (hz > 0f) pitch[v] = hz // the last pitch is kept while the note fades out
                gain[v] = if (hz > 0f) minOf(1f, gain[v] + ramp) else maxOf(0f, gain[v] - ramp)
                if (gain[v] <= 0f) continue
                phase[v] = (phase[v] + pitch[v] / rate) % 1.0
                mix += (if (phase[v] < 0.5) LEVEL else -LEVEL) * gain[v]
                audible = true
            }
            filtered += (mix - filtered) * 0.55f // takes the harsh edge off the square wave
            buf[i] = (filtered.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return audible
    }

    companion object {
        const val VOICES = 4
        private const val LEVEL = 0.22f
    }
}

class BuzzerAudio(private val sim: Simulator) {
    @Volatile private var stopped = false

    fun start() {
        thread(name = "pinscope-audio", isDaemon = true) { runCatching { play() } }
    }

    fun stop() { stopped = true }

    private fun play() {
        val rate = 48_000
        val buf = ShortArray(rate / 100) // 10 ms
        val format = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, buf.size * 2 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val synth = BuzzerSynth(rate)
        var quiet = 0
        var playing = false
        try {
            while (!stopped && !sim.stopped) {
                val voices = sim.buzzers
                val tones = if (sim.running && !sim.muted) FloatArray(voices.size) { voices[it].sound } else FloatArray(0)
                quiet = if (synth.fill(buf, tones)) 0 else quiet + 1
                // the track idles after half a second of silence
                if (quiet < 50) {
                    if (!playing) { track.play(); playing = true }
                    track.write(buf, 0, buf.size)
                } else {
                    if (playing) { track.stop(); playing = false }
                    Thread.sleep(10)
                }
            }
        } finally {
            track.release()
        }
    }
}
