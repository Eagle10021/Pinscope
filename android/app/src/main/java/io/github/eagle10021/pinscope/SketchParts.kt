package io.github.eagle10021.pinscope

// Works out which parts a sketch expects from its source, so the simulator can wire them up by itself.
// Reads pin constants (const int / #define / A0…, LED_BUILTIN), library declarations and pin calls.

object SketchParts {
    private const val ID = """[A-Za-z_]\w*"""

    fun infer(source: String): List<Pair<PartType, IntArray>> {
        val src = stripComments(source)
        val names = constants(src)
        fun pin(tok: String): Int? {
            val t = tok.trim().removeSuffix("u").removeSuffix("U")
            return when {
                t == "LED_BUILTIN" -> 13
                Regex("""A(\d{1,2})""").matches(t) -> 54 + t.drop(1).toInt()
                t.toIntOrNull() != null -> t.toInt()
                names.containsKey(t) -> pin(names.getValue(t))
                else -> null
            }?.takeIf { it in 0..69 }
        }
        fun pins(args: String) = args.split(',').map { pin(it) }

        val out = ArrayList<Pair<PartType, IntArray>>()
        val claimed = HashSet<Int>()
        fun add(t: PartType, p: List<Int?>) {
            if (p.any { it == null } || p.size != t.roles.size) return
            val arr = p.map { it!! }.toIntArray()
            if (arr.any { it in claimed }) return
            out += t to arr; claimed += arr.toList()
        }

        // libraries with their pins in the constructor
        Regex("""LiquidCrystal\s+$ID\s*\(([^)]*)\)""").find(src)?.let { m -> pins(m.groupValues[1]).let { if (it.size == 6) add(PartType.LCD1602, it) } }
        Regex("""DHT\s+$ID\s*\(\s*([^,]+),\s*DHT11""").find(src)?.let { add(PartType.DHT11, listOf(pin(it.groupValues[1]))) }
        Regex("""LedControl\s+$ID\s*=?\s*(?:LedControl)?\s*\(([^)]*)\)""").find(src)?.let { m ->
            val p = pins(m.groupValues[1]); if (p.size >= 3) add(PartType.MAX7219, listOf(p[0], p[2], p[1])) // (DIN, CLK, CS) → DIN, CS, CLK
        }
        Regex("""Stepper\s+$ID\s*\(\s*[^,]+,([^)]*)\)""").find(src)?.let { m -> pins(m.groupValues[1]).let { if (it.size == 4) add(PartType.STEPPER, it) } }
        (Regex("""IrReceiver\.begin\s*\(\s*([^,)]+)""").find(src) ?: Regex("""IRrecv\s+$ID\s*\(\s*([^)]+)\)""").find(src))?.let { add(PartType.IR, listOf(pin(it.groupValues[1]))) }
        if (Regex("""RTC_DS1307|DS1307RTC""").containsMatchIn(src)) add(PartType.RTC, listOf(20, 21))
        else if (Regex("""MPU6050|0x68|MPU_addr""").containsMatchIn(src) && src.contains("Wire")) add(PartType.MPU6050, listOf(20, 21))
        // Keypad: rowPins[4] = {…}; colPins[4] = {…}
        val rows = Regex("""rowPins\s*\[[^\]]*\]\s*=\s*\{([^}]*)\}""").find(src)?.let { pins(it.groupValues[1]) }
        val cols = Regex("""colPins\s*\[[^\]]*\]\s*=\s*\{([^}]*)\}""").find(src)?.let { pins(it.groupValues[1]) }
        if (rows != null && cols != null && rows.size == 4 && cols.size == 4) add(PartType.KEYPAD, rows + cols)

        // Servo: name.attach(pin)
        Regex("""(\w+)\.attach\s*\(\s*([^,)]+)""").findAll(src).forEach { m -> if (Regex("""Servo\s+[^;]*\b${m.groupValues[1]}\b""").containsMatchIn(src)) add(PartType.SERVO, listOf(pin(m.groupValues[2]))) }
        // HC-SR04: pulseIn on the echo pin, trigger is a pulsed output
        Regex("""pulseIn\s*\(\s*([^,]+),""").find(src)?.let { m ->
            val echo = pin(m.groupValues[1])
            val outs = Regex("""pinMode\s*\(\s*([^,]+),\s*OUTPUT""").findAll(src).map { it.groupValues[1].trim() }.toList()
            val trig = (outs.filter { Regex("trig", RegexOption.IGNORE_CASE).containsMatchIn(it) } + outs).map { pin(it) }
                .firstOrNull { it != null && it != echo && Regex("""delayMicroseconds""").containsMatchIn(src) }
            if (echo != null && trig != null) add(PartType.HCSR04, listOf(trig, echo))
        }
        // 74HC595: shiftOut(data, clock, …) with a latch output
        Regex("""shiftOut\s*\(\s*([^,]+),\s*([^,]+),""").find(src)?.let { m ->
            val data = pin(m.groupValues[1]); val clock = pin(m.groupValues[2])
            val latch = Regex("""pinMode\s*\(\s*([^,]+),\s*OUTPUT""").findAll(src).map { pin(it.groupValues[1]) }.firstOrNull { it != null && it != data && it != clock }
            if (data != null && clock != null && latch != null) add(PartType.SHIFT595, listOf(data, latch, clock))
        }
        // tone() → buzzer
        Regex("""\btone\s*\(\s*([^,]+),""").findAll(src).forEach { add(PartType.BUZZER, listOf(pin(it.groupValues[1]))) }

        // analog inputs → potentiometers (or the sensor the variable name suggests)
        Regex("""analogRead\s*\(\s*([^)]+)\)""").findAll(src).forEach { m ->
            val tok = m.groupValues[1].trim()
            val p = pin(tok) ?: return@forEach
            if (p < 54) return@forEach
            val hint = (tok + " " + names.entries.firstOrNull { it.key == tok }?.key.orEmpty()).lowercase()
            val t = when {
                Regex("photo|light|ldr").containsMatchIn(hint) -> PartType.PHOTO
                Regex("therm|temp").containsMatchIn(hint) -> PartType.THERMISTOR
                Regex("water|level").containsMatchIn(hint) -> PartType.WATER
                else -> PartType.POT
            }
            add(t, listOf(p))
        }
        // digital inputs → buttons; outputs → LEDs (PWM pins show brightness)
        Regex("""pinMode\s*\(\s*([^,]+),\s*(INPUT_PULLUP|INPUT|OUTPUT)\s*\)""").findAll(src).forEach { m ->
            val p = pin(m.groupValues[1]) ?: return@forEach
            if (p == 0 || p == 1) return@forEach
            if (m.groupValues[2] == "OUTPUT") add(if (Regex("buzz|beep|piezo|active", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[1])) PartType.BUZZER else PartType.LED, listOf(p))
            else {
                val hint = m.groupValues[1].lowercase()
                add(if (Regex("pir|motion").containsMatchIn(hint)) PartType.PIR else if (Regex("tilt").containsMatchIn(hint)) PartType.TILT else PartType.BUTTON, listOf(p))
            }
        }
        // digitalWrite/analogWrite on LED_BUILTIN or a pin never given a pinMode (analogWrite needs none)
        Regex("""analogWrite\s*\(\s*([^,]+),""").findAll(src).forEach { add(PartType.LED, listOf(pin(it.groupValues[1]))) }
        return out
    }

