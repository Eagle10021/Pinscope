package io.github.eagle10021.pinscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Real kit-library sketches (compiled by the Arduino IDE's arduino-cli) running against the simulated parts
class PartsTest {
    private val kit by lazy { KitData.parse(org.json.JSONObject(File("src/main/assets/pinscope.json").readText())) }
    private fun sim(name: String) = Simulator(kit, IntelHex.parse(File("src/test/sketches/$name.hex").readText()), name)
    private fun <T : SimPart> Simulator.with(p: T): T { addPart(p); return p }
    private fun Simulator.seconds(s: Double) = runCycles((CLOCK * s).toLong())

    @Test fun dht11() {
        val s = sim("dht"); val d = s.with(Dht11(intArrayOf(2))); d.tempC = 23.4f; d.humidity = 51f
        s.seconds(5.0)
        println("DHT: " + s.serialText().lines().take(3))
        assertTrue(s.serialText(), s.serialText().contains("T:23.4 H:51"))
    }

    @Test fun ultrasonic() {
        val s = sim("sonar"); val u = s.with(Ultrasonic(intArrayOf(12, 11))); u.cm = 42f
        s.seconds(0.5)
        println("Sonar: " + s.serialText().lines().take(3))
        assertTrue(s.serialText(), s.serialText().contains("cm:42") || s.serialText().contains("cm:41"))
    }

    @Test fun lcd1602() {
        val s = sim("lcd"); val l = s.with(Lcd(intArrayOf(7, 8, 9, 10, 11, 12)))
        s.seconds(0.5)
        val l0 = l.line(0).joinToString("") { it.toChar().toString() }; val l1 = l.line(1).joinToString("") { it.toChar().toString() }
        println("LCD: [$l0] [$l1] on=${l.displayOn}")
        assertEquals("Hello, Pinscope ", l0); assertEquals("Line 2 OK       ", l1)
    }

    @Test fun rtc() {
        val s = sim("rtc"); s.with(Rtc(intArrayOf(20, 21)))
        s.seconds(1.0)
        println("RTC: " + s.serialText().lines().take(2))
        val now = java.time.LocalDate.now()
        assertTrue(s.serialText(), s.serialText().contains("Y:${now.year} M:${now.monthValue}"))
    }

    @Test fun irRemote() {
        val s = sim("ir"); val ir = s.with(IrRemote(intArrayOf(11)))
        s.seconds(0.3); ir.send("POWER", 0x45); s.seconds(0.3); ir.send("5", 0x1C); s.seconds(0.3)
        println("IR: " + s.serialText().lines().take(3))
        assertTrue(s.serialText(), s.serialText().contains("cmd:45") && s.serialText().contains("cmd:1C"))
    }

    @Test fun max7219() {
        val s = sim("matrix"); val m = s.with(Matrix(intArrayOf(12, 11, 10)))
        s.seconds(0.3)
        println("Matrix rows: " + m.rows.joinToString { Integer.toBinaryString(it).padStart(8, '0') } + " shutdown=${m.shutdown}")
        assertEquals(0b10100101, m.rows[0]); assertEquals(0b11110000, m.rows[7])
    }

    // pinMode(INPUT) with the button to 5V and a pull-down: inferred from the source, pressed reads HIGH
    @Test fun buttonToVccWithInput() {
        val s = sim("buttoninput")
        val src = File("src/test/sketches/buttoninput/buttoninput.ino").readText()
        SketchParts.infer(src).forEach { (t, p) -> s.addPart(createPart(t, p).also { SketchParts.configureFromSource(it, src) }) }
        s.runCycles(1)
        val b = s.parts.filterIsInstance<Button>().single(); val led = s.parts.filterIsInstance<Led>().single()
        assertTrue(b.toVcc)
        s.seconds(0.1); assertEquals(0f, led.bright[0])
        b.press(true); s.seconds(0.1); assertEquals(1f, led.bright[0])
        b.press(false); s.seconds(0.1); assertEquals(0f, led.bright[0])
        assertTrue(s.warnings.value.toString(), s.warnings.value.isEmpty())
        // the same button wired to GND leaves the input floating: a warning explains it
        b.wire(false); s.seconds(0.1)
        println("Warnings: " + s.warnings.value)
        assertTrue(s.warnings.value.any { it.contains("floats") })
    }
}
