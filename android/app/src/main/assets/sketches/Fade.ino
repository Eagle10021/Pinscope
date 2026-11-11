// Fades an LED on PWM pin 9 up and down. Wire: pin 9 -> 220 ohm -> LED -> GND.
const int LED = 9;
int level = 0;
int step = 5;

void setup() {
  pinMode(LED, OUTPUT);
}

void loop() {
  analogWrite(LED, level);
  level += step;
  if (level <= 0 || level >= 255) step = -step;
  delay(30);
}
