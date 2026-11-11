#!/usr/bin/env bash
# Renders Fritzing breadboard images of the simulator's parts to PNG (android assets/parts).
# The images are from the Fritzing parts library, licensed CC BY-SA 3.0 (see assets/parts/LICENSE.txt).
#   FRITZING_PARTS=~/path/to/fritzing-parts tools/build-part-art.sh
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="${FRITZING_PARTS:-$HOME/GamesAndFiles/Fritzing/fritzing-parts}/svg"
OUT=android/app/src/main/assets/parts
rm -rf "$OUT"; mkdir -p "$OUT"
while IFS='|' read -r key file; do
  [ -z "$key" ] && continue
  rsvg-convert -a -h 300 "$SRC/$file" -o "$OUT/$key.png"
  echo "$key <- $file"
done <<'MAP'
led|core/breadboard/LED-5mm-red-leg.svg
rgb|core/breadboard/led-rgb-4pin-anode-leg.svg
button|core/breadboard/basic_pbutton.svg
tilt|core/breadboard/tilt-switch_breadboard.svg
pot|core/breadboard/potentiometer_rotary_9mm_6_breadboard.svg
ldr|core/breadboard/ldr.svg
thermistor|core/breadboard/ceramic_disk_thermistor_leg.svg
joystick|core/breadboard/Joystick-Breakout-v11_breadboard.svg
pir|core/breadboard/lhi958-pir_breadboard.svg
buzzer|core/breadboard/Buzzer-v15_breadboard.svg
servo|core/breadboard/Dagu_DGServo_9g_Top_view_breadboard.svg
relay|core/breadboard/basic-relay-6p_1c569ac_002.svg
seg7|core/breadboard/7segment_13.svg
seg7x4|core/breadboard/sparkfun-displays_7-segment-4digit_breadboard.svg
sr595|core/breadboard/74HC595.svg
lcd|core/breadboard/lcd-GDM1602K.svg
encoder|core/breadboard/Rotary_Encoder.svg
hcsr04|core/breadboard/hc-sr04_bf8299a_002.svg
max7219|core/breadboard/20mm_8x8_dot_matrix_DIP_16_600mil_breadboard.svg
ir|core/breadboard/IR_Receiver_v14_breadboard.svg
stepper|core/breadboard/Stepper_Motor_Unipolar_breadboard_leg.svg
motor|core/breadboard/dc_motor_leg.svg
rtc|core/breadboard/DS1307_breadboard.svg
mpu6050|core/breadboard/MPU6050_GY521_9.svg
MAP
cat > "$OUT/LICENSE.txt" <<'LIC'
These part images are rendered from the Fritzing parts library (https://github.com/fritzing/fritzing-parts).
They are licensed under Creative Commons Attribution-ShareAlike 3.0 Unported (CC BY-SA 3.0):
https://creativecommons.org/licenses/by-sa/3.0/
Images by the Fritzing project and its contributors.
LIC
du -sh "$OUT"
