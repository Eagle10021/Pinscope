// Pinscope fast scope and signal generator.
// Captures one analog pin with the ADC running free and sends the samples to the Pinscope app at
// 1,000,000 baud, while generating test signals to look at:
//   pin 9   490 Hz PWM, duty set by the app (25% at start)
//   pin 11  square wave, 1 Hz to over 100 kHz (Timer1 hardware toggle, so no jitter)
//   pin 6   sine, triangle or sawtooth, 1 to 200 Hz: 62.5 kHz PWM, smooth it with an RC filter
//
// Commands, one line each:
//   ?                                  -> "PINSCOPE-SCOPE 3"
//   S<ch>,<ps>,<dec>,<n>,<lvl>,<edge>,<timeout ms>   capture (see below)
//   Q<tenths of Hz>                    square wave on pin 11, 0 = off
//   P<0-255>                           PWM duty on pin 9
//   W<shape>,<tenths of Hz>            pin 6: shape 0 off, 1 sine, 2 triangle, 3 sawtooth
//   U<0|1>                             pull-up on the capture pin (for buttons)
// Capture: ch analog pin 0-15, ps ADC prescaler bits 2-7, dec keep every dec-th sample, n samples (max 6000),
// lvl trigger level 0-255, edge 0 none / 1 rising / 2 falling. Reply: 0xA5 0x5A, n (2 bytes, little-endian),
// flags (1 = triggered), duration of n samples in microseconds (4 bytes), then n 8-bit samples (0-255 = 0-5 V).

const uint16_t MAXN = 6000;
uint8_t buf[MAXN];
char line[48];
uint8_t len = 0;
uint8_t capCh = 0;
bool pullUp = false;

// one period of a sine, 64 steps
const uint8_t SINE[64] PROGMEM = {
  128,140,152,165,176,188,198,208,218,226,234,240,245,250,253,254,255,254,253,250,245,240,234,226,218,208,198,188,176,165,152,140,
  128,115,103, 90, 79, 67, 57, 47, 37, 29, 21, 15, 10,  5,  2,  1,  0,  1,  2,  5, 10, 15, 21, 29, 37, 47, 57, 67, 79, 90,103,115 };
volatile uint8_t wShape = 0;
volatile uint8_t wIdx = 0;

void setup() {
  Serial.begin(1000000);
  pinMode(9, OUTPUT);
  analogWrite(9, 64);
}

// ---------- signal generator ----------

// Timer1 CTC, toggling OC1A (pin 11): f = 16 MHz / (2 * N * (1 + OCR1A))
static void squareWave(uint32_t tenthsHz) {
  TCCR1A = 0; TCCR1B = 0; TCNT1 = 0;
  if (tenthsHz == 0) { pinMode(11, OUTPUT); digitalWrite(11, LOW); return; }
  const uint16_t pres[] = {1, 8, 64, 256, 1024};
  const uint8_t bits[] = {1, 2, 3, 4, 5};
  for (uint8_t i = 0; i < 5; i++) {
    uint32_t top = (uint32_t)(80000000.0 / ((double)tenthsHz * pres[i]) + 0.5);  // 16e6*10/(2*f10*N)
    if (top >= 1 && top <= 65536) {
      pinMode(11, OUTPUT);
      OCR1A = top - 1;
      TCCR1A = _BV(COM1A0);                   // toggle on compare match
      TCCR1B = _BV(WGM12) | bits[i];          // CTC
      return;
    }
  }
}

// Timer4 fast PWM 8-bit at 62.5 kHz on OC4A (pin 6); Timer3 interrupt steps through 64 samples a period
static void wave(uint8_t shape, uint32_t tenthsHz) {
  TIMSK3 = 0;
  if (shape == 0 || tenthsHz == 0) { TCCR4A = 0; TCCR4B = 0; pinMode(6, OUTPUT); digitalWrite(6, LOW); wShape = 0; return; }
  wShape = shape; wIdx = 0;
  pinMode(6, OUTPUT);
  TCCR4A = _BV(COM4A1) | _BV(WGM40);
  TCCR4B = _BV(WGM42) | _BV(CS40);
  // Timer3 CTC at 2 MHz (prescaler 8): OCR3A = 2e6 / (f * 64) - 1
  uint32_t top = (uint32_t)(2000000.0 * 10.0 / ((double)tenthsHz * 64.0) + 0.5);
  if (top < 20) top = 20;
  if (top > 65535) top = 65535;
  TCCR3A = 0; TCCR3B = _BV(WGM32) | _BV(CS31); TCNT3 = 0;
  OCR3A = top - 1;
  TIMSK3 = _BV(OCIE3A);
}

