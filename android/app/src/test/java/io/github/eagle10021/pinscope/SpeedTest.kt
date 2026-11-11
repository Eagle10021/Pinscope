package io.github.eagle10021.pinscope

import org.junit.Test
import java.io.File

// How many simulated seconds run per real second (a JVM on a laptop; phones are several times slower)
class SpeedTest {
    private val kit by lazy { KitData.parse(org.json.JSONObject(File("src/main/assets/pinscope.json").readText())) }
    private fun bench(label: String, hex: String, source: String?, seconds: Double) {
        val s = Simulator(kit, IntelHex.parse(File(hex).readText()), label)
        source?.let { SketchParts.infer(File(it).readText()).forEach { (t, p) -> s.addPart(createPart(t, p)) } }
        s.runCycles(CLOCK) // warm up
        val t0 = System.nanoTime()
        s.runCycles((CLOCK * seconds).toLong())
        val real = (System.nanoTime() - t0) / 1e9
        println("SPEED %-14s %.1f× real time".format(label, seconds / real))
    }
    @Test fun speed() {
        bench("PlotterDemo", "src/main/assets/sketches/PlotterDemo.hex", null, 5.0)
        bench("LCD custom", "src/main/assets/examples/lib-LiquidCrystal-CustomCharacter.hex", "src/main/assets/examples/lib-LiquidCrystal-CustomCharacter.ino", 5.0)
        bench("ServoSweep", "src/main/assets/sketches/ServoSweep.hex", "src/main/assets/sketches/ServoSweep.ino", 5.0)
    }
}
