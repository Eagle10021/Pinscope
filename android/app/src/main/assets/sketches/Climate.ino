// Plots temperature and humidity from the kit's DHT11 sensor.
// Wiring (3-pin module): S -> pin 2, middle -> 5V, - -> GND. Plotter at 9600 baud.
// The DHT11 updates about once a second; breathe on it to watch the humidity jump.

#include <DHT.h>

DHT dht(2, DHT11);

void setup() {
  Serial.begin(9600);
  dht.begin();
}

void loop() {
  delay(2000);
  float t = dht.readTemperature(), h = dht.readHumidity();
  if (isnan(t) || isnan(h)) { Serial.println("Sensor not answering: check the wiring."); return; }
  Serial.print("temp_C:"); Serial.print(t, 1);
  Serial.print(" humidity_%:"); Serial.println(h, 0);
}
