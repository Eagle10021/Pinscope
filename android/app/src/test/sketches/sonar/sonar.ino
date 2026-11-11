void setup() { Serial.begin(115200); pinMode(12, OUTPUT); pinMode(11, INPUT); }
void loop() {
  digitalWrite(12, LOW); delayMicroseconds(2); digitalWrite(12, HIGH); delayMicroseconds(10); digitalWrite(12, LOW);
  long us = pulseIn(11, HIGH, 30000);
  Serial.print("cm:"); Serial.println(us / 58);
  delay(100);
}
