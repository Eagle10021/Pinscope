#!/usr/bin/env python3
"""Compiles the Arduino example sketches for the Mega 2560 and bundles them in the app.

Sources: the Arduino IDE's built-in examples (01.Basics …), and the examples of every library in the
given library folders. Each example that builds for the Mega becomes assets/examples/<id>.ino + .hex;
assets/examples/index.json lists them with their category and licence. Examples that fail to build
(for example USB keyboard sketches, which need a different chip) are skipped.

    python3 -I tools/build-examples.py [--jobs 4]
"""

import argparse
import concurrent.futures
import json
import os
import re
import shutil
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "android/app/src/main/assets/examples")
FQBN = "arduino:avr:mega:cpu=atmega2560"
IDE = "/opt/arduino-ide/resources/app/lib/backend/resources"
CLI = shutil.which("arduino-cli") or os.path.join(IDE, "arduino-cli")
BUILTIN = os.path.join(IDE, "Examples")
LIB_DIRS = [os.path.expanduser("~/.arduino15/libraries"), os.path.expanduser("~/Arduino/libraries")]
# Libraries for other boards, or for hardware outside the ELEGOO Mega kit (SD is also GPL)
SKIP_LIBS = {"Keyboard", "Mouse", "Arduino_BuiltIn", "SD", "Ethernet", "TFT"}


def sketches(base):
    """Yields (relative category path, sketch dir) for every folder holding <name>.ino."""
    for d, _, files in os.walk(base):
        name = os.path.basename(d)
        if f"{name}.ino" in files:
            yield os.path.relpath(os.path.dirname(d), base), d


def lib_license(lib):
    for f in sorted(os.listdir(lib)):
        if f.upper().startswith("UNLICENSE"):
            return "Unlicense"
        if f.upper().startswith(("LICENSE", "LICENCE", "COPYING", "README")):
            text = open(os.path.join(lib, f), errors="replace").read(3000)
            for key, name in [("MIT License", "MIT"), ("Permission is hereby granted, free of charge", "MIT"),
                              ("GNU LESSER", "LGPL"), ("GNU Lesser", "LGPL"), ("GNU GENERAL", "GPL"),
                              ("Apache License", "Apache 2.0"), ("Unlicense", "Unlicense"), ("unlicense", "Unlicense"),
                              ("Redistribution and use", "BSD"), ("Creative Commons Attribution", "CC BY 3.0")]:
                if key in text:
                    return name
    props = os.path.join(lib, "library.properties")
    if os.path.exists(props):
        for line in open(props, errors="replace"):
            if line.lower().startswith("license="):
                return line.split("=", 1)[1].strip()
    return None


def lib_name(lib):
    props = os.path.join(lib, "library.properties")
    if os.path.exists(props):
        for line in open(props, errors="replace"):
            if line.startswith("name="):
                return line.split("=", 1)[1].strip()
    return os.path.basename(lib)


def slug(s):
    return re.sub(r"[^A-Za-z0-9]+", "-", s).strip("-")


def compile_one(job):
    sid, sketch_dir, libs = job
    with tempfile.TemporaryDirectory(prefix="pinscope-ex-") as out:
        cmd = [CLI, "compile", "--fqbn", FQBN, "--output-dir", out, sketch_dir]
        for l in libs:
            cmd[2:2] = ["--libraries", l]
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if p.returncode != 0:
            return sid, None, None
        name = os.path.basename(sketch_dir)
        hexdata = open(os.path.join(out, f"{name}.ino.hex")).read()
        m = re.search(r"Sketch uses (\d+) bytes", p.stdout)
        return sid, hexdata, int(m[1]) if m else None


def first_comment(src):
    m = re.match(r"\s*/\*(.*?)\*/", src, re.S)
    text = m[1] if m else "\n".join(l[2:] for l in src.splitlines()[:6] if l.strip().startswith("//"))
    lines = [l.strip(" *\t") for l in text.splitlines()]
    lines = [l for l in lines if l and not l.lower().startswith(("created", "modified", "by ", "this example code", "http"))]
    return " ".join(lines[1:3] if len(lines) > 1 else lines)[:160]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", type=int, default=4)
    a = ap.parse_args()
    libs = [d for d in LIB_DIRS if os.path.isdir(d)]
    entries, jobs = [], []
    for cat, d in sketches(BUILTIN):
        group = re.sub(r"^\d+\.", "", cat.split(os.sep)[0]).replace("_", " ")
        sid = "builtin-" + slug(cat + "-" + os.path.basename(d))
        entries.append({"id": sid, "name": os.path.basename(d), "group": group, "library": None, "license": "Public domain", "dir": d})
        jobs.append((sid, d, libs))
    for base in libs:
        for lib in sorted(os.listdir(base)):
            path = os.path.join(base, lib)
            if lib in SKIP_LIBS or not os.path.isdir(os.path.join(path, "examples")):
                continue
            name, lic = lib_name(path), lib_license(path)
            for cat, d in sketches(os.path.join(path, "examples")):
                sid = "lib-" + slug(lib + "-" + cat + "-" + os.path.basename(d))
                entries.append({"id": sid, "name": os.path.basename(d), "group": name, "library": name, "license": lic, "dir": d})
                jobs.append((sid, d, libs))
    print(f"compiling {len(jobs)} examples with {a.jobs} jobs…", flush=True)
    results = {}
    with concurrent.futures.ThreadPoolExecutor(a.jobs) as ex:
        for i, (sid, hexdata, size) in enumerate(ex.map(compile_one, jobs), 1):
            results[sid] = (hexdata, size)
            if i % 20 == 0:
                print(f"  {i}/{len(jobs)}", flush=True)
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    index = []
    for e in entries:
        hexdata, size = results.get(e["id"], (None, None))
        if not hexdata:
            continue
        d = e.pop("dir")
        src = open(os.path.join(d, os.path.basename(d) + ".ino"), errors="replace").read()
        extra = [f for f in os.listdir(d) if f.endswith((".h", ".cpp", ".c")) or (f.endswith(".ino") and f != os.path.basename(d) + ".ino")]
        open(os.path.join(OUT, e["id"] + ".ino"), "w").write(src)
        open(os.path.join(OUT, e["id"] + ".hex"), "w").write(hexdata)
        baud = re.search(r"Serial\.begin\((\d+)", src)
        e.update({"desc": first_comment(src), "size": size, "baud": int(baud[1]) if baud else None, "multiFile": bool(extra)})
        index.append(e)
    json.dump(index, open(os.path.join(OUT, "index.json"), "w"), indent=0)
    skipped = len(entries) - len(index)
    print(f"bundled {len(index)} examples ({skipped} do not build for the Mega)")


if __name__ == "__main__":
    main()
