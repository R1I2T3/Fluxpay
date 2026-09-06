#!/usr/bin/env python3
"""Serve JET UI; fail fast if node_modules missing."""

import argparse, os, subprocess, sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    if not os.path.isdir("frontend/fluxpay-ui/node_modules"):
        print("node_modules missing; run: npm install --prefix frontend/fluxpay-ui")
        return 2
    env = dict(os.environ)
    env["API_PROXY"] = "http://localhost:8080"
    cmd = ["ojet", "serve", "--server-port", str(a.port)]
    if a.verbose:
        print("+", " ".join(cmd))
    return subprocess.run(cmd, cwd="frontend/fluxpay-ui", env=env).returncode


if __name__ == "__main__":
    sys.exit(main())
