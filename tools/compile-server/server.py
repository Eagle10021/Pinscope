#!/usr/bin/env python3
"""Pinscope compile server: turns Arduino sketch source into a .hex file for the Mega 2560.

Run it on any machine with arduino-cli and the arduino:avr core (the Arduino IDE 2 includes both):

    python3 server.py --port 8099

The app sends POST /compile with JSON {"source": "..."} or {"url": "https://.../sketch.ino"}
and gets back {"ok": true, "hex": "...", "size": n} or {"ok": false, "error": "..."}.
GitHub page links (github.com/.../blob/...) are turned into raw links. Only http(s) URLs are fetched.
Meant for a private network or Tailscale; there is no authentication beyond an optional shared token.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import tempfile
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FQBN = "arduino:avr:mega:cpu=atmega2560"
MAX_SOURCE = 256 * 1024
CLI_DEFAULTS = ["arduino-cli", "/opt/arduino-ide/resources/app/lib/backend/resources/arduino-cli"]


def find_cli(explicit):
    for c in ([explicit] if explicit else []) + CLI_DEFAULTS:
        path = shutil.which(c) or (c if os.path.isfile(c) else None)
        if path:
            return path
    raise SystemExit("arduino-cli not found; pass --cli")


def raw_url(url):
    m = re.match(r"https://github\.com/([^/]+)/([^/]+)/blob/(.+)", url)
    return f"https://raw.githubusercontent.com/{m[1]}/{m[2]}/{m[3]}" if m else url


def fetch(url):
    if not url.startswith(("http://", "https://")):
        raise ValueError("Only http and https links are supported")
    req = urllib.request.Request(raw_url(url), headers={"User-Agent": "pinscope-compile-server"})
    with urllib.request.urlopen(req, timeout=20) as r:
        data = r.read(MAX_SOURCE + 1)
    if len(data) > MAX_SOURCE:
        raise ValueError("The sketch is too large")
    return data.decode("utf-8", "replace")


def compile_source(cli, libs, source):
    with tempfile.TemporaryDirectory(prefix="pinscope-") as tmp:
        sketch = os.path.join(tmp, "sketch")
        os.makedirs(sketch)
        with open(os.path.join(sketch, "sketch.ino"), "w") as f:
            f.write(source)
        out = os.path.join(tmp, "out")
        cmd = [cli, "compile", "--fqbn", FQBN, "--output-dir", out, sketch]
        if libs:
            cmd[2:2] = ["--libraries", libs]
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
        if p.returncode != 0:
            # keep the useful part: compiler errors without the temp paths
            msg = (p.stderr or p.stdout).replace(sketch + "/", "")
            return {"ok": False, "error": msg[-4000:]}
        with open(os.path.join(out, "sketch.ino.hex")) as f:
            hexdata = f.read()
        size = re.search(r"Sketch uses (\d+) bytes", p.stdout)
        return {"ok": True, "hex": hexdata, "size": int(size[1]) if size else None}


def make_handler(cli, libs, token):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, code, obj):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/health":
                self._send(200, {"ok": True, "board": FQBN})
            else:
                self._send(404, {"ok": False, "error": "Not found"})

        def do_POST(self):
            if self.path != "/compile":
                return self._send(404, {"ok": False, "error": "Not found"})
            if token and self.headers.get("Authorization") != f"Bearer {token}":
                return self._send(401, {"ok": False, "error": "Wrong or missing token"})
            try:
                n = int(self.headers.get("Content-Length", "0"))
                if n > MAX_SOURCE * 2:
                    return self._send(413, {"ok": False, "error": "Request too large"})
                req = json.loads(self.rfile.read(n) or b"{}")
                source = req.get("source") or (fetch(req["url"]) if req.get("url") else None)
                if not source:
                    return self._send(400, {"ok": False, "error": "Send source or url"})
                self._send(200, compile_source(cli, libs, source))
            except Exception as e:  # report any failure to the app instead of dropping the request
                self._send(200, {"ok": False, "error": str(e)})

        def log_message(self, fmt, *args):
            print("%s %s" % (self.address_string(), fmt % args))

    return Handler


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--cli", help="path to arduino-cli")
    ap.add_argument("--libraries", default=os.path.expanduser("~/.arduino15/libraries"), help="extra library folder")
    ap.add_argument("--token", default=os.environ.get("PINSCOPE_TOKEN"), help="optional shared token")
    a = ap.parse_args()
    cli = find_cli(a.cli)
    libs = a.libraries if os.path.isdir(a.libraries) else None
    print(f"Pinscope compile server on {a.host}:{a.port} using {cli}")
    ThreadingHTTPServer((a.host, a.port), make_handler(cli, libs, a.token)).serve_forever()


if __name__ == "__main__":
    main()
