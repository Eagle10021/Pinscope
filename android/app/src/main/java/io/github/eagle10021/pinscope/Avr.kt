package io.github.eagle10021.pinscope

// An ATmega2560 (the Arduino Mega's chip) in software: the AVR instruction set, 256 KB flash, 8 KB SRAM,
// and the peripherals Arduino sketches use: GPIO ports A–L, timers 0–5, ADC, USART0, external
// interrupts, EEPROM, and minimal TWI/SPI so Wire and SPI calls return instead of hanging.
// Addresses and vector numbers follow the ATmega640/1280/2560 datasheet.

private const val K_UNKNOWN = 0; private const val K_NOP = 1; private const val K_MOVW = 2; private const val K_MULS = 3; private const val K_MULSU = 4
private const val K_FMUL = 5; private const val K_FMULS = 6; private const val K_FMULSU = 7; private const val K_CPC = 8; private const val K_SBC = 9
private const val K_ADD = 10; private const val K_CPSE = 11; private const val K_CP = 12; private const val K_SUB = 13; private const val K_ADC = 14
private const val K_AND = 15; private const val K_EOR = 16; private const val K_OR = 17; private const val K_MOV = 18; private const val K_CPI = 19
private const val K_SBCI = 20; private const val K_SUBI = 21; private const val K_ORI = 22; private const val K_ANDI = 23; private const val K_LDI = 24
private const val K_LDD = 25; private const val K_STD = 26; private const val K_LDS = 27; private const val K_STS = 28; private const val K_LDZP = 29
private const val K_LDZM = 30; private const val K_LDYP = 31; private const val K_LDYM = 32; private const val K_LDX = 33; private const val K_LDXP = 34
private const val K_LDXM = 35; private const val K_STZP = 36; private const val K_STZM = 37; private const val K_STYP = 38; private const val K_STYM = 39
private const val K_STX = 40; private const val K_STXP = 41; private const val K_STXM = 42; private const val K_POP = 43; private const val K_PUSH = 44
private const val K_LPM = 45; private const val K_LPMP = 46; private const val K_ELPM = 47; private const val K_ELPMP = 48; private const val K_LPM0 = 49
private const val K_ELPM0 = 50; private const val K_IJMP = 51; private const val K_EIJMP = 52; private const val K_ICALL = 53; private const val K_EICALL = 54
private const val K_RET = 55; private const val K_RETI = 56; private const val K_BSET = 57; private const val K_BCLR = 58; private const val K_JMP = 59
private const val K_CALL = 60; private const val K_COM = 61; private const val K_NEG = 62; private const val K_SWAP = 63; private const val K_INC = 64
private const val K_DEC = 65; private const val K_ASR = 66; private const val K_LSR = 67; private const val K_ROR = 68; private const val K_ADIW = 69
private const val K_SBIW = 70; private const val K_CBI = 71; private const val K_SBI = 72; private const val K_SBIC = 73; private const val K_SBIS = 74
private const val K_MUL = 75; private const val K_IN = 76; private const val K_OUT = 77; private const val K_RJMP = 78; private const val K_RCALL = 79
private const val K_BRBS = 80; private const val K_BRBC = 81; private const val K_BLD = 82; private const val K_BST = 83; private const val K_SBRC = 84
private const val K_SBRS = 85

private val PS = intArrayOf(0, 1, 8, 64, 256, 1024, 0, 0)
private val PS2 = intArrayOf(0, 1, 8, 32, 64, 128, 256, 1024)

class Avr(image: ByteArray) {
    val flash = ByteArray(256 * 1024).also { image.copyInto(it, 0, 0, minOf(image.size, it.size)) }
    val data = IntArray(0x2200)
    val eeprom = IntArray(4096) { 0xFF }
    var pc = 0              // word address
    var cycles = 0L
    var sreg = 0

    // ---------- outside world ----------
    var onSerial: ((Int) -> Unit)? = null           // a byte sent by the sketch on Serial (USART0)
    var onPortWrite: ((port: Int, old: Int, new: Int) -> Unit)? = null
    private val rx = ArrayDeque<Int>()
    fun serialIn(bytes: ByteArray) { synchronized(rx) { bytes.forEach { rx.addLast(it.toInt() and 0xFF) } }; irqDue = true }

    // Levels driven onto input pins by virtual parts: per port, a mask of driven bits and their levels
    val extDriven = IntArray(11)
    val extLevel = IntArray(11)
    val analog = IntArray(16)               // ADC inputs, 0–1023

    // ---------- GPIO ----------
    // Ports A..L (no I): PINx, DDRx, PORTx addresses
    val portBase = intArrayOf(0x20, 0x23, 0x26, 0x29, 0x2C, 0x2F, 0x32, 0x100, 0x103, 0x106, 0x109)
    fun ddr(p: Int) = data[portBase[p] + 1]
    fun port(p: Int) = data[portBase[p] + 2]
    fun pinLevels(p: Int): Int {
        val d = ddr(p); val o = port(p)
        val inputs = (extDriven[p] and extLevel[p]) or (o and extDriven[p].inv()) // undriven inputs follow the pull-up
        return ((o and d) or (inputs and d.inv())) and 0xFF
    }
    private val portOf = IntArray(0x200) { a -> portBase.indexOfFirst { a >= it && a < it + 3 } }
    private fun portIndex(addr: Int): Int = if (addr in 0 until 0x200) portOf[addr] else -1

