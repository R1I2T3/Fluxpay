#!/usr/bin/env python3
"""Serve JET UI; fail fast if node_modules missing."""

import argparse, os, subprocess, sys

from platform_commands import FRONTEND_DIR, ojet_command


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    if not os.path.isdir(FRONTEND_DIR / "node_modules"):
        print("node_modules missing; run: npm install --prefix frontend/fluxpay-ui")
        return 2
    env = dict(os.environ)
    env["API_PROXY"] = "http://localhost:8080"
    cmd = ojet_command("serve", "--server-port", a.port)
    if a.verbose:
        print("+", " ".join(cmd))
    return subprocess.run(cmd, cwd=FRONTEND_DIR, env=env).returncode


if __name__ == "__main__":
    sys.exit(main())
