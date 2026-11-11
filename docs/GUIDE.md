# Pinscope user guide

Pinscope has four tabs: **Scan**, **Board**, **Parts** and **Bench**. On phones they're along the bottom; on tablets, down the left side.

## Scan: pins on your real board

1. Tap **Start live view** and hold the device above the Mega so the whole board is in view.
2. When the status reads **Tracking**, every header pin is labelled. Labels on the long double header turn sideways so they never cover other pins.
3. Tap any pin for its details. The filter chips (PWM, Analog, Interrupt…) highlight one kind of pin, and choosing a kit part in **Parts** highlights the pins it uses.

Pinscope recognises the ELEGOO Mega straight away. For another Mega, teach it once:

1. In live view, tap the shutter.
2. Drag the four markers onto the pins they name (SCL, 21, A15 and NC). Pinch to zoom for precision.
3. Tap **Done**. Pinscope remembers what your board looks like and tracks it from then on.

The camera keeps optical stabilisation on and the screen awake while it's open.

**Photos:** the shutter freezes a sharp photo with the same labels. The toolbar can re-align it, hide labels, or show live pin states (with Firmata running).

**Notes on photos:** write on the photo with a pen. Phones without a pen can turn on finger drawing with the ✍ button. Choose a colour, undo, clear, or save the photo with its notes to *Pictures/Pinscope*. Holding the pen's side button erases.

## Board: diagram and wiring sketchpad

A to-scale Mega 2560. Tap a pin for details, pinch to zoom, and double-tap to reset.

**Drawing wires:**

- Draw from a pin with the pen (or with a finger after tapping ✍). The wire snaps onto the pin, and onto a second pin if it ends on one.
- Name each wire ("LED", "trig", "button") and pick a colour.
- Drag a wire's end to re-route it (it snaps when released), or drag its middle to bend it.
- Stroke across wires with the pen's side button held to erase them.
- **Make sketch** opens the editor with `const int LED = 9;` style constants and a starting `pinMode` for each, guessed from the label.

**Pen hover:** hold the pen just above any pin to see what it is, without tapping.

Samsung's *Air command* opens if the side button is pressed while the pen hovers. To use the button only inside apps, turn that off in Settings → Advanced features → S Pen → Air command.

## Parts: the kit

All 29 parts of the ELEGOO Mega starter kit, by category. Each has its pinout, the kit lesson's wiring, resistor advice, notes and datasheets. **Show on my board** highlights its pins on the diagram or your photo.

## Bench: talking to the board

Plug the Mega into the phone with an OTG cable. Android offers to open Pinscope, which also gives it USB access. While connected, the connection card shrinks to one slim row.

### Serial monitor and plotter

- **Monitor:** choose the baud rate your sketch uses. Lines can be sent with a newline, CR, both or nothing. Timestamps and auto-scroll are optional.
- **Plotter:** prints like `Serial.print("temp:"); Serial.println(t);` become labelled lines. Plain numbers separated by spaces or commas also work.

### Firmata pins and instruments

Switch to **Firmata pins**. If the board doesn't answer, tap **Upload StandardFirmata**. Each pin then offers the modes the board reports. The **Instruments** row has:

- **Voltmeter:** any analog pin, with ÷2, ÷3 and ÷5 divider presets (resistor values are listed). **Calibrate** once against a multimeter reading, or the board's own 5V pin. Over a phone's USB the board's 5V rail is often only about 4.6 V, which makes uncalibrated readings high.
- **Oscilloscope:**
  - *Firmata* mode watches up to four slow signals.
  - *Fast* mode uploads a small Pinscope sketch that samples one pin about 77,000 times a second, with the trigger handled on the board.
  - **Signal generator** (Fast mode): a square wave on pin 11 (1 Hz to 50 kHz), PWM duty on pin 9, and a sine, triangle or sawtooth on pin 6 (through an RC filter). A pull-up switch on the scope pin makes button tests need no resistor.
  - **Lab demos** set up the generator and scope for an experiment, with what to build and what to expect: PWM duty, frequency and period, RC charging (τ = 10 ms), PWM to DC, sine synthesis, button bounce and aliasing.
  - Tap the screen, or touch it with a pen, to place cursors A and B. The readout shows Δt, frequency and ΔV.