    // ---------- timers ----------
    private inner class Timer(
        val n: Int, val wide: Boolean, val tccrA: Int, val tcnt: Int, val ocr: IntArray, val icr: Int,
        val timsk: Int, val tifr: Int, val vCapt: Int, val vComp: IntArray, val vOvf: Int,
    ) {
        val tccrB = tccrA + 1
        var acc = 0L
        var down = false
        fun prescale(): Int {
            val cs = data[tccrB] and 7
            return if (n == 2) PS2[cs] else PS[cs]
        }
        fun wgm(): Int = if (wide) (data[tccrA] and 3) or (((data[tccrB] shr 3) and 3) shl 2) else (data[tccrA] and 3) or (((data[tccrB] shr 3) and 1) shl 2)
        fun r16(a: Int) = if (wide) data[a] or (data[a + 1] shl 8) else data[a]
        fun w16(a: Int, v: Int) { data[a] = v and 0xFF; if (wide) data[a + 1] = (v shr 8) and 0xFF }
        fun top(m: Int = wgm()): Int =
            if (!wide) when (m) { 2, 5, 7 -> data[ocr[0]]; else -> 0xFF }
            else when (m) {
                0 -> 0xFFFF; 1, 5 -> 0xFF; 2, 6 -> 0x1FF; 3, 7 -> 0x3FF
                4, 9, 11, 15 -> r16(ocr[0]); 8, 10, 12, 14 -> r16(icr); else -> 0xFFFF
            }
        fun dual(m: Int) = if (!wide) m == 1 || m == 5 else m in 1..3 || m in 8..11
        fun ctc(m: Int) = if (!wide) m == 2 else m == 4 || m == 12

        fun step(c: Int) {
            val ps = prescale(); if (ps == 0) return
            acc += c
            if (acc < ps) return
            var ticks = (acc / ps).toInt(); acc %= ps
            val m = wgm()
            val top = top(m).coerceAtLeast(1)
            val isDual = dual(m); val isCtc = ctc(m)
            nonPwm = m == 0 || isCtc
            val max = if (wide) 0xFFFF else 0xFF
            var t = r16(tcnt)
            while (ticks > 0) {
                if (!isDual) {
                    // count up: skip straight to just before the next compare value or the end of the count
                    val wrapAt = if (isCtc || m != 0) top else max
                    var nextEv = wrapAt
                    for (i in ocr.indices) { val o = r16(ocr[i]); if (o > t && o < nextEv) nextEv = o }
                    val run = nextEv - t
                    if (run > 0 && ticks <= run) { t += ticks; ticks = 0; for (i in ocr.indices) if (t == r16(ocr[i])) match(i); break }
                    if (run > 0) { t = nextEv; ticks -= run; for (i in ocr.indices) if (t == r16(ocr[i])) match(i); continue }
                    // at the end of the count: wrap
                    t = 0; ticks--
                    if (!isCtc) data[tifr] = data[tifr] or 1
                    for (i in ocr.indices) if (r16(ocr[i]) == 0) match(i)
                } else if (data[timsk] == 0) {
                    // no interrupts on this timer: jump the up/down count forward in one go
                    val period = 2 * top
                    val phase0 = if (down) period - t else t
                    val total = phase0 + ticks
                    if (total >= period) data[tifr] = data[tifr] or 1
                    val phase = total % period
                    if (phase <= top) { t = phase; down = false } else { t = period - phase; down = true }
                    if (t >= top) down = true
                    ticks = 0
                } else {
                    if (!down) { t++; if (t >= top) { t = top; down = true } }
                    else { t--; if (t <= 0) { t = 0; down = false; data[tifr] = data[tifr] or 1 } }
                    for (i in ocr.indices) if (t == r16(ocr[i])) match(i)
                    ticks--
                }
            }
            w16(tcnt, t)
        }

        // a compare match: set the flag, and in normal/CTC modes with COM = 01, toggle the OCnx pin
        var nonPwm = false
        fun match(i: Int) {
            data[tifr] = data[tifr] or (2 shl i)
            if (nonPwm && (data[tccrA] shr (6 - 2 * i)) and 3 == 1) {
                val (port, bit) = ocPin[n][i]
                if (port >= 0 && (data[portBase[port] + 1] shr bit) and 1 == 1) write(portBase[port], 1 shl bit)
            }
        }

        fun pending(): Int {
            val f = data[tifr] and data[timsk]
            if (f == 0) return -1
            for (i in ocr.indices) if (f and (2 shl i) != 0) return vComp[i]
            if (f and 1 != 0) return vOvf
            if (f and 0x20 != 0 && vCapt > 0) return vCapt
            return -1
        }
        fun clear(v: Int) {
            for (i in ocr.indices) if (v == vComp[i]) data[tifr] = data[tifr] and (2 shl i).inv()
            if (v == vOvf) data[tifr] = data[tifr] and 1.inv()
            if (v == vCapt) data[tifr] = data[tifr] and 0x20.inv()
        }
        // Duty (0–255) on a compare output when its COM bits are set, else -1
        fun duty(ch: Int): Int {
            val com = (data[tccrA] shr (6 - 2 * ch)) and 3
            if (com == 0) return -1
            val m0 = wgm(); if (m0 == 0 || ctc(m0)) return -1 // normal/CTC modes toggle the pin instead
            val top = top().coerceAtLeast(1)
            val v = (r16(ocr[ch]).toLong() * 255 / top).toInt().coerceIn(0, 255)
            return if (com == 3) 255 - v else v
        }
        fun ownsTifr(a: Int) = a == tifr
        // CPU cycles per PWM period, 0 when stopped
        fun period(): Int {
            val ps = prescale(); if (ps == 0) return 0
            val m = wgm(); val top = top(m).coerceAtLeast(1)
            return ps * (if (dual(m)) 2 * top else top + 1)
        }
        fun isTimerReg(a: Int) = a in tccrA..tccrA + 0x0D && (wide || a <= tccrA + 4)
    }

