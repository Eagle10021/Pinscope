#!/usr/bin/env python3
"""Builds Pinscope's on-phone compiler for the Arduino Mega 2560.

1. Downloads Alpine Linux's ARM64 packages for avr-gcc, binutils and avr-libc (pinned versions).
   They are musl programs; the app runs them through musl's loader, which ships as a native library.
2. Puts the needed programs and shared libraries in android/app/src/main/jniLibs/arm64-v8a/ as lib*.so.
3. Builds a small sysroot: GCC's headers and libgcc for avr6, avr-libc trimmed to the ATmega2560, the
   Arduino core and libraries precompiled with the Arduino IDE's avr-gcc (no LTO), and a linker script.
   It is zipped into android/app/src/main/assets/toolchain/sysroot.zip with an index of the libraries.

    python3 -I tools/build-toolchain.py

Needs: curl, tar, the Arduino IDE 2 (avr core 1.8.x) and its libraries in ~/.arduino15 and ~/Arduino.
"""

import json
import os
import re
import shutil
import subprocess
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = os.path.join(ROOT, "build", "toolchain")
JNI = os.path.join(ROOT, "android/app/src/main/jniLibs/arm64-v8a")
ASSETS = os.path.join(ROOT, "android/app/src/main/assets/toolchain")
ALPINE = "https://dl-cdn.alpinelinux.org/alpine/v3.24"
PACKAGES = [
    ("community", "gcc-avr", "15.2.0-r0"), ("community", "binutils-avr", "2.45.1-r0"), ("community", "avr-libc", "2.3.1-r0"),
    ("main", "musl", "1.2.6-r3"), ("main", "libstdc++", "15.2.0-r5"), ("main", "libgcc", "15.2.0-r5"),
    ("main", "gmp", "6.3.0-r4"), ("main", "mpfr4", "4.2.2-r0"), ("main", "mpc1", "1.3.1-r1"),
    ("main", "isl26", "0.26-r2"), ("main", "zlib", "1.4-r0"), ("main", "zstd-libs", "1.5.7-r2"),
]
GCC_VER = "15.2.0"
# (file in the Alpine tree, name in jniLibs, soname the loader looks for or None for programs)
NATIVE = [
    ("lib/ld-musl-aarch64.so.1", "libpsmusl.so", "libc.musl-aarch64.so.1"),
    (f"usr/lib/gcc/avr/{GCC_VER}/cc1plus", "libpscc1plus.so", None),
    ("usr/avr/bin/as", "libpsas.so", None),
    ("usr/avr/bin/ld", "libpsld.so", None),
    ("usr/avr/bin/objcopy", "libpsobjcopy.so", None),
    ("usr/lib/libstdc++.so.6", "libpsstdcxx.so", "libstdc++.so.6"),
    ("usr/lib/libgcc_s.so.1", "libpsgccs.so", "libgcc_s.so.1"),
    ("usr/lib/libgmp.so.10", "libpsgmp.so", "libgmp.so.10"),
    ("usr/lib/libmpfr.so.6", "libpsmpfr.so", "libmpfr.so.6"),
    ("usr/lib/libmpc.so.3", "libpsmpc.so", "libmpc.so.3"),
    ("usr/lib/libisl.so.23", "libpsisl.so", "libisl.so.23"),
    ("usr/lib/libz.so.1", "libpsz.so", "libz.so.1"),
    ("usr/lib/libzstd.so.1", "libpszstd.so", "libzstd.so.1"),
]
HOME = os.path.expanduser("~")
HW = os.path.join(HOME, ".arduino15/packages/arduino/hardware/avr/1.8.8")
AVR_BIN = os.path.join(HOME, ".arduino15/packages/arduino/tools/avr-gcc/7.3.0-atmel3.6.1-arduino7/bin")
LIB_DIRS = [os.path.join(HW, "libraries"), os.path.join(HOME, ".arduino15/libraries"), os.path.join(HOME, "Arduino/libraries")]
# Libraries for other boards or hardware outside the kit
SKIP_LIBS = {"Keyboard", "Mouse", "HID", "Arduino_BuiltIn", "SD", "Ethernet", "TFT", "Firmata"}
DEFS = ["-mmcu=atmega2560", "-DF_CPU=16000000L", "-DARDUINO=10607", "-DARDUINO_AVR_MEGA2560", "-DARDUINO_ARCH_AVR"]
FLAGS = ["-Os", "-w", "-ffunction-sections", "-fdata-sections"]


