// Counts how many edges one button press really makes. Mechanical contacts bounce, so a single press
// often looks like several presses to a fast microcontroller.
// Wiring: push button between pin 2 and GND (the internal pull-up is used). Serial monitor at 115200.
// Simulator: button 2 bouncy; scope 2 2ms falling

volatile unsigned int edges = 0;
volatile unsigned long first = 0, last = 0;

void onEdge() {
  unsigned long t = micros();
  if (edges == 0) first = t;
  last = t;
  edges++;
}

void setup() {
  Serial.begin(115200);
  pinMode(2, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(2), onEdge, CHANGE);
  Serial.println("Press the button.");
}

void loop() {
  // report once the pin has been quiet for 50 ms
  noInterrupts();
  unsigned int n = edges; unsigned long a = first, b = last;
  interrupts();
  if (n && micros() - b > 50000UL) {
    noInterrupts(); edges = 0; interrupts();
    Serial.print(digitalRead(2) ? "Released: " : "Pressed: ");
    Serial.print(n); Serial.print(n == 1 ? " edge" : " edges");
    if (n > 1) { Serial.print(" over "); Serial.print(b - a); Serial.print(" us (bounce)"); }
    Serial.println();
  }
}