    // OCnA/B/C pins as (port index, bit), per timer
    private val ocPin = arrayOf(
        arrayOf(1 to 7, 6 to 5, -1 to 0),          // OC0A PB7 (13), OC0B PG5 (4)
        arrayOf(1 to 5, 1 to 6, 1 to 7),           // OC1A PB5 (11), OC1B PB6 (12), OC1C PB7 (13)
        arrayOf(1 to 4, 7 to 6, -1 to 0),          // OC2A PB4 (10), OC2B PH6 (9)
        arrayOf(4 to 3, 4 to 4, 4 to 5),           // OC3A PE3 (5), OC3B PE4 (2), OC3C PE5 (3)
        arrayOf(7 to 3, 7 to 4, 7 to 5),           // OC4A PH3 (6), OC4B PH4 (7), OC4C PH5 (8)
        arrayOf(10 to 3, 10 to 4, 10 to 5),        // OC5A PL3 (46), OC5B PL4 (45), OC5C PL5 (44)
    )

    private val timers = arrayOf(
        Timer(0, false, 0x44, 0x46, intArrayOf(0x47, 0x48), 0, 0x6E, 0x35, 0, intArrayOf(21, 22), 23),
        Timer(1, true, 0x80, 0x84, intArrayOf(0x88, 0x8A, 0x8C), 0x86, 0x6F, 0x36, 16, intArrayOf(17, 18, 19), 20),
        Timer(2, false, 0xB0, 0xB2, intArrayOf(0xB3, 0xB4), 0, 0x70, 0x37, 0, intArrayOf(13, 14), 15),
        Timer(3, true, 0x90, 0x94, intArrayOf(0x98, 0x9A, 0x9C), 0x96, 0x71, 0x38, 31, intArrayOf(32, 33, 34), 35),
        Timer(4, true, 0xA0, 0xA4, intArrayOf(0xA8, 0xAA, 0xAC), 0xA6, 0x72, 0x39, 41, intArrayOf(42, 43, 44), 45),
        Timer(5, true, 0x120, 0x124, intArrayOf(0x128, 0x12A, 0x12C), 0x126, 0x73, 0x3A, 46, intArrayOf(47, 48, 49), 50),
    )
    fun pwmDuty(timer: Int, ch: Int) = timers[timer].duty(ch)
    fun pwmPeriod(timer: Int) = timers[timer].period()
    // Hooks for analog parts: called just before a conversion reads analog[ch], and after a sketch changes timer settings
    var beforeAdc: ((Int) -> Unit)? = null
    var onTimerWrite: (() -> Unit)? = null
    fun catchUp() = sync()

    // ---------- ADC, USART, EEPROM, TWI ----------
    private var adcLeft = -1
    private fun adcStart() {
        val ps = 1 shl (data[0x7A] and 7).coerceAtLeast(1)
        adcLeft = 13 * ps
    }
    private fun adcDone() {
        val ch = (data[0x7C] and 7) or (if (data[0x7B] and 8 != 0) 8 else 0)
        beforeAdc?.invoke(ch)
        val v = analog[ch].coerceIn(0, 1023)
        if (data[0x7C] and 0x20 != 0) { data[0x78] = (v shl 6) and 0xC0; data[0x79] = v shr 2 } else { data[0x78] = v and 0xFF; data[0x79] = v shr 8 }
        data[0x7A] = (data[0x7A] and 0x40.inv()) or 0x10 // ADSC off, ADIF on
        adcLeft = -1
    }
    // ---------- TWI (I2C) master, talking to simulated devices ----------
    interface TwiBus {
        fun address(addr: Int, read: Boolean): Boolean   // true = a device answered (ACK)
        fun write(b: Int): Boolean
        fun read(ack: Boolean): Int
        fun stop()
    }
    var twiBus: TwiBus? = null
    private var twiState = 0xF8
    private var twint = false
    private var twiAction = 0
    private var twiDelay = -1
    private var twiActive = false
    private var twiExpectAddr = false
    private var twiReading = false
    private fun twiDo(v: Int) {
        val bus = twiBus
        when {
            v and 0x20 != 0 -> { twiState = if (twiActive) 0x10 else 0x08; twiActive = true; twiExpectAddr = true; twint = true }
            v and 0x10 != 0 -> { bus?.stop(); twiActive = false; twiState = 0xF8; data[0xBC] = data[0xBC] and 0x10.inv() }
            twiExpectAddr -> {
                val a = data[0xBB]; twiReading = a and 1 != 0
                val ack = bus?.address(a shr 1, twiReading) ?: false
                twiState = if (twiReading) (if (ack) 0x40 else 0x48) else (if (ack) 0x18 else 0x20)
                twiExpectAddr = false; twint = true
            }
            !twiReading -> { twiState = if (bus?.write(data[0xBB]) == true) 0x28 else 0x30; twint = true }
            else -> { val ack = v and 0x40 != 0; data[0xBB] = bus?.read(ack) ?: 0xFF; twiState = if (ack) 0x50 else 0x58; twint = true }
        }
    }

    // ---------- external interrupts INT0–INT7 ----------
    // INT0–3 are PD0–3 (port index 3), INT4–7 are PE4–7 (port index 4)
    fun inputChanged(p: Int, oldLevels: Int, newLevels: Int) {
        for (n in 0 until 8) {
            val (pp, bit) = if (n < 4) 3 to n else 4 to n
            if (pp != p) continue
            val o = (oldLevels shr bit) and 1; val nw = (newLevels shr bit) and 1
            val sense = if (n < 4) (data[0x69] shr (2 * n)) and 3 else (data[0x6A] shr (2 * (n - 4))) and 3
            val fire = when (sense) { 0 -> nw == 0; 1 -> o != nw; 2 -> o == 1 && nw == 0; else -> o == 0 && nw == 1 }
            if (fire) { data[0x3C] = data[0x3C] or (1 shl n); irqDue = true }
        }
    }