def run(cmd, **kw):
    subprocess.run(cmd, check=True, **kw)


def resolve(root, rel):
    """Follows symlinks inside the unpacked tree (they point at absolute Alpine paths)."""
    p = os.path.join(root, rel)
    for _ in range(8):
        if not os.path.islink(p):
            return p
        t = os.readlink(p)
        p = os.path.join(root, t.lstrip("/")) if t.startswith("/") else os.path.join(os.path.dirname(p), t)
    return p


def fetch_alpine():
    pkgs, tree = os.path.join(WORK, "pkgs"), os.path.join(WORK, "alpine")
    os.makedirs(pkgs, exist_ok=True)
    shutil.rmtree(tree, ignore_errors=True)
    os.makedirs(tree)
    for repo, name, ver in PACKAGES:
        f = os.path.join(pkgs, f"{name}-{ver}.apk")
        if not os.path.exists(f):
            run(["curl", "-sfL", "-o", f, f"{ALPINE}/{repo}/aarch64/{name}-{ver}.apk"])
        subprocess.run(["tar", "-xzf", f, "-C", tree], stderr=subprocess.DEVNULL)
    return tree


def library_dirs():
    """Library name → (root, source dir) for every library usable on the Mega."""
    libs = {}
    for base in LIB_DIRS:
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            root = os.path.join(base, name)
            if name in SKIP_LIBS or not os.path.isdir(root) or name in libs:
                continue
            src = os.path.join(root, "src") if os.path.isdir(os.path.join(root, "src")) else root
            # architecture-specific sources (for example src/avr) are used when present
            libs[name] = (root, src)
    return libs


def sources(src):
    out = []
    for d, dirs, files in os.walk(src):
        dirs[:] = [x for x in dirs if x not in ("examples", "extras", "test", "tests", "docs") and not (
            os.path.basename(d) == os.path.basename(src) and x in ("sam", "samd", "esp32", "esp8266", "megaavr", "mbed", "renesas", "stm32", "nrf52"))]
        out += [os.path.join(d, f) for f in files if f.endswith((".c", ".cpp", ".S"))]
    return out


def build_sysroot(tree):
    sys_ = os.path.join(WORK, "sysroot")
    shutil.rmtree(sys_, ignore_errors=True)
    g = os.path.join(tree, f"usr/lib/gcc/avr/{GCC_VER}")
    os.makedirs(os.path.join(sys_, "gcc"))
    shutil.copytree(os.path.join(g, "include"), os.path.join(sys_, "gcc/include"))
    shutil.copytree(os.path.join(g, "include-fixed"), os.path.join(sys_, "gcc/include-fixed"))
    shutil.copy(os.path.join(g, "avr6/libgcc.a"), os.path.join(sys_, "gcc/libgcc.a"))
    inc = os.path.join(sys_, "avr/include")
    shutil.copytree(os.path.join(tree, "usr/avr/include"), inc)
    for f in os.listdir(os.path.join(inc, "avr")):
        if f.startswith("io") and f not in ("io.h", "iom2560.h", "iomxx0_1.h"):
            os.remove(os.path.join(inc, "avr", f))
    os.makedirs(os.path.join(sys_, "avr/lib"))
    for f in ("crtatmega2560.o", "libatmega2560.a", "libc.a", "libm.a"):
        shutil.copy(os.path.join(tree, "usr/avr/lib/avr6", f), os.path.join(sys_, "avr/lib", f))
    os.makedirs(os.path.join(sys_, "ldscripts"))
    shutil.copy(os.path.join(tree, "usr/avr/lib/ldscripts/avr6.xn"), os.path.join(sys_, "ldscripts/avr6.xn"))

    # Arduino core
    core = os.path.join(sys_, "arduino/core")
    os.makedirs(core)
    for f in os.listdir(os.path.join(HW, "cores/arduino")):
        if f.endswith(".h"):
            shutil.copy(os.path.join(HW, "cores/arduino", f), core)
    os.makedirs(os.path.join(sys_, "arduino/variant"))
    shutil.copy(os.path.join(HW, "variants/mega/pins_arduino.h"), os.path.join(sys_, "arduino/variant"))
    base_inc = ["-I" + os.path.join(HW, "cores/arduino"), "-I" + os.path.join(HW, "variants/mega")]
    libs = library_dirs()
    lib_inc = [f"-I{src}" for _, src in libs.values()]
    archive(os.path.join(sys_, "arduino/core.a"), sources(os.path.join(HW, "cores/arduino")), base_inc)

    # libraries: headers keep their folder layout; sources become lib<name>.a
    index = []
    for name, (root, src) in libs.items():
        dst = os.path.join(sys_, "libraries", name)
        for d, _, files in os.walk(src):
            if any(x in d for x in ("/examples", "/extras", "/test", "/docs")):
                continue
            for f in files:
                if f.endswith((".h", ".hpp", ".tpp", ".inc")):
                    rel = os.path.relpath(os.path.join(d, f), src)
                    os.makedirs(os.path.dirname(os.path.join(dst, rel)), exist_ok=True)
                    shutil.copy(os.path.join(d, f), os.path.join(dst, rel))
        srcs = sources(src)
        a = None
        if srcs:
            a = f"libraries/{name}.a"
            if not archive(os.path.join(sys_, a), srcs, base_inc + lib_inc, quiet=True):
                shutil.rmtree(dst, ignore_errors=True)
                print(f"  skipped {name} (does not build for the Mega)")
                continue
        headers = sorted(f for f in os.listdir(dst)) if os.path.isdir(dst) else []
        index.append({"name": name, "dir": f"libraries/{name}", "archive": a, "headers": [h for h in headers if h.endswith((".h", ".hpp"))]})
    json.dump({"gcc": GCC_VER, "libraries": index}, open(os.path.join(sys_, "index.json"), "w"), indent=0)
    return sys_


