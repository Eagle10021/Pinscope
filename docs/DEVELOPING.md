# Developing Pinscope

The app is in `android/`: Kotlin, Jetpack Compose with Material 3 Expressive, CameraX and OpenCV. Generated assets are built by the scripts in `tools/`.

## Building

```bash
node tools/build-data.mjs path/to/Mega-Pinouts/data.js   # pin and kit data → assets/pinscope.json
tools/build-sketches.sh                                  # Pinscope's sketches → assets/sketches
python3 -I tools/build-examples.py                       # Arduino examples → assets/examples
python3 -I tools/build-toolchain.py                      # on-phone compiler → jniLibs + assets/toolchain
tools/build-part-art.sh                                  # Fritzing part images → assets/parts
cd android && ./gradlew assembleRelease
```

- **Arduino scripts:** the sketch, example and toolchain scripts need the Arduino IDE 2 (for its `arduino-cli`, AVR core and libraries in `~/.arduino15` and `~/Arduino/libraries`).
- **Toolchain script:** downloads pinned Alpine Linux packages. Its output in `jniLibs/` is not committed.
- **Part art:** needs a local [fritzing-parts](https://github.com/fritzing/fritzing-parts) checkout (`FRITZING_PARTS=…`) and `rsvg-convert`.
- **Signing:** release signing reads `android/signing.properties`, which is not in the repository. Without it, release builds are unsigned.

## Tests

```bash
cd android && ./gradlew testDebugUnitTest
```

The JVM unit tests run real compiled sketches through the emulator:

- **AvrTest:** Serial output, `millis()`/`delay()` timing (Blink toggles every 500 ms exactly), `analogRead`, Servo pulses on Timer5, and the scope sketch's hardware-toggled square wave on pin 11.
- **PartsTest:** sketches built with the kit's own libraries (Adafruit DHT, LiquidCrystal, RTClib, IRremote, LedControl) against the simulated DHT11, HC-SR04, LCD1602, DS1307, IR remote and MAX7219. Their `.hex` files are in `src/test/sketches`.
- **SketchPartsTest:** that the parts inferred from sketch source match what each sketch uses.

## How it fits together

| Area | Files |
|---|---|
| Pin and kit data | `Data.kt` (reads `assets/pinscope.json`) |
| Camera and tracking | `LiveScan.kt`, `ScanScreen.kt`, `Detector.kt`, `BoardMemory.kt`, `Homography.kt` |
| USB, upload, Firmata | `UsbSerial.kt` (CDC-ACM and CH340), `Uploader.kt` (Intel HEX, STK500v2), `Firmata.kt`, `Bench.kt` |
| On-phone compiler | `Compiler.kt`, `tools/build-toolchain.py` |
| Simulator | `Avr.kt` (ATmega2560), `Simulator.kt` (run loop, parts, checks), `SketchParts.kt`, `SimulatorScreen.kt` |
| Pen | `Pen.kt`, plus hooks in `BoardDiagram.kt`, `ScanScreen.kt` and `Instruments.kt` |

**Tracking:** the app matches ORB features of a flattened photo of the board, tied to positions in millimetres, then follows them with optical flow between full matches. The ELEGOO preset is a saved feature set (`assets/boards`).

**Compiler:** GCC's `cc1plus`, `as`, `ld` and `objcopy` are musl programs. They ship as `lib*.so` files, so Android installs them as runnable files, and each runs through musl's loader. The sysroot (`assets/toolchain/sysroot.zip`) holds avr-libc for the ATmega2560 and the Arduino core and libraries, precompiled without LTO.

**Simulator:**

- `Avr` executes the AVR instruction set with the Mega's 3-byte program counter, plus GPIO ports A–L, timers 0–5 (normal, CTC, fast and phase-correct PWM), the ADC, USART0, external interrupts, EEPROM, and an interrupt-driven TWI master for I2C parts.
- Peripherals catch up every 64 cycles, or whenever the sketch reads or writes one of their registers, so timing stays exact while the core runs fast.
- Parts react to pin edges with cycle timestamps; timed responses (DHT11 bits, ultrasonic echoes, IR frames) are scheduled events.

## Conventions

Code comments are neutral and third person. The public repository holds no personal details.