    // ---------- data space access ----------
    fun read(a: Int): Int {
        if (a >= 0x200) return if (a < 0x2200) data[a] else 0
        if (a < 0x20) return data[a]
        if (peripheral(a)) sync()
        when (a) {
            0x5F -> return sreg
            0xC0 -> return (if (synchronized(rx) { rx.isNotEmpty() }) 0x80 else 0) or 0x60 or (data[a] and 0x1F)
            0xC6 -> return synchronized(rx) { rx.removeFirstOrNull() ?: 0 }
            0xC8, 0xD0, 0x130 -> return 0x60 // Serial1–3: always ready to send
            0x4D -> return 0x80              // SPI: transfer always complete
            0xBC -> return data[a] or (if (twint) 0x80 else 0)
            0xB9 -> return twiState or (data[a] and 3)
        }
        if (a < 0x200) { val p = portIndex(a); if (p >= 0 && a == portBase[p]) return pinLevels(p) }
        return if (a in data.indices) data[a] else 0
    }

    fun write(a: Int, v0: Int) {
        val v = v0 and 0xFF
        if (a >= 0x200) { if (a < 0x2200) data[a] = v; return }
        if (a < 0x20) { data[a] = v; return }
        if (a == 0x5F) { sreg = v; irqDue = true; return }
        if (peripheral(a)) { sync(); irqDue = true }
        if (a < 0x200) {
            val p = portIndex(a)
            if (p >= 0) {
                val before = pinLevels(p)
                if (a == portBase[p]) data[portBase[p] + 2] = data[portBase[p] + 2] xor v  // writing PINx toggles PORTx
                else data[a] = v
                val after = pinLevels(p)
                if (before != after) { onPortWrite?.invoke(p, before, after) }
                return
            }
        }
        when (a) {
            0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3C -> { data[a] = data[a] and v.inv(); return } // flag registers: write 1 to clear
            0xC6 -> { onSerial?.invoke(v); return }
            0x7A -> {
                data[a] = (v and 0xEF) or (data[a] and 0x10 and (if (v and 0x10 != 0) 0 else 0x10))
                if (v and 0x40 != 0 && v and 0x80 != 0) adcStart()
                return
            }
            0x3F -> { // EECR
                val addr = (data[0x41] or (data[0x42] shl 8)) and 0xFFF
                if (v and 1 != 0) data[0x40] = eeprom[addr]
                if (v and 2 != 0) eeprom[addr] = data[0x40]
                data[a] = v and 0xFC
                return
            }
            0xBC -> { // TWCR: writing TWINT=1 clears the flag and starts the next bus operation
                data[a] = v and 0x7F
                if (v and 0x80 != 0 && v and 0x04 != 0) { twint = false; twiAction = v; twiDelay = 40 }
                return
            }
        }
        if (a in data.indices) data[a] = v
        if (onTimerWrite != null && timers.any { it.isTimerReg(a) }) onTimerWrite?.invoke()
    }

    // ---------- helpers ----------
    private fun r(i: Int) = data[i]
    private fun setR(i: Int, v: Int) { data[i] = v and 0xFF }
    private fun word(i: Int) = data[i] or (data[i + 1] shl 8)
    private fun setWord(i: Int, v: Int) { data[i] = v and 0xFF; data[i + 1] = (v shr 8) and 0xFF }
    private val words = IntArray(128 * 1024) { (flash[2 * it].toInt() and 0xFF) or ((flash[2 * it + 1].toInt() and 0xFF) shl 8) }
    private fun fw(p: Int): Int = words[p and 0x1FFFF]
    var sp: Int
        get() = data[0x5D] or (data[0x5E] shl 8)
        set(v) { data[0x5D] = v and 0xFF; data[0x5E] = (v shr 8) and 0xFF }
    private fun push(v: Int) { val s = sp; if (s in data.indices) data[s] = v and 0xFF; sp = s - 1 }
    private fun pop(): Int { val s = sp + 1; sp = s; return if (s in data.indices) data[s] else 0 }
    private fun pushPc(v: Int) { push(v); push(v shr 8); push(v shr 16) }
    private fun popPc(): Int { val h = pop(); val m = pop(); val l = pop(); return (h shl 16) or (m shl 8) or l }

    private fun flag(b: Int) = (sreg shr b) and 1
    private fun setFlags(c: Int, z: Int, n: Int, v: Int, s: Int, h: Int, mask: Int) {
        val f = c or (z shl 1) or (n shl 2) or (v shl 3) or (s shl 4) or (h shl 5)
        sreg = (sreg and mask.inv()) or (f and mask)
    }
    private fun bit(x: Int, b: Int) = (x shr b) and 1
    private fun is32(op: Int) = (op and 0xFE0F) == 0x9000 || (op and 0xFE0F) == 0x9200 || (op and 0xFE0E) == 0x940C || (op and 0xFE0E) == 0x940E

