#!/usr/bin/env python3
"""Stop infra. Default: docker compose stop (data kept)."""

import argparse, subprocess, sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--down", action="store_true")
    ap.add_argument("--volumes", action="store_true")
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    if a.volumes:
        cmd = ["docker", "compose", "down", "--volumes"]
    elif a.down:
        cmd = ["docker", "compose", "down"]
    else:
        cmd = ["docker", "compose", "stop"]
    if a.verbose:
        print("+", " ".join(cmd))
    return subprocess.run(cmd).returncode


if __name__ == "__main__":
    sys.exit(main())
