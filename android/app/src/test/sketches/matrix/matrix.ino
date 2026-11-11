#include <LedControl.h>
LedControl lc(12, 10, 11, 1);
void setup() { lc.shutdown(0, false); lc.setIntensity(0, 8); lc.clearDisplay(0); lc.setRow(0, 0, B10100101); lc.setRow(0, 7, B11110000); }
void loop() {}
