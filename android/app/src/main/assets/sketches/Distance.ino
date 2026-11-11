// Measures distance with the HC-SR04 ultrasonic sensor.
// Wiring: VCC -> 5V, GND -> GND, Trig -> pin 12, Echo -> pin 11. Plotter at 9600 baud.
// Sound travels about 0.0343 cm per microsecond, and the echo time covers the distance twice.

const int TRIG = 12, ECHO = 11;

void setup() {
  Serial.begin(9600);
  pinMode(TRIG, OUTPUT);
  pinMode(ECHO, INPUT);
}

void loop() {
  digitalWrite(TRIG, LOW); delayMicroseconds(2);
  digitalWrite(TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(TRIG, LOW);
  unsigned long us = pulseIn(ECHO, HIGH, 30000UL);   // no echo within 30 ms: out of range
  if (us) { Serial.print("cm:"); Serial.println(us * 0.0343 / 2, 1); }
  else Serial.println("cm:0");
  delay(100);
}