    private fun add(d: Int, rv: Int, carry: Int): Int {
        val rd = r(d); val res = (rd + rv + carry) and 0xFF
        val c = if (rd + rv + carry > 0xFF) 1 else 0
        val h = (bit(rd, 3) and bit(rv, 3)) or (bit(rv, 3) and bit(res, 3).inv()) or (bit(res, 3).inv() and bit(rd, 3))
        val v = (bit(rd, 7) and bit(rv, 7) and (bit(res, 7) xor 1)) or ((bit(rd, 7) xor 1) and (bit(rv, 7) xor 1) and bit(res, 7))
        val n = bit(res, 7)
        setFlags(c, if (res == 0) 1 else 0, n, v, n xor v, h and 1, 0x3F)
        return res
    }
    // subtraction; keepZ: SBC/SBCI/CPC only clear Z, never set it
    private fun sub(rd: Int, rv: Int, carry: Int, keepZ: Boolean): Int {
        val res = (rd - rv - carry) and 0xFF
        val c = if (rv + carry > rd) 1 else 0
        val h = ((bit(rd, 3) xor 1) and bit(rv, 3)) or (bit(rv, 3) and bit(res, 3)) or (bit(res, 3) and (bit(rd, 3) xor 1))
        val v = (bit(rd, 7) and (bit(rv, 7) xor 1) and (bit(res, 7) xor 1)) or ((bit(rd, 7) xor 1) and bit(rv, 7) and bit(res, 7))
        val n = bit(res, 7)
        val z = if (keepZ) (if (res == 0) flag(1) else 0) else (if (res == 0) 1 else 0)
        setFlags(c, z, n, v, n xor v, h and 1, 0x3F)
        return res
    }
    private fun logic(res: Int): Int { val n = bit(res, 7); setFlags(0, if (res == 0) 1 else 0, n, 0, n, 0, 0x1E); return res }

    // ---------- interrupts ----------
    private fun pendingVector(): Int {
        var best = Int.MAX_VALUE
        val eif = data[0x3C] and data[0x3D]
        if (eif != 0) for (n in 0 until 8) if (eif and (1 shl n) != 0) { best = minOf(best, n + 1); break }
        for (t in timers) { val v = t.pending(); if (v in 1 until best) best = v }
        val b = data[0xC1]
        if (b and 0x80 != 0 && synchronized(rx) { rx.isNotEmpty() }) best = minOf(best, 25)
        if (b and 0x20 != 0) best = minOf(best, 26)
        if (data[0x7A] and 0x18 == 0x18) best = minOf(best, 29)
        if (twint && data[0xBC] and 0x05 == 0x05) best = minOf(best, 39)
        return if (best == Int.MAX_VALUE) -1 else best
    }
    private fun service(v: Int) {
        when (v) {
            in 1..8 -> data[0x3C] = data[0x3C] and (1 shl (v - 1)).inv()
            29 -> data[0x7A] = data[0x7A] and 0x10.inv()
            25, 26, 39 -> {}
            else -> timers.forEach { it.clear(v) }
        }
        pushPc(pc)
        sreg = sreg and 0x7F
        pc = v * 2
        irqDue = true
        tick(5)
    }

    // Peripherals catch up in batches: every 64 cycles, or before the sketch touches one of their registers
    private var lag = 0
    // set whenever an interrupt might have become pending; only the simulator thread touches it
    var irqDue = true
    private fun tick(c: Int) {
        cycles += c
        lag += c
        if (lag >= 64) sync()
    }
    private fun sync() {
        val c = lag; if (c == 0) return
        lag = 0
        for (t in timers) t.step(c)
        if (adcLeft > 0) { adcLeft -= c; if (adcLeft <= 0) adcDone() }
        if (twiDelay > 0) { twiDelay -= c; if (twiDelay <= 0) { twiDelay = -1; twiDo(twiAction) } }
        irqDue = true
    }
    // GPIO registers are hot (digitalRead/Write) and need no catch-up; every other I/O register does
    private fun peripheral(a: Int) = a in 0x20..0x1FF && a != 0x5F && a != 0x5D && a != 0x5E && portIndex(a) < 0

    // ---------- the program, decoded once ----------
    // Each flash word gets an instruction kind and its operands, so running an instruction is one table jump.
    private val kind = ByteArray(128 * 1024)
    private val argA = IntArray(128 * 1024)   // usually Rd / register pair / I/O address
    private val argB = IntArray(128 * 1024)   // usually Rr / constant / bit / offset
    private val wide2 = BooleanArray(128 * 1024) // 32-bit instruction (LDS, STS, JMP, CALL)

    private fun decodeAll() { for (p in 0 until 128 * 1024) decode(p) }