    // const int x = 9; int x = A0; #define X 13; byte pins… → name to value text
    private fun constants(src: String): Map<String, String> {
        val m = HashMap<String, String>()
        // also lists: const int rs = 12, en = 11, d4 = 5;
        Regex("""(?:const\s+)?(?:static\s+)?(?:unsigned\s+)?(?:int|byte|uint8_t|short|long|char)\s+([^;(){}]+);""").findAll(src).forEach { decl ->
            Regex("""($ID)\s*=\s*([A-Za-z_]?\w+)""").findAll(decl.groupValues[1]).forEach { m[it.groupValues[1]] = it.groupValues[2] }
        }
        Regex("""#define\s+($ID)\s+([A-Za-z_]?\w+)""").findAll(src).forEach { m[it.groupValues[1]] = it.groupValues[2] }
        return m
    }

    // A sketch can say exactly what to wire up, in a comment line such as
    //   // Simulator: rc 8 A0 1k 10u; scope A0 100ms; button 2 bouncy
    // Each entry is a part (its type name, or scope / rc / photo / pot…), its pins, then options:
    // a span like 5ms, rising / falling / free, a trigger level like 0.2V, a resistor like 10k, a capacitor like 100n or 10u, bouncy.
    class Hint(val type: PartType, val pins: IntArray, val opts: List<String>)