def archive(out, srcs, inc, quiet=False):
    objs = os.path.join(WORK, "obj", os.path.basename(out))
    shutil.rmtree(objs, ignore_errors=True)
    os.makedirs(objs)
    for i, s in enumerate(srcs):
        o = os.path.join(objs, f"{i}-{os.path.basename(s)}.o")
        if s.endswith(".cpp"):
            cmd = [os.path.join(AVR_BIN, "avr-g++"), "-c", "-std=gnu++11", "-fpermissive", "-fno-exceptions", "-fno-threadsafe-statics"]
        elif s.endswith(".S"):
            cmd = [os.path.join(AVR_BIN, "avr-gcc"), "-c", "-x", "assembler-with-cpp"]
        else:
            cmd = [os.path.join(AVR_BIN, "avr-gcc"), "-c", "-std=gnu11"]
        p = subprocess.run(cmd + DEFS + FLAGS + inc + ["-I" + os.path.dirname(s), s, "-o", o], capture_output=True, text=True)
        if p.returncode != 0:
            if not quiet:
                print(p.stderr)
            return False
    run([os.path.join(AVR_BIN, "avr-ar"), "rcs", out] + [os.path.join(objs, f) for f in sorted(os.listdir(objs))])
    return True


def main():
    os.makedirs(WORK, exist_ok=True)
    tree = fetch_alpine()
    os.makedirs(JNI, exist_ok=True)
    links = {}
    for rel, name, soname in NATIVE:
        shutil.copy(resolve(tree, rel), os.path.join(JNI, name))
        if soname:
            links[soname] = name
    sys_ = build_sysroot(tree)
    os.makedirs(ASSETS, exist_ok=True)
    z = os.path.join(ASSETS, "sysroot.zip")
    with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as zf:
        for d, _, files in os.walk(sys_):
            for f in files:
                p = os.path.join(d, f)
                zf.write(p, os.path.relpath(p, sys_))
    json.dump({"links": links, "packages": [f"{n} {v}" for _, n, v in PACKAGES], "alpine": ALPINE}, open(os.path.join(ASSETS, "native.json"), "w"), indent=1)
    libs = json.load(open(os.path.join(sys_, "index.json")))["libraries"]
    print(f"native: {sum(os.path.getsize(os.path.join(JNI, n)) for _, n, _ in NATIVE) // 1024} KB, sysroot.zip: {os.path.getsize(z) // 1024} KB, libraries: {', '.join(l['name'] for l in libs)}")


if __name__ == "__main__":
    main()