    private fun decode(p: Int) {
        val op = words[p]
        val d5 = (op shr 4) and 0x1F
        val r5 = (op and 0xF) or ((op shr 5) and 0x10)
        var k = K_UNKNOWN; var a = d5; var b = r5
        when (op shr 12) {
            0x0 -> when {
                op == 0 -> k = K_NOP
                op and 0xFF00 == 0x0100 -> { k = K_MOVW; a = ((op shr 4) and 0xF) * 2; b = (op and 0xF) * 2 }
                op and 0xFF00 == 0x0200 -> { k = K_MULS; a = 16 + ((op shr 4) and 0xF); b = 16 + (op and 0xF) }
                op and 0xFF88 == 0x0300 -> { k = K_MULSU; a = 16 + ((op shr 4) and 7); b = 16 + (op and 7) }
                op and 0xFF88 == 0x0308 -> { k = K_FMUL; a = 16 + ((op shr 4) and 7); b = 16 + (op and 7) }
                op and 0xFF88 == 0x0380 -> { k = K_FMULS; a = 16 + ((op shr 4) and 7); b = 16 + (op and 7) }
                op and 0xFF88 == 0x0388 -> { k = K_FMULSU; a = 16 + ((op shr 4) and 7); b = 16 + (op and 7) }
                op and 0xFC00 == 0x0400 -> k = K_CPC
                op and 0xFC00 == 0x0800 -> k = K_SBC
                else -> k = K_ADD
            }
            0x1 -> k = when (op and 0xFC00) { 0x1000 -> K_CPSE; 0x1400 -> K_CP; 0x1800 -> K_SUB; else -> K_ADC }
            0x2 -> k = when (op and 0xFC00) { 0x2000 -> K_AND; 0x2400 -> K_EOR; 0x2800 -> K_OR; else -> K_MOV }
            0x3, 0x4, 0x5, 0x6, 0x7, 0xE -> {
                a = 16 + ((op shr 4) and 0xF); b = (op and 0xF) or ((op shr 4) and 0xF0)
                k = when (op shr 12) { 0x3 -> K_CPI; 0x4 -> K_SBCI; 0x5 -> K_SUBI; 0x6 -> K_ORI; 0x7 -> K_ANDI; else -> K_LDI }
            }
            0x8, 0xA -> {
                val q = (op and 7) or ((op shr 7) and 0x18) or ((op shr 8) and 0x20)
                b = q or (if (op and 8 != 0) 0x100 else 0)      // bit 8: Y instead of Z
                k = if (op and 0x200 != 0) K_STD else K_LDD
            }
            0x9 -> decode9(op, d5, r5).let { (kk, aa, bb) -> k = kk; a = aa; b = bb }
            0xB -> { a = d5; b = (op and 0xF) or ((op shr 5) and 0x30); k = if (op and 0x800 != 0) K_OUT else K_IN }
            0xC -> { k = K_RJMP; b = (op shl 20) shr 20 }
            0xD -> { k = K_RCALL; b = (op shl 20) shr 20 }
            0xF -> when {
                op and 0xF800 == 0xF000 -> { k = if (op and 0x400 == 0) K_BRBS else K_BRBC; a = op and 7; b = (op shl 22) shr 25 }
                op and 0xFE08 == 0xF800 -> { k = K_BLD; b = op and 7 }
                op and 0xFE08 == 0xFA00 -> { k = K_BST; b = op and 7 }
                op and 0xFE08 == 0xFC00 -> { k = K_SBRC; b = op and 7 }
                op and 0xFE08 == 0xFE00 -> { k = K_SBRS; b = op and 7 }
            }
        }
        if (k == K_JMP || k == K_CALL) b = ((((op shr 3) and 0x3E) or (op and 1)) shl 16) or words[(p + 1) and 0x1FFFF]
        if (k == K_LDS || k == K_STS) b = words[(p + 1) and 0x1FFFF]
        kind[p] = k.toByte(); argA[p] = a; argB[p] = b
        wide2[p] = k == K_JMP || k == K_CALL || k == K_LDS || k == K_STS
    }

    private fun decode9(op: Int, d: Int, rr: Int): Triple<Int, Int, Int> {
        when (op and 0xFE00) {
            0x9000 -> return Triple(when (op and 0xF) { 0x0 -> K_LDS; 0x1 -> K_LDZP; 0x2 -> K_LDZM; 0x4 -> K_LPM; 0x5 -> K_LPMP; 0x6 -> K_ELPM; 0x7 -> K_ELPMP
                0x9 -> K_LDYP; 0xA -> K_LDYM; 0xC -> K_LDX; 0xD -> K_LDXP; 0xE -> K_LDXM; 0xF -> K_POP; else -> K_NOP }, d, 0)
            0x9200 -> return Triple(when (op and 0xF) { 0x0 -> K_STS; 0x1 -> K_STZP; 0x2 -> K_STZM; 0x9 -> K_STYP; 0xA -> K_STYM
                0xC -> K_STX; 0xD -> K_STXP; 0xE -> K_STXM; 0xF -> K_PUSH; else -> K_NOP }, d, 0)
            0x9400 -> {
                when (op) {
                    0x9409 -> return Triple(K_IJMP, 0, 0); 0x9419 -> return Triple(K_EIJMP, 0, 0)
                    0x9509 -> return Triple(K_ICALL, 0, 0); 0x9519 -> return Triple(K_EICALL, 0, 0)
                    0x9508 -> return Triple(K_RET, 0, 0); 0x9518 -> return Triple(K_RETI, 0, 0)
                    0x9588, 0x9598, 0x95A8, 0x95E8 -> return Triple(K_NOP, 0, 0)
                    0x95C8 -> return Triple(K_LPM0, 0, 0); 0x95D8 -> return Triple(K_ELPM0, 0, 0)
                }
                if (op and 0xFF8F == 0x9408) return Triple(K_BSET, (op shr 4) and 7, 0)
                if (op and 0xFF8F == 0x9488) return Triple(K_BCLR, (op shr 4) and 7, 0)
                if (op and 0xFE0E == 0x940C) return Triple(K_JMP, 0, 0)
                if (op and 0xFE0E == 0x940E) return Triple(K_CALL, 0, 0)
                return Triple(when (op and 0xF) { 0x0 -> K_COM; 0x1 -> K_NEG; 0x2 -> K_SWAP; 0x3 -> K_INC; 0x5 -> K_ASR; 0x6 -> K_LSR; 0x7 -> K_ROR; 0xA -> K_DEC; else -> K_NOP }, d, 0)
            }
            0x9600 -> return Triple(if (op and 0x100 != 0) K_SBIW else K_ADIW, 24 + ((op shr 4) and 3) * 2, (op and 0xF) or ((op shr 2) and 0x30))
            0x9800, 0x9A00 -> return Triple(when (op and 0xFF00) { 0x9800 -> K_CBI; 0x9900 -> K_SBIC; 0x9A00 -> K_SBI; else -> K_SBIS }, 0x20 + ((op shr 3) and 0x1F), op and 7)
            else -> return Triple(K_MUL, d, rr)
        }
    }

