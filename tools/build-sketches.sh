#!/usr/bin/env bash
# Compiles the bundled example sketches for the Arduino Mega 2560 into the app's assets.
# Needs arduino-cli with the arduino:avr core and the Firmata library (the Arduino IDE 2 includes both).
set -euo pipefail
cd "$(dirname "$0")/.."
CLI="${ARDUINO_CLI:-$(command -v arduino-cli || echo /opt/arduino-ide/resources/app/lib/backend/resources/arduino-cli)}"
FQBN="arduino:avr:mega:cpu=atmega2560"
OUT="android/app/src/main/assets/sketches"
FIRMATA="${FIRMATA_DIR:-$HOME/.arduino15/libraries/Firmata/examples/StandardFirmata}"
mkdir -p "$OUT" build/sketches
build() { # name, sketch dir
  "$CLI" compile --fqbn "$FQBN" --libraries "${ARDUINO_LIBS:-$HOME/.arduino15/libraries}" --output-dir "build/sketches/$1" "$2" >/dev/null
  cp "build/sketches/$1/$(basename "$2").ino.hex" "$OUT/$1.hex"
  echo "built $1 ($(stat -c %s "$OUT/$1.hex") bytes of hex)"
}
build StandardFirmata "$FIRMATA"
for d in sketches/*/; do
  n="$(basename "$d")"
  build "$n" "${d%/}"
  cp "$d$n.ino" "$OUT/$n.ino"   # source too, for starting a new sketch from an example
done
cp sketches/index.json "$OUT/index.json"