ISR(TIMER3_COMPA_vect) {
  uint8_t i = wIdx = (wIdx + 1) & 63;
  uint8_t v;
  if (wShape == 1) v = pgm_read_byte(&SINE[i]);
  else if (wShape == 2) v = i < 32 ? i * 8 : (63 - i) * 8;
  else v = i * 4;
  OCR4A = v;
}

// ---------- capture ----------

static void startAdc(uint8_t ch, uint8_t ps) {
  ADCSRA = 0;
  ADMUX = _BV(REFS0) | _BV(ADLAR) | (ch & 7);             // AVcc reference, 8-bit result in ADCH
  if (ch >= 8) ADCSRB = _BV(MUX5); else ADCSRB = 0;        // free running, channels 8-15 need MUX5
  DIDR0 = ch < 8 ? _BV(ch) : 0;
  DIDR2 = ch >= 8 ? _BV(ch - 8) : 0;
  ADCSRA = _BV(ADEN) | _BV(ADSC) | _BV(ADATE) | _BV(ADIF) | (ps & 7);
}

static inline uint8_t nextSample(uint8_t dec) {
  uint8_t v = 0;
  for (uint8_t k = 0; k < dec; k++) {
    while (!(ADCSRA & _BV(ADIF))) {}
    v = ADCH;
    ADCSRA |= _BV(ADIF);
  }
  return v;
}

static void capture(uint8_t ch, uint8_t ps, uint8_t dec, uint16_t n, uint8_t lvl, uint8_t edge, uint16_t timeoutMs) {
  if (n > MAXN) n = MAXN;
  if (n < 16) n = 16;
  if (dec < 1) dec = 1;
  if (ch != capCh) { pinMode(A0 + capCh, INPUT); capCh = ch; }
  pinMode(A0 + ch, pullUp ? INPUT_PULLUP : INPUT);
  startAdc(ch, ps);
  nextSample(2);                                          // let the first conversions settle
  uint16_t pre = edge ? n / 4 : 0;
  uint16_t i = 0, filled = 0;
  uint8_t prev = nextSample(dec);
  bool triggered = false;
  unsigned long t0 = millis();
  // fill a ring until the trigger, keeping a quarter of the window before it
  if (edge) {
    while (true) {
      uint8_t v = nextSample(dec);
      buf[i] = v; if (++i == n) i = 0; if (filled < n) filled++;
      if (filled >= pre && ((edge == 1 && prev < lvl && v >= lvl) || (edge == 2 && prev > lvl && v <= lvl))) { triggered = true; break; }
      prev = v;
      if (millis() - t0 > timeoutMs) break;               // no edge: show what is there
    }
  }
  unsigned long us0 = micros();
  uint16_t rest = n - (edge ? pre : 0);
  for (uint16_t k = 0; k < rest; k++) { buf[i] = nextSample(dec); if (++i == n) i = 0; }
  unsigned long us = micros() - us0;
  ADCSRA = 0;
  unsigned long total = (unsigned long)((double)us * n / rest);
  Serial.write(0xA5); Serial.write(0x5A);
  Serial.write(n & 0xFF); Serial.write(n >> 8);
  Serial.write(triggered ? 1 : 0);
  for (uint8_t b = 0; b < 4; b++) Serial.write((total >> (8 * b)) & 0xFF);
  uint16_t start = edge ? i : 0;
  for (uint16_t k = 0, j = start; k < n; k++) { Serial.write(buf[j]); if (++j == n) j = 0; }
}

static long field(char*& p) {
  long v = 0;
  while (*p && (*p < '0' || *p > '9')) p++;
  while (*p >= '0' && *p <= '9') { v = v * 10 + (*p - '0'); p++; }
  return v;
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      line[len] = 0;
      char* p = line + 1;
      if (len) switch (line[0]) {
        case '?': Serial.println("PINSCOPE-SCOPE 3"); break;
        case 'S': {
          uint8_t ch = field(p), ps = field(p), dec = field(p);
          uint16_t n = field(p); uint8_t lvl = field(p), edge = field(p); uint16_t to = field(p);
          capture(ch, ps, dec, n, lvl, edge, to);
          break;
        }
        case 'Q': squareWave(field(p)); break;
        case 'P': analogWrite(9, constrain(field(p), 0, 255)); break;
        case 'W': { uint8_t sh = field(p); uint32_t f = field(p); wave(sh, f); break; }
        case 'U': pullUp = field(p) != 0; break;
      }
      len = 0;
    } else if (len < sizeof(line) - 1) line[len++] = c;
  }
}
