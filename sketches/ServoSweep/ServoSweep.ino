// Sweeps an SG90 servo on pin 9 from 0 to 180 degrees and back.
// Wire: brown -> GND, red -> 5V, orange -> pin 9.
#include <Servo.h>

Servo servo;

void setup() {
  servo.attach(9);
}

void loop() {
  for (int a = 0; a <= 180; a++) { servo.write(a); delay(12); }
  for (int a = 180; a >= 0; a--) { servo.write(a); delay(12); }
}
