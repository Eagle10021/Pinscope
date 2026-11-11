#include <DHT.h>
DHT dht(2, DHT11);
void setup() { Serial.begin(115200); dht.begin(); }
void loop() { delay(2100); Serial.print("T:"); Serial.print(dht.readTemperature(), 1); Serial.print(" H:"); Serial.println(dht.readHumidity(), 0); }
