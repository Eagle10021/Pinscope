// Builds android/app/src/main/assets/pinscope.json from the Mega Kit Pinouts site data.
// Usage: node tools/build-data.mjs <path to Mega-Pinouts/data.js>
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const src = process.argv[2];
if (!src) { console.error('usage: node tools/build-data.mjs <data.js>'); process.exit(1); }
const SITE = 'https://pinouts.01blue.org/';

const ctx = {};
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(src, 'utf8') + `
;globalThis.__d = { SOURCES, CATEGORIES, COMPONENTS, PORTS, PWM, INTS, SERIAL, PCINT, SPECIAL, POWER, CAT_KEYS, BOARD, KIT_RESISTORS, RESISTORS, RES_NONE };`, ctx);
const D = ctx.__d;

// ---------- pin facts (same rules as the site) ----------
function pinInfo(ref) {
  if (D.POWER[ref]) return { title: D.POWER[ref].name, tags: [...D.POWER[ref].tags], lines: [], desc: D.POWER[ref].desc, num: null };
  const m = /^([DA])(\d+)$/.exec(ref);
  const analog = m[1] === 'A';
  const k = +m[2];
  const n = analog ? 54 + k : k;
  const tags = analog ? ['analog'] : ['digital'];
  const lines = [];
  if (analog) {
    lines.push(['Analog in', `A${k}, 10-bit (0–1023)`]);
    lines.push(['As digital', `pin ${n}`]);
    lines.push(['AVR port', (k < 8 ? 'PF' : 'PK') + (k % 8)]);
    if (k >= 8) lines.push(['Pin change int', `PCINT${16 + k - 8}`]);
  } else {
    lines.push(['AVR port', D.PORTS[n]]);
    if (D.PWM[n]) { tags.push('pwm'); lines.push(['PWM', `analogWrite(), timer ${D.PWM[n].slice(2, 3)} (${D.PWM[n]})`]); }
    if (n in D.INTS) { tags.push('interrupt'); lines.push(['Interrupt', `attachInterrupt(${D.INTS[n]}, …) or digitalPinToInterrupt(${n})`]); }
    if (D.SERIAL[n]) { tags.push('serial'); lines.push(['Serial', D.SERIAL[n]]); }
    if (n === 20 || n === 21) tags.push('i2c');
    if (n >= 50 && n <= 53) tags.push('spi');
    if (D.SPECIAL[n]) lines.push(['Special', D.SPECIAL[n]]);
    if (n in D.PCINT) lines.push(['Pin change int', `PCINT${D.PCINT[n]}`]);
  }
  const title = analog ? `A${k}` : `Pin ${k}`;
  return { title, tags, lines, desc: '', num: n };
}

// ---------- which kit parts use which pins ----------
const REF_RE = /^(D\d{1,2}|A\d{1,2}|5V|3V3|GND|VIN)$/;
function refsOf(s) {
  const out = [];
  for (const part of String(s).split(/,\s*/)) {
    const r = /^D(\d+)–D(\d+)$/.exec(part);
    if (r) { for (let i = +r[1]; i <= +r[2]; i++) out.push('D' + i); continue; }
    if (REF_RE.test(part)) out.push(part); else return [];
  }
  return out;
}
const usage = {};
for (const c of D.COMPONENTS) for (const w of c.wiring) for (const [pin, mega] of w.rows)
  for (const r of refsOf(mega)) (usage[r] ||= []).push({ part: c.id, set: w.title, pin });

