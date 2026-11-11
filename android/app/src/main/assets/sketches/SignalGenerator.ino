// Function generator for a bench oscilloscope. Type commands in the serial monitor (115200 baud, newline):
//   s1000   square wave on pin 11 at 1000 Hz (1 Hz to 100 kHz; 0 = off)
//   d50     PWM on pin 9 at 50% duty (0-100), about 490 Hz
//   w1 20   pin 6 wave: 0 off, 1 sine, 2 triangle, 3 sawtooth, at 20 Hz (1-200 Hz)
//   ?       show the current settings
// Pin 6 is 62.5 kHz PWM following the waveform: put a 10 kOhm resistor in series and a 100 nF capacitor
// to GND after it, and probe across the capacitor for a smooth wave.
// Simulator: scope 11 2ms; scope 9 5ms; rc 6 A0 10k 100n; scope A0 200ms

const uint8_t SINE[64] PROGMEM = {
  128,140,152,165,176,188,198,208,218,226,234,240,245,250,253,254,255,254,253,250,245,240,234,226,218,208,198,188,176,165,152,140,
  128,115,103, 90, 79, 67, 57, 47, 37, 29, 21, 15, 10,  5,  2,  1,  0,  1,  2,  5, 10, 15, 21, 29, 37, 47, 57, 67, 79, 90,103,115 };
volatile uint8_t shape = 0, idx = 0;
long squareHz = 1000, waveHz = 10;
int duty = 50;
char line[32];
uint8_t len = 0;

// Timer1 CTC toggling OC1A (pin 11): f = 16 MHz / (2 * N * (OCR1A + 1)); returns the frequency actually made
float setSquare(long hz) {
  TCCR1A = 0; TCCR1B = 0; TCNT1 = 0;
  if (hz <= 0) { digitalWrite(11, LOW); return 0; }
  const uint16_t pres[] = {1, 8, 64, 256, 1024};
  for (uint8_t i = 0; i < 5; i++) {
    uint32_t top = (uint32_t)(8000000.0 / ((double)hz * pres[i]) + 0.5);
    if (top >= 1 && top <= 65536) {
      OCR1A = top - 1;
      TCCR1A = _BV(COM1A0);
      TCCR1B = _BV(WGM12) | (i + 1);
      return 8000000.0 / ((double)top * pres[i]);
    }
  }
  return 0;
}

// Timer4 fast PWM on OC4A (pin 6), Timer3 interrupt stepping 64 samples per period
void setWave(uint8_t s, long hz) {
  TIMSK3 = 0;
  shape = s;
  if (s == 0 || hz <= 0) { TCCR4A = 0; TCCR4B = 0; digitalWrite(6, LOW); return; }
  TCCR4A = _BV(COM4A1) | _BV(WGM40);
  TCCR4B = _BV(WGM42) | _BV(CS40);
  uint32_t top = constrain(2000000L / (hz * 64), 20, 65535);
  TCCR3A = 0; TCCR3B = _BV(WGM32) | _BV(CS31); TCNT3 = 0;
  OCR3A = top - 1;
  TIMSK3 = _BV(OCIE3A);
}

ISR(TIMER3_COMPA_vect) {
  uint8_t i = idx = (idx + 1) & 63;
  if (shape == 1) OCR4A = pgm_read_byte(&SINE[i]);
  else if (shape == 2) OCR4A = i < 32 ? i * 8 : (63 - i) * 8;
  else OCR4A = i * 4;
}

void report() {
  Serial.print("Square on pin 11: "); Serial.print(setSquare(squareHz), 2); Serial.println(" Hz");
  Serial.print("PWM on pin 9: "); Serial.print(duty); Serial.println("% duty");
  Serial.print("Wave on pin 6: ");
  const char* names[] = {"off", "sine", "triangle", "sawtooth"};
  Serial.print(names[shape]);
  if (shape) { Serial.print(" at "); Serial.print(waveHz); Serial.print(" Hz"); }
  Serial.println();
}

void setup() {
  Serial.begin(115200);
  pinMode(6, OUTPUT); pinMode(9, OUTPUT); pinMode(11, OUTPUT);
  analogWrite(9, duty * 255L / 100);
  setWave(1, waveHz);                 // a 10 Hz sine to start with
  Serial.println("Signal generator. Commands: s<Hz>, d<duty %>, w<shape> <Hz>, ?");
  report();
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c != '\n' && c != '\r') { if (len < sizeof(line) - 1) line[len++] = c; continue; }
    line[len] = 0;
    if (len) {
      long a = 0, b = 0;
      sscanf(line + 1, "%ld %ld", &a, &b);
      switch (line[0]) {
        case 's': squareHz = constrain(a, 0, 100000); break;
        case 'd': duty = constrain(a, 0, 100); analogWrite(9, duty * 255L / 100); break;
        case 'w': waveHz = constrain(b ? b : waveHz, 1, 200); setWave(constrain(a, 0, 3), waveHz); break;
      }
      report();
    }
    len = 0;
  }
}
