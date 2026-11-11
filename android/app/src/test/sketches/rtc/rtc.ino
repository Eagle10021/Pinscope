#include <RTClib.h>
RTC_DS1307 rtc;
void setup() { Serial.begin(115200); if (!rtc.begin()) Serial.println("no rtc"); }
void loop() { DateTime n = rtc.now(); Serial.print("Y:"); Serial.print(n.year()); Serial.print(" M:"); Serial.println(n.month()); delay(500); }