// ---------- board geometry ----------
// Millimetres from the top-left corner of the board, seen from the top with USB on the left.
// Positions come from KiCad's Arduino_Mega template (kicad-templates, Projects/Arduino_Mega):
// board 101.6 × 53.34 mm, headers on the 2.54 mm grid with the 0.16" (4.064 mm) step between pins 8 and 7.
const P = 2.54, TOP = 2.54, BOT = 50.8;
const pins = [];
const add = (ref, label, x, y, side, header) => pins.push({ ref, label, x: +x.toFixed(3), y: +y.toFixed(3), side, header });
const row = (list, x0, y, side, header) => list.forEach((p, i) => add(p[0], p[1], x0 + i * P, y, side, header));
const d = n => ['D' + n, String(n)];
row([['D21', 'SCL'], ['D20', 'SDA'], ['AREF', 'AREF'], ['GND', 'GND'], ...[13, 12, 11, 10, 9, 8].map(d)], 18.796, TOP, 'top', 'PWM / I2C');
row([7, 6, 5, 4, 3, 2, 1, 0].map(d), 45.72, TOP, 'top', 'PWM');
row([14, 15, 16, 17, 18, 19, 20, 21].map(d), 68.58, TOP, 'top', 'Communication');
row([['NC', ''], ['IOREF', 'IOREF'], ['RESET', 'RESET'], ['3V3', '3.3V'], ['5V', '5V'], ['GND', 'GND'], ['GND', 'GND'], ['VIN', 'VIN']], 27.94, BOT, 'bottom', 'Power');
row([0, 1, 2, 3, 4, 5, 6, 7].map(i => ['A' + i, 'A' + i]), 50.8, BOT, 'bottom', 'Analog in');
row([8, 9, 10, 11, 12, 13, 14, 15].map(i => ['A' + i, 'A' + i]), 73.66, BOT, 'bottom', 'Analog in');
for (let r = 0; r < 18; r++) {
  const y = TOP + r * P;
  const pair = r === 0 ? [['5V', '5V'], ['5V', '5V']] : r === 17 ? [['GND', 'GND'], ['GND', 'GND']] : [d(20 + r * 2), d(21 + r * 2)];
  add(pair[0][0], pair[0][1], 93.98, y, 'left', 'Digital 22–53');
  add(pair[1][0], pair[1][1], 96.52, y, 'right', 'Digital 22–53');
}

const refName = r => /^D\d+$/.test(r) ? r.slice(1) : r === '3V3' ? '3.3V' : r;
for (const p of pins) Object.assign(p, pinInfo(p.ref), { name: refName(p.ref), uses: usage[p.ref] || [] });

// Alignment anchors: the outer pin of each corner header (SCL, D21 on the comms header, NC, A15).
const idx = (ref, header) => pins.findIndex(p => p.ref === ref && p.header === header);
const anchors = [
  { pin: idx('D21', 'PWM / I2C'), name: 'SCL', hint: 'Top left: the first pin of the header next to AREF' },
  { pin: idx('D21', 'Communication'), name: '21', hint: 'Top right: the last pin of the Communication header' },
  { pin: idx('A15', 'Analog in'), name: 'A15', hint: 'Bottom right: the last analog pin' },
  { pin: pins.findIndex(p => p.ref === 'NC'), name: 'NC', hint: 'Bottom left: the first pin of the power header' },
];
if (anchors.some(a => a.pin < 0)) throw new Error('anchor not found');

// ---------- components ----------
const link = l => ({ label: l.label, url: l.local ? SITE + l.url : l.url });
const components = D.COMPONENTS.map(c => ({
  id: c.id, name: c.name, part: c.part, cat: c.cat, catKey: D.CAT_KEYS[c.cat], lesson: c.lesson ?? null,
  blurb: c.blurb || '', layout: c.layout || 'strip',
  pins: c.pins.map(([num, name, desc]) => ({ num: String(num), name, desc })),
  wiring: c.wiring.map(w => ({ title: w.title, rows: w.rows.map(([pin, mega, note]) => ({ pin, mega, note: note || '', refs: refsOf(mega) })) })),
  notes: c.notes || [],
  links: (c.links || []).map(link),
  resistors: (D.RESISTORS[c.id] || []).map(([where, value, why]) => ({ where, value, why })),
  noResistor: D.RES_NONE[c.id] || null,
}));

const out = {
  generated: new Date().toISOString().slice(0, 10),
  site: SITE,
  board: { name: 'Arduino Mega 2560', rev: D.BOARD.rev, width: 101.6, height: 53.34,
    usb: [-6.35, 9.525, 15.875, 11.43], jack: [-1.905, 41.275, 13.335, 8.89], facts: D.BOARD.facts.map(([k, v]) => ({ k, v })), howToTell: D.BOARD.howToTell },
  categories: D.CATEGORIES.map(c => ({ name: c, key: D.CAT_KEYS[c] })),
  pins, anchors, components,
  kitResistors: D.KIT_RESISTORS.map(([value, bands]) => ({ value, bands })),
  sources: Object.values(D.SOURCES).map(link),
};
const dest = path.join(path.dirname(fileURLToPath(import.meta.url)), '../android/app/src/main/assets/pinscope.json');
fs.writeFileSync(dest, JSON.stringify(out));
console.log(`wrote ${dest}: ${pins.length} pins, ${components.length} parts`);
