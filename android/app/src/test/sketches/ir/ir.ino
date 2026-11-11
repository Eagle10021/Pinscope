#include <IRremote.hpp>
void setup() { Serial.begin(115200); IrReceiver.begin(11); }
void loop() { if (IrReceiver.decode()) { Serial.print("cmd:"); Serial.println(IrReceiver.decodedIRData.command, HEX); IrReceiver.resume(); } }
