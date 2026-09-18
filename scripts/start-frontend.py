#!/usr/bin/env python3
"""Serve JET UI; fail fast if node_modules missing."""

import argparse, os, subprocess, sys

from platform_commands import FRONTEND_DIR, load_env, ojet_command


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    load_env(a.env_file)
    if not os.path.isdir(FRONTEND_DIR / "node_modules"):
        print("node_modules missing; run: npm install --prefix frontend/fluxpay-ui")
        return 2
    env = dict(os.environ)
    env.setdefault("API_PROXY", "http://127.0.0.1:" + env.get("SERVER_PORT", "8080"))
    print(f"FluxPay UI: http://localhost:{a.port}/ — API proxy: {env['API_PROXY']}", flush=True)
    cmd = ojet_command("serve", "--server-port", a.port)
    if a.verbose:
        print("+", " ".join(cmd))
    return subprocess.run(cmd, cwd=FRONTEND_DIR, env=env).returncode


if __name__ == "__main__":
    sys.exit(main())
