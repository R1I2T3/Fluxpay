"""Run M2 Oracle tests in a child process using explicitly isolated schemas.

The env file supplies ORACLE_JDBC_URL and the test schemas' ORACLE_PASSWORD.
The username is fixed to FLUXPAY_M2_TEST; the legacy tests use FLUXPAY_M2_LEGACY_TEST.
This script never creates/resets a schema or changes the parent terminal environment.
"""

import argparse
import os
from pathlib import Path
import subprocess
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))
from platform_commands import maven_command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=PROJECT_ROOT / ".env")
    args = parser.parse_args()
    settings = {}
    try:
        for line in args.env_file.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                settings[key] = value
    except OSError:
        parser.error("Cannot read the env file; supply --env-file with test connection settings")
    for key in ("ORACLE_JDBC_URL", "ORACLE_PASSWORD"):
        if not settings.get(key):
            parser.error(f"The env file must supply {key}")
    env = dict(os.environ)
    env.update(
        ORACLE_JDBC_URL=settings["ORACLE_JDBC_URL"],
        ORACLE_USERNAME="FLUXPAY_M2_TEST",
        ORACLE_PASSWORD=settings["ORACLE_PASSWORD"],
        M2_ORACLE_TESTS="true",
    )
    print("Running against FLUXPAY_M2_TEST and FLUXPAY_M2_LEGACY_TEST only.", flush=True)
    return subprocess.call(maven_command("-f", "backend/pom.xml", "test"), cwd=PROJECT_ROOT, env=env)


if __name__ == "__main__":
    sys.exit(main())
