#!/usr/bin/env python3
"""Stop Compose-managed FluxPay services; external services are never stopped."""

import argparse, os, subprocess, sys

from platform_commands import PROJECT_ROOT, load_env


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["compose", "external"])
    ap.add_argument("--down", action="store_true")
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    load_env(a.env_file)
    mode = a.mode or os.environ.get("FLUXPAY_INFRA_MODE", "compose").lower()
    if mode not in ("compose", "external"):
        print("invalid FLUXPAY_INFRA_MODE; expected compose or external")
        return 2
    if mode == "external":
        print("external infrastructure is managed outside FluxPay; nothing stopped")
        return 0
    if a.down:
        cmd = ["docker", "compose", "down"]
    else:
        cmd = ["docker", "compose", "stop", "oracle", "kafka"]
    if a.verbose:
        print("+", " ".join(cmd))
    return subprocess.run(cmd, cwd=PROJECT_ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