- **Logic monitor:** up to six digital inputs over the last 10 seconds.
- **PWM generator:** duty cycle on any PWM pin, with the waveform and average voltage.
- **Servo tester:** angle, sweep, and pulse range. *Standard* is 544–2400 µs, *SG90 full* is 500–2500 µs, and you can save your own preset. The flip button mirrors the dial.

On tablets in landscape, each instrument shows its display on the left and its controls on the right.

### Sketches

- **My sketches:** **New** opens the editor. It has highlighting, line numbers, auto-indent and a key bar with brackets and common calls.
  - **Verify** compiles on the phone (about a second); errors list their line, and tapping one jumps to it.
  - **Upload** compiles, backs up the board, writes the sketch and checks it, then opens the monitor at the sketch's `Serial.begin()` speed.
  - **Simulate** runs the sketch on the simulator instead of a board.
- **Your own sketch:** upload a `.hex` file (Arduino IDE: Sketch → Export Compiled Binary).
- **From the web:** paste a link to an `.ino` file (GitHub links work) or the code itself. It compiles on the phone.
- **Quick start:** Pinscope's own example sketches, with upload, edit and simulate buttons. Besides Blink and friends: a serial-controlled **signal generator** for a lab oscilloscope (square, PWM, sine, triangle, sawtooth), **RC charging**, **button bounce**, a **light meter**, **distance** (HC-SR04) and **temperature and humidity** (DHT11).
- **Arduino examples:** 179 examples from the Arduino IDE and the kit's libraries, precompiled and searchable. Each can be uploaded, edited as a copy, or tried in the simulator (🧪).
- **Backups:** the sketch that was on the board before each upload (Pinscope's own sketches aren't backed up). Tap **Restore** to put it back, or delete it.

### Compile server (optional)

Only needed for phones without the built-in compiler, or for libraries Pinscope doesn't include. Run it on a computer with the Arduino IDE 2:

```bash
python3 tools/compile-server/server.py --port 8099
```

Then enter its address (for example `http://my-computer:8099`) when Pinscope asks. Keep it on your home network or Tailscale; `--token` adds a shared secret.

## Simulator

Tap 🧪 on any example, or **Simulate** in the editor. The sketch runs on a simulated ATmega2560 in real time: it really executes the compiled program, timers, interrupts and all. The board diagram shows pin states live, and the **Serial** tab shows output and sends input.

**Parts:**

- Opening a sketch adds the parts it uses, from its code. For example, `pinMode(13, OUTPUT)` becomes an LED and `LiquidCrystal lcd(12, 11, 5, 4, 3, 2)` an LCD on those pins.
- Otherwise the last set-up is reused, or parts are made from the wires drawn on the Board tab.
- **Add a part** lists every kit part with its picture. It starts on the kit lesson's pins; tap a part's pins to change them.

| Kind | Parts |
|---|---|
| Lights and displays | LED, RGB LED, 1- and 4-digit 7-segment, 74HC595 with LEDs or a 7-segment, LCD1602, 8×8 MAX7219 matrix |
| Inputs | Push button, tilt switch, potentiometer, joystick, 4×4 keypad, rotary encoder, IR remote |
| Sensors | Photoresistor, thermistor, water level, sound, PIR motion, HC-SR04 ultrasonic, DHT11, MPU-6050 (it can use the device's own motion sensors), DS1307 clock |
| Outputs | Buzzer (shows the note), servo, relay, stepper with ULN2003, DC motor with L293D |
| Bench | Oscilloscope probe (span, trigger, hold; samples like a real scope, so slow spans alias), RC filter (R then C to GND, charges with τ = R × C), bouncy button contacts |

The **Serial** tab has a Monitor and a Plotter, like the Bench.

A sketch can set up its own simulation in one comment line, for example `// Simulator: rc 8 A0 1k 10u; scope A0 100ms 0.2V; button 2 bouncy`. Pinscope's Quick start demos use this.

**Warnings** appear when the sketch would hurt a real board or has a common mistake:

- a button shorting a HIGH output to GND;
- two outputs fighting over a pin;
- an LED on a pin left as an input;
- a part on Serial's pins 0 and 1;
- two I2C parts on one address.

Pause, reset and the speed readout are in the top bar. Tablets show the board and parts side by side.
