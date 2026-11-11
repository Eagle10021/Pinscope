package io.github.eagle10021.pinscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The Quick start demo sketches, wired up from their "// Simulator:" lines, as the app does
class DemoSketchTest {
    private val kit by lazy { KitData.parse(org.json.JSONObject(File("src/main/assets/pinscope.json").readText())) }
    private fun demo(name: String): Simulator {
        val s = Simulator(kit, IntelHex.parse(File("src/main/assets/sketches/$name.hex").readText()), name)
        val src = File("src/main/assets/sketches/$name.ino").readText()
        val hints = SketchParts.hints(src)
        if (hints.isEmpty()) SketchParts.infer(src).forEach { (t, p) -> s.addPart(createPart(t, p)) }
        hints.forEach { h -> s.addPart(createPart(h.type, h.pins).also { SketchParts.configure(it, h.opts) }) }
        return s
    }
    private fun Simulator.seconds(x: Double) = runCycles((CLOCK * x).toLong())
    private fun freq(tr: FloatArray, spanMs: Float): Double {
        val mid = (tr.min() + tr.max()) / 2
        val ups = (1 until tr.size).filter { tr[it - 1] < mid && tr[it] >= mid }
        return (ups.size - 1) / ((ups.last() - ups.first()) * spanMs / 1000.0 / (tr.size - 1))
    }

    @Test fun rcCharge() {
        val s = demo("RcCharge")
        s.seconds(0.5)
        val rc = s.parts.filterIsInstance<RcFilter>().single()
        assertEquals(1000.0, rc.ohms, 0.0); assertEquals(10e-6, rc.farads, 1e-12)
        // the sketch's own readings of the capacitor: near 5 V at the end of a charge, near 0 at the end of a discharge
        val caps = Regex("""cap:([\d.]+)""").findAll(s.serialText()).map { it.groupValues[1].toDouble() }.toList()
        println("RcCharge: ${caps.size} readings, min ${caps.min()} max ${caps.max()}")
        assertTrue(caps.max() > 4.9 && caps.min() < 0.1)
        // one time constant after a rising edge the capacitor is at 63%
        val scope = s.parts.filterIsInstance<Scope>().first { it.pins[0] == 54 }
        val tr = scope.trace
        val i10 = tr.size / 4 + (tr.size - 1) / 10 // trigger is a quarter in; 100 ms span → 10 ms per tenth
        println("Scope on A0: triggered=${scope.triggered}, at +10 ms %.2f V".format(tr[i10]))
        assertTrue(scope.triggered)
        assertEquals(3.16, tr[i10].toDouble(), 0.25)
    }

    @Test fun signalGenerator() {
        val s = demo("SignalGenerator")
        s.seconds(0.6)
        val scopes = s.parts.filterIsInstance<Scope>().associateBy { it.pins[0] }
        val sq = freq(scopes.getValue(11).trace, 2f)
        println("SignalGenerator: pin 11 %.1f Hz; serial: %s".format(sq, s.serialText().lines().take(4)))
        assertEquals(1000.0, sq, 20.0)
        val pwm = scopes.getValue(9).trace
        val duty = pwm.count { it > 2.5f }.toDouble() / pwm.size
        println("pin 9 duty %.2f".format(duty))
        assertEquals(0.5, duty, 0.05)
        val sine = scopes.getValue(54).trace
        val f = freq(sine, 200f)
        println("A0 sine: %.2f–%.2f V, %.1f Hz".format(sine.min(), sine.max(), f))
        assertTrue(sine.max() > 4f && sine.min() < 1f)
        assertEquals(10.0, f, 1.0)
        // change it over serial: 5 kHz square
        s.send("s5000\n"); s.seconds(0.3)
        scopes.getValue(11).spanMs = 1f; s.seconds(0.1)
        val f2 = freq(scopes.getValue(11).trace, 1f)
        println("after s5000: %.0f Hz".format(f2))
        assertEquals(5000.0, f2, 300.0)
    }

    @Test fun buttonBounce() {
        val s = demo("ButtonBounce")
        s.seconds(0.2)
        val b = s.parts.filterIsInstance<Button>().single()
        assertTrue(b.bouncy)
        b.press(true); s.seconds(0.2); b.press(false); s.seconds(0.2)
        println("ButtonBounce: " + s.serialText().lines().filter { it.isNotBlank() })
        val m = Regex("""Pressed: (\d+) edges""").find(s.serialText())
        assertTrue(s.serialText(), m != null && m.groupValues[1].toInt() > 1)
    }

    @Test fun inferredDemos() {
        fun parts(n: String) = demo(n).also { it.seconds(0.01) }.parts.map { it.type.name + it.pins.joinToString(",", "(", ")") }
        assertEquals(listOf("PHOTO(54)"), parts("LightMeter"))
        assertEquals(listOf("HCSR04(12,11)"), parts("Distance"))
        assertEquals(listOf("DHT11(2)"), parts("Climate"))
    }
}
