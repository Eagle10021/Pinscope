// Prints the reading on A0 (0-1023) and its voltage, ten times a second, at 9600 baud.
// Try a potentiometer: ends to 5V and GND, middle pin to A0.
void setup() {
  Serial.begin(9600);
}

void loop() {
  int raw = analogRead(A0);
  Serial.print("A0:");
  Serial.print(raw);
  Serial.print(" volts:");
  Serial.println(raw * 5.0 / 1023.0, 2);
  delay(100);
}
