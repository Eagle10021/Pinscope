const int LED = 13;
const int BUTTON = 2;
int buttonState = 0;
void setup() { Serial.begin(9600); pinMode(LED, OUTPUT); pinMode(BUTTON, INPUT); }
void loop() { buttonState = digitalRead(BUTTON); digitalWrite(LED, buttonState == HIGH ? HIGH : LOW); }