    // ---------- one instruction ----------
    fun step() {
        if (sreg and 0x80 != 0 && irqDue) {
            val v = pendingVector()
            if (v > 0) { service(v); return }
            irqDue = false
        }
        val p = pc
        val a = argA[p]; val b = argB[p]
        pc = (p + 1) and 0x1FFFF
        var cy = 1
        when (kind[p].toInt()) {
            K_NOP -> {}
            K_MOVW -> { data[a] = data[b]; data[a + 1] = data[b + 1] }
            K_MULS -> mul(a, b, true, true, false)
            K_MULSU -> mul(a, b, true, false, false)
            K_FMUL -> mul(a, b, false, false, true)
            K_FMULS -> mul(a, b, true, true, true)
            K_FMULSU -> mul(a, b, true, false, true)
            K_CPC -> sub(data[a], data[b], sreg and 1, true)
            K_SBC -> data[a] = sub(data[a], data[b], sreg and 1, true)
            K_ADD -> data[a] = add(a, data[b], 0)
            K_CPSE -> if (data[a] == data[b]) cy += skip()
            K_CP -> sub(data[a], data[b], 0, false)
            K_SUB -> data[a] = sub(data[a], data[b], 0, false)
            K_ADC -> data[a] = add(a, data[b], sreg and 1)
            K_AND -> data[a] = logic(data[a] and data[b])
            K_EOR -> data[a] = logic(data[a] xor data[b])
            K_OR -> data[a] = logic(data[a] or data[b])
            K_MOV -> data[a] = data[b]
            K_CPI -> sub(data[a], b, 0, false)
            K_SBCI -> data[a] = sub(data[a], b, sreg and 1, true)
            K_SUBI -> data[a] = sub(data[a], b, 0, false)
            K_ORI -> data[a] = logic(data[a] or b)
            K_ANDI -> data[a] = logic(data[a] and b)
            K_LDI -> data[a] = b
            K_LDD -> { data[a] = read((if (b and 0x100 != 0) word(28) else word(30)) + (b and 0xFF)); cy = 2 }
            K_STD -> { write((if (b and 0x100 != 0) word(28) else word(30)) + (b and 0xFF), data[a]); cy = 2 }
            K_LDS -> { data[a] = read(b); pc = (pc + 1) and 0x1FFFF; cy = 2 }
            K_STS -> { write(b, data[a]); pc = (pc + 1) and 0x1FFFF; cy = 2 }
            K_LDZP -> { val z = word(30); data[a] = read(z); setWord(30, z + 1); cy = 2 }
            K_LDZM -> { val z = (word(30) - 1) and 0xFFFF; data[a] = read(z); setWord(30, z); cy = 2 }
            K_LDYP -> { val y = word(28); data[a] = read(y); setWord(28, y + 1); cy = 2 }
            K_LDYM -> { val y = (word(28) - 1) and 0xFFFF; data[a] = read(y); setWord(28, y); cy = 2 }
            K_LDX -> { data[a] = read(word(26)); cy = 2 }
            K_LDXP -> { val x = word(26); data[a] = read(x); setWord(26, x + 1); cy = 2 }
            K_LDXM -> { val x = (word(26) - 1) and 0xFFFF; data[a] = read(x); setWord(26, x); cy = 2 }
            K_STZP -> { val z = word(30); write(z, data[a]); setWord(30, z + 1); cy = 2 }
            K_STZM -> { val z = (word(30) - 1) and 0xFFFF; write(z, data[a]); setWord(30, z); cy = 2 }
            K_STYP -> { val y = word(28); write(y, data[a]); setWord(28, y + 1); cy = 2 }
            K_STYM -> { val y = (word(28) - 1) and 0xFFFF; write(y, data[a]); setWord(28, y); cy = 2 }
            K_STX -> { write(word(26), data[a]); cy = 2 }
            K_STXP -> { val x = word(26); write(x, data[a]); setWord(26, x + 1); cy = 2 }
            K_STXM -> { val x = (word(26) - 1) and 0xFFFF; write(x, data[a]); setWord(26, x); cy = 2 }
            K_POP -> { data[a] = pop(); cy = 2 }
            K_PUSH -> { push(data[a]); cy = 2 }
            K_LPM -> { data[a] = flash[word(30)].toInt() and 0xFF; cy = 3 }
            K_LPMP -> { val z = word(30); data[a] = flash[z].toInt() and 0xFF; setWord(30, z + 1); cy = 3 }
            K_ELPM -> { data[a] = flash[(word(30) or (data[0x5B] shl 16)) and 0x3FFFF].toInt() and 0xFF; cy = 3 }
            K_ELPMP -> { val z = word(30) or (data[0x5B] shl 16); data[a] = flash[z and 0x3FFFF].toInt() and 0xFF; val nz = (z + 1) and 0xFFFFFF; setWord(30, nz); data[0x5B] = (nz shr 16) and 0xFF; cy = 3 }
            K_LPM0 -> { data[0] = flash[word(30)].toInt() and 0xFF; cy = 3 }
            K_ELPM0 -> { data[0] = flash[(word(30) or (data[0x5B] shl 16)) and 0x3FFFF].toInt() and 0xFF; cy = 3 }
            K_IJMP -> { pc = word(30); cy = 2 }
            K_EIJMP -> { pc = (word(30) or (data[0x5C] shl 16)) and 0x1FFFF; cy = 2 }
            K_ICALL -> { pushPc(pc); pc = word(30); cy = 4 }
            K_EICALL -> { pushPc(pc); pc = (word(30) or (data[0x5C] shl 16)) and 0x1FFFF; cy = 4 }
            K_RET -> { pc = popPc() and 0x1FFFF; cy = 5 }
            K_RETI -> { pc = popPc() and 0x1FFFF; sreg = sreg or 0x80; irqDue = true; cy = 5 }
            K_BSET -> { sreg = sreg or (1 shl a); irqDue = true }
            K_BCLR -> sreg = sreg and (1 shl a).inv()
            K_JMP -> { pc = b and 0x1FFFF; cy = 3 }
            K_CALL -> { pushPc((pc + 1) and 0x1FFFF); pc = b and 0x1FFFF; cy = 5 }
            K_COM -> { val res = data[a] xor 0xFF; val n = res shr 7; setFlags(1, if (res == 0) 1 else 0, n, 0, n, 0, 0x1F); data[a] = res }
            K_NEG -> { val v = data[a]; val res = (0 - v) and 0xFF; val n = res shr 7; val vv = if (res == 0x80) 1 else 0
                setFlags(if (res != 0) 1 else 0, if (res == 0) 1 else 0, n, vv, n xor vv, ((res shr 3) or (v shr 3)) and 1, 0x3F); data[a] = res }
            K_SWAP -> { val v = data[a]; data[a] = ((v shl 4) or (v shr 4)) and 0xFF }
            K_INC -> { val res = (data[a] + 1) and 0xFF; val n = res shr 7; val vv = if (res == 0x80) 1 else 0; setFlags(0, if (res == 0) 1 else 0, n, vv, n xor vv, 0, 0x1E); data[a] = res }
            K_DEC -> { val res = (data[a] - 1) and 0xFF; val n = res shr 7; val vv = if (res == 0x7F) 1 else 0; setFlags(0, if (res == 0) 1 else 0, n, vv, n xor vv, 0, 0x1E); data[a] = res }
            K_ASR -> { val v = data[a]; val c = v and 1; val res = (v shr 1) or (v and 0x80); val n = res shr 7; val vv = n xor c; setFlags(c, if (res == 0) 1 else 0, n, vv, n xor vv, 0, 0x1F); data[a] = res }
            K_LSR -> { val v = data[a]; val c = v and 1; val res = v shr 1; setFlags(c, if (res == 0) 1 else 0, 0, c, c, 0, 0x1F); data[a] = res }
            K_ROR -> { val v = data[a]; val c = v and 1; val res = (v shr 1) or ((sreg and 1) shl 7); val n = res shr 7; val vv = n xor c; setFlags(c, if (res == 0) 1 else 0, n, vv, n xor vv, 0, 0x1F); data[a] = res }
            K_ADIW, K_SBIW -> {
                val w = word(a); val sbiw = kind[p].toInt() == K_SBIW
                val res = (if (sbiw) w - b else w + b) and 0xFFFF
                val n = res shr 15; val hi = w shr 15
                val v = if (sbiw) hi and (n xor 1) else (hi xor 1) and n
                val c = if (sbiw) n and (hi xor 1) else (n xor 1) and hi
                setFlags(c, if (res == 0) 1 else 0, n, v, n xor v, 0, 0x1F); setWord(a, res); cy = 2
            }
            K_CBI -> { write(a, read(a) and (1 shl b).inv()); cy = 2 }
            K_SBI -> { write(a, if (portIndex(a) >= 0 && portBase[portIndex(a)] == a) (1 shl b) else read(a) or (1 shl b)); cy = 2 }
            K_SBIC -> if ((read(a) shr b) and 1 == 0) cy += skip()
            K_SBIS -> if ((read(a) shr b) and 1 == 1) cy += skip()
            K_MUL -> { val pr = data[a] * data[b]; data[0] = pr and 0xFF; data[1] = (pr shr 8) and 0xFF; sreg = (sreg and 3.inv()) or ((pr shr 15) and 1) or (if (pr == 0) 2 else 0); cy = 2 }
            K_IN -> data[a] = read(b + 0x20)
            K_OUT -> write(b + 0x20, data[a])
            K_RJMP -> { pc = (pc + b) and 0x1FFFF; cy = 2 }
            K_RCALL -> { pushPc(pc); pc = (pc + b) and 0x1FFFF; cy = 4 }
            K_BRBS -> if ((sreg shr a) and 1 == 1) { pc = (pc + b) and 0x1FFFF; cy = 2 }
            K_BRBC -> if ((sreg shr a) and 1 == 0) { pc = (pc + b) and 0x1FFFF; cy = 2 }
            K_BLD -> data[a] = (data[a] and (1 shl b).inv()) or (((sreg shr 6) and 1) shl b)
            K_BST -> sreg = (sreg and 0x40.inv()) or (((data[a] shr b) and 1) shl 6)
            K_SBRC -> if ((data[a] shr b) and 1 == 0) cy += skip()
            K_SBRS -> if ((data[a] shr b) and 1 == 1) cy += skip()
        }
        tick(cy)
    }

