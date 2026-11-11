// Sends three labelled waves at 115200 baud, for trying the plotter without any wiring.
float t = 0;

void setup() {
  Serial.begin(115200);
}

void loop() {
  Serial.print("sine:");
  Serial.print(sin(t) * 100);
  Serial.print(" cosine:");
  Serial.print(cos(t) * 60);
  Serial.print(" saw:");
  Serial.println(fmod(t * 20, 100) - 50);
  t += 0.08;
  delay(20);
}
