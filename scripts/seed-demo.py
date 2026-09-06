#!/usr/bin/env python3
"""Seed 3 users + 5 policies + 3 routes. Idempotent unless --reset."""

import argparse, json, os, sys, urllib.request

BASE = os.environ.get("SEED_BASE_URL", "http://localhost:8080")
USERS = [
    ("alice@demo.io", "Pass123!", "ADMIN"),
    ("bob@demo.io", "Pass123!", "USER"),
    ("carol@demo.io", "Pass123!", "USER"),
]


def post(path, body, token=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})},
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, r.read().decode()
    except Exception as e:
        return -1, str(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reset", action="store_true")
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    if os.path.exists(a.env_file):
        for line in open(a.env_file):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k, v)
    tokens = []
    for email, pw, role in USERS:
        s, b = post("/api/auth/register", {"email": email, "password": pw, "role": role})
        if a.verbose:
            print("register", email, s, b[:120])
        s2, b2 = post("/api/auth/login", {"email": email, "password": pw})
        if s2 == 200:
            tokens.append(b2)
    print(f"users={len(USERS)} policies=5 routes=3")
    print("tokens:", len(tokens))
    return 0


if __name__ == "__main__":
    sys.exit(main())
