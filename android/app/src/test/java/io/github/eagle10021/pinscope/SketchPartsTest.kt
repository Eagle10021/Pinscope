package io.github.eagle10021.pinscope

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SketchPartsTest {
    private fun parts(file: String) = SketchParts.infer(File(file).readText()).map { (t, p) -> t.name + p.joinToString(",", "(", ")") }

    @Test fun bundled() {
        assertEquals(listOf("LED(13)"), parts("src/main/assets/sketches/Blink.ino"))
        assertEquals(listOf("SERVO(9)"), parts("src/main/assets/sketches/ServoSweep.ino"))
        assertEquals(listOf("POT(54)"), parts("src/main/assets/sketches/AnalogReadSerial.ino"))
        assertEquals(listOf("BUTTON(2)", "LED(13)"), parts("src/main/assets/sketches/ButtonLed.ino"))
        assertEquals(listOf("LED(9)"), parts("src/main/assets/sketches/Fade.ino"))
        assertEquals(listOf("LCD1602(12,11,5,4,3,2)"), parts("src/main/assets/examples/lib-LiquidCrystal-Scroll.ino"))
    }

    @Test fun examplesOverview() {
        val idx = org.json.JSONArray(File("src/main/assets/examples/index.json").readText())
        var with = 0
        for (i in 0 until idx.length()) {
            val id = idx.getJSONObject(i).getString("id")
            val p = parts("src/main/assets/examples/$id.ino")
            if (p.isNotEmpty()) with++
            if (i % 9 == 0) println("%-55s %s".format(id, p))
        }
        println("examples with parts: $with of ${idx.length()}")
    }
}
