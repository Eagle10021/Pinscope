// Charges and discharges a capacitor through a resistor and plots the voltage.
// Wiring: pin 8 -> 1 kOhm resistor -> A0, and a 10 uF capacitor from A0 to GND (stripe to GND).
// Time constant tau = R * C = 10 ms: after 10 ms the capacitor is at 63% of 5 V (3.16 V).
// Open the plotter at 115200 baud. The "tau" line is the 63% mark: the curve crosses it one tau after each switch.
// Simulator: rc 8 A0 1k 10u; scope A0 100ms 0.2V; scope 8 100ms

const int DRIVE = 8;
const unsigned long HALF_MS = 60;   // six time constants per half cycle

void setup() {
  Serial.begin(115200);
  pinMode(DRIVE, OUTPUT);
}

void loop() {
  for (int level = HIGH; level >= LOW; level--) {
    digitalWrite(DRIVE, level);
    unsigned long t0 = micros();
    while (micros() - t0 < HALF_MS * 1000UL) {
      float v = analogRead(A0) * 5.0 / 1023.0;
      Serial.print("cap:"); Serial.print(v, 2);
      Serial.print(" input:"); Serial.print(level ? 5.0 : 0.0);
      Serial.print(" tau:"); Serial.println(level ? 3.16 : 1.84);
      delayMicroseconds(500);
    }
  }
}
