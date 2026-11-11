// Plots light level from the kit's photoresistor.
// Wiring: 5V -> photoresistor -> A0, and a 10 kOhm resistor from A0 to GND. More light, higher reading.
// Open the plotter at 9600 baud. Cover the sensor or shine a light on it; under mains lighting a fast
// scope on A0 can show the lamp flicker at twice the mains frequency.
// Simulator: photo A0

float smooth = 0;

void setup() {
  Serial.begin(9600);
}

void loop() {
  int raw = analogRead(A0);
  smooth += (raw - smooth) * 0.2;
  Serial.print("light:"); Serial.print(raw * 100.0 / 1023, 1);
  Serial.print(" smooth:"); Serial.println(smooth * 100.0 / 1023, 1);
  delay(50);
}
