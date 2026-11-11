// Lights the on-board LED while a button on pin 2 is pressed, and reports changes at 9600 baud.
// Wire: one side of the button to pin 2, the other to GND (the internal pull-up is used).
const int BUTTON = 2;
int last = HIGH;

void setup() {
  pinMode(BUTTON, INPUT_PULLUP);
  pinMode(LED_BUILTIN, OUTPUT);
  Serial.begin(9600);
}

void loop() {
  int now = digitalRead(BUTTON);
  digitalWrite(LED_BUILTIN, now == LOW ? HIGH : LOW);
  if (now != last) {
    Serial.println(now == LOW ? "pressed" : "released");
    last = now;
    delay(20);
  }
}