    private fun skip(): Int { val w = if (wide2[pc]) 2 else 1; pc = (pc + w) and 0x1FFFF; return w }

    private fun mul(d: Int, rr: Int, sd: Boolean, srr: Boolean, frac: Boolean) {
        val x = if (sd) data[d].toByte().toInt() else data[d]
        val y = if (srr) data[rr].toByte().toInt() else data[rr]
        var pr = (x * y) and 0xFFFF
        val c = (pr shr 15) and 1
        if (frac) pr = (pr shl 1) and 0xFFFF
        data[0] = pr and 0xFF; data[1] = pr shr 8
        sreg = (sreg and 3.inv()) or c or (if (pr == 0) 2 else 0)
    }

    // SBI on a PINx register toggles only the given bit (other bits written as 0)
    private fun portBaseForPin(a: Int): Int = portBase.firstOrNull { it == a } ?: -1

    fun reset() {
        data.fill(0); sreg = 0; pc = 0; cycles = 0
        sp = 0x21FF
        synchronized(rx) { rx.clear() }
        lag = 0; irqDue = true
        adcLeft = -1; twiState = 0xF8; twint = false; twiDelay = -1; twiActive = false
        timers.forEach { it.acc = 0; it.down = false }
    }

    init { decodeAll(); reset() }
}