    fun hints(source: String): List<Hint> {
        val line = Regex("""//\s*Simulator:\s*(.+)""").find(source)?.groupValues?.get(1) ?: return emptyList()
        val names = constants(stripComments(source))
        fun pin(t: String): Int? = when {
            Regex("""A(\d{1,2})""").matches(t) -> 54 + t.drop(1).toInt()
            t.toIntOrNull() != null -> t.toInt()
            names.containsKey(t) -> pin(names.getValue(t))
            else -> null
        }?.takeIf { it in 0..69 }
        val alias = mapOf("photo" to PartType.PHOTO, "probe" to PartType.SCOPE, "ultrasonic" to PartType.HCSR04, "lcd" to PartType.LCD1602)
        return line.split(';').mapNotNull { entry ->
            val tok = entry.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (tok.isEmpty()) return@mapNotNull null
            val type = alias[tok[0].lowercase()] ?: PartType.entries.firstOrNull { it.name.equals(tok[0], true) } ?: return@mapNotNull null
            val pins = tok.drop(1).mapNotNull { pin(it) }
            if (pins.size != type.roles.size) return@mapNotNull null
            Hint(type, pins.toIntArray(), tok.drop(1).filter { pin(it) == null })
        }
    }

    // value with an SI suffix: 10k → 10000, 100n → 1e-7, 4.7u → 4.7e-6
    private fun si(t: String): Double? {
        val m = Regex("""(\d+(?:\.\d+)?)([pnumkM]?)""").matchEntire(t) ?: return null
        val mult = mapOf("p" to 1e-12, "n" to 1e-9, "u" to 1e-6, "m" to 1e-3, "k" to 1e3, "M" to 1e6, "" to 1.0).getValue(m.groupValues[2])
        return m.groupValues[1].toDouble() * mult
    }

    fun configure(p: SimPart, opts: List<String>) {
        for (o in opts) {
            when {
                o.endsWith("ms") && p is Scope -> o.dropLast(2).toFloatOrNull()?.let { p.spanMs = it }
                o == "rising" && p is Scope -> p.edge = 1
                o == "falling" && p is Scope -> p.edge = 2
                o == "free" && p is Scope -> p.edge = 0
                o == "bouncy" && p is Button -> p.bouncy = true
                o == "vcc" && p is Button -> p.toVcc = true
                o.endsWith("V") && p is Scope -> o.dropLast(1).toFloatOrNull()?.let { p.level = it.coerceIn(0f, 5f) }
                p is RcFilter -> si(o)?.let { v -> if (o.last() in "kM" || v >= 1) p.ohms = v else p.farads = v }
            }
        }
    }

    // Buttons on pins set to plain INPUT are wired the other way: to 5V with a pull-down, so pressed reads HIGH
    fun configureFromSource(p: SimPart, source: String) {
        if (p !is Button || p.type != PartType.BUTTON) return
        val src = stripComments(source); val names = constants(src)
        fun pin(t: String): Int? = t.trim().let { x -> when {
            Regex("""A(\d{1,2})""").matches(x) -> 54 + x.drop(1).toInt()
            x.toIntOrNull() != null -> x.toInt()
            names.containsKey(x) -> pin(names.getValue(x))
            else -> null } }
        val plain = Regex("""pinMode\s*\(\s*([^,]+),\s*INPUT\s*\)""").findAll(src).any { pin(it.groupValues[1]) == p.pins[0] }
        if (plain) p.toVcc = true
    }

    private fun stripComments(s: String) = s.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), "")
}
