package io.github.eagle10021.pinscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Runs the bundled sketches' compiled code through the ATmega2560 emulator
class AvrTest {
    private fun load(name: String) = Avr(IntelHex.parse(File("src/main/assets/sketches/$name.hex").readText()))

    private fun run(avr: Avr, cycles: Long) { val end = avr.cycles + cycles; while (avr.cycles < end) avr.step() }

    @Test fun plotterDemoPrintsOverSerial() {
        val avr = load("PlotterDemo")
        val out = StringBuilder()
        avr.onSerial = { out.append(it.toChar()) }
        val t0 = System.nanoTime()
        run(avr, 16_000_000L) // one simulated second
        val ms = (System.nanoTime() - t0) / 1e6
        println("PlotterDemo: 1 s simulated in %.0f ms; output starts: %s".format(ms, out.take(80).toString().replace("\n", "|")))
        assertTrue("expected sine: lines, got '${out.take(200)}'", out.contains("sine:") && out.contains("cosine:"))
    }

    @Test fun blinkTogglesPin13EveryHalfSecond() {
        val avr = load("Blink")
        val edges = ArrayList<Long>()
        // pin 13 is PB7 (port index 1)
        avr.onPortWrite = { p, old, new -> if (p == 1 && ((old xor new) and 0x80) != 0) edges += avr.cycles }
        run(avr, 16_000_000L * 3)
        println("Blink edges (ms): " + edges.map { it / 16_000 })
        assertTrue("expected at least 4 toggles, got ${edges.size}", edges.size >= 4)
        val gaps = edges.zipWithNext { a, b -> (b - a) / 16_000.0 }
        gaps.forEach { assertEquals(500.0, it, 15.0) }
    }

    @Test fun analogReadSerialReportsTheAdcValue() {
        val avr = load("AnalogReadSerial")
        avr.analog[0] = 512
        val out = StringBuilder()
        avr.onSerial = { out.append(it.toChar()) }
        run(avr, 16_000_000L / 2)
        println("AnalogReadSerial: " + out.lines().take(3))
        assertTrue("got '${out.take(200)}'", out.contains("A0:512"))
    }

    @Test fun servoSweepMakesServoPulses() {
        val avr = load("ServoSweep")
        // pin 9 is PH6 (port index 7)
        var rise = 0L
        val widths = ArrayList<Double>()
        avr.onPortWrite = { p, old, new ->
            if (p == 7 && ((old xor new) and 0x40) != 0) {
                if (new and 0x40 != 0) rise = avr.cycles else if (rise > 0) widths += (avr.cycles - rise) / 16.0
            }
        }
        run(avr, 16_000_000L * 2)
        println("Servo pulses: ${widths.size}, µs from ${widths.minOrNull()} to ${widths.maxOrNull()}, first ${widths.take(5)}")
        assertTrue(widths.size > 50)
        assertTrue(widths.all { it in 500.0..2500.0 })
        assertTrue(widths.max() - widths.min() > 1000) // it sweeps
    }

    // The scope sketch's generator: Timer1 CTC toggling OC1A (pin 11) for the square wave
    @Test fun scopeSketchMakesSquareWaveOnPin11() {
        val avr = load("PinscopeScope")
        run(avr, 16_000_000L / 10)
        avr.serialIn("Q10000\n".toByteArray()) // 1 kHz
        val edges = ArrayList<Long>()
        // pin 11 is PB5 (port index 1)
        avr.onPortWrite = { p, old, new -> if (p == 1 && ((old xor new) and 0x20) != 0) edges += avr.cycles }
        run(avr, 16_000_000L / 10)
        val gaps = edges.drop(4).zipWithNext { a, b -> (b - a) / 16.0 }
        println("Square: ${edges.size} edges, half periods µs ${gaps.take(5)}")
        assertTrue(edges.size > 150)
        gaps.forEach { assertEquals(500.0, it, 5.0) }
    }
}
