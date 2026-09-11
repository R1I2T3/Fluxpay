"""Shared, standard-library helpers for the explicit M5 solo workflow."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler
import xml.etree.ElementTree as ET

from platform_commands import PROJECT_ROOT, maven_command


def load_environment(path=None, inherited=None):
    """Parse literal dotenv values; the selected file overrides inherited values."""
    result = dict(os.environ if inherited is None else inherited)
    if path is None:
        return result
    for number, line in enumerate(Path(path).read_text(encoding="utf-8-sig").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError(f"Invalid env assignment on line {number}")
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"Unclosed env quote on line {number}")
            value = value[1:-1]
        result[key] = value
    return result


def validate_isolated_environment(env):
    required = ("ORACLE_JDBC_URL", "ORACLE_USERNAME", "ORACLE_PASSWORD", "JWT_SECRET", "M5_TEST_SCHEMA")
    if any(not env.get(key, "").strip() for key in required):
        raise ValueError("Explicit isolated Oracle credentials, JWT_SECRET and M5_TEST_SCHEMA are required")
    if env.get("M5_ALLOW_FIXTURE_SETUP") != "true":
        raise ValueError("M5_ALLOW_FIXTURE_SETUP must explicitly equal true")
    schema = env["M5_TEST_SCHEMA"]
    if not re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", schema):
        raise ValueError("M5_TEST_SCHEMA must be an explicit uppercase unquoted schema name")
    if schema != env["ORACLE_USERNAME"] or schema in {"FLUXPAY", "SYS", "SYSTEM", "ADMIN", "PUBLIC"}:
        raise ValueError("M5_TEST_SCHEMA must equal the dedicated username and cannot name a shared/system schema")
    if len(env["JWT_SECRET"].encode("utf-8")) < 32:
        raise ValueError("JWT_SECRET must contain at least 32 UTF-8 bytes")
    if not env["ORACLE_JDBC_URL"].startswith("jdbc:oracle:thin:"):
        raise ValueError("An explicit Oracle thin JDBC URL is required")


def private_token_path(path):
    candidate = Path(path).expanduser().absolute()
    if candidate.is_symlink():
        raise ValueError("The token file cannot be a symbolic link")
    resolved = candidate.resolve()
    if resolved == PROJECT_ROOT or PROJECT_ROOT in resolved.parents:
        raise ValueError("M5 tokens must be stored outside the repository")
    if any((parent / ".git").exists() for parent in resolved.parents):
        raise ValueError("M5 tokens must be stored outside version-controlled directories")
    return resolved


def read_tokens(path):
    value = json.loads(private_token_path(path).read_text(encoding="utf-8"))
    if value.get("format") != "m5-solo-v1":
        raise ValueError("Expected an M5 solo token file")
    for key in ("adminToken", "userToken", "foreignUserToken"):
        if not isinstance(value.get(key), str) or value[key].count(".") != 2:
            raise ValueError("The token file does not contain valid signed-token fields")
    return value


def m5_maven_command(*args):
    """Use the existing local distribution when present, then the project wrapper."""
    common = ["-o", "-B", "-ntp", f"-Duser.home={Path.home()}",
              f"-Dmaven.repo.local={PROJECT_ROOT / '.m2' / 'repository'}", "-f", str(PROJECT_ROOT / "backend" / "pom.xml")]
    executable = "mvn.cmd" if os.name == "nt" else "mvn"
    cached = sorted((Path.home() / ".m2" / "wrapper" / "dists").glob(f"apache-maven-*/**/bin/{executable}"))
    if cached:
        prefix = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c"] if os.name == "nt" else []
        return [*prefix, str(cached[-1]), *common, *map(str, args)]
    return maven_command(*common, *args)


def stop_process(child):
    if child.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       creationflags=subprocess.CREATE_NO_WINDOW, check=False)
    else:
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)


def run_owned_process(command, env, shutdown_file=None):
    kwargs = {"cwd": str(PROJECT_ROOT), "env": env}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    child = subprocess.Popen(command, **kwargs)
    try:
        return child.wait()
    except KeyboardInterrupt:
        if shutdown_file is not None:
            Path(shutdown_file).touch()
            try:
                child.wait(timeout=10)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                pass
        return 130
    finally:
        if child.poll() is None:
            stop_process(child)


def check_reports(directory, groups):
    counts = dict.fromkeys(groups, 0)
    for report in Path(directory).glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        name = suite.attrib.get("name", "").rsplit(".", 1)[-1]
        if not name.startswith("M5") or name.endswith("SoloLauncherTest"):
            continue
        for group in groups:
            if name.endswith(group):
                tests, skipped, failures, errors = (int(suite.attrib.get(key, 0)) for key in ("tests", "skipped", "failures", "errors"))
                if failures or errors or (group == "OracleTest" and skipped):
                    raise RuntimeError(f"{name}: failed, errored or skipped required Oracle tests")
                counts[group] += tests - skipped
    missing = [key for key, count in counts.items() if count <= 0]
    if missing:
        raise RuntimeError("No executed tests for required groups: " + ", ".join(missing))
    return counts


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class ApiClient:
    def __init__(self, base, tokens):
        parsed = urlsplit(base)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
            raise ValueError("--base must be an HTTP(S) origin without credentials or a path")
        if parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("Non-loopback API access requires HTTPS")
        self.base, self.tokens = base.rstrip("/"), tokens
        self.opener = build_opener(_NoRedirect())

    def call(self, method, path, body=None, role="admin", expected=(200,), retry_uncertain=False, timeout=35):
        if not path.startswith("/api/") or path.startswith("//"):
            raise ValueError("Only API paths are supported")
        headers = {"Accept": "application/json"}
        if role:
            headers["Authorization"] = "Bearer " + self.tokens[role + "Token"]
        payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        if payload is not None:
            headers["Content-Type"] = "application/json"
        # Serialize once so a lost response retries the identical assessment/decision identity.
        for attempt in range(2 if retry_uncertain else 1):
            request = Request(self.base + path, data=payload, headers=headers, method=method)
            try:
                response = self.opener.open(request, timeout=timeout)
                with response:
                    status, raw = response.status, response.read()
            except HTTPError as error:
                status, raw = error.code, error.read()
            except (URLError, TimeoutError, ConnectionError, OSError):
                if retry_uncertain and attempt == 0:
                    continue
                raise RuntimeError(f"{method} {path}: transport failed; preserve operation identity before retrying") from None
            try:
                data = json.loads(raw)
            except (ValueError, UnicodeError):
                raise RuntimeError(f"{method} {path}: response is not a JSON envelope") from None
            if status not in expected:
                code = data.get("code", "unexpected") if isinstance(data, dict) else "unexpected"
                raise RuntimeError(f"{method} {path}: HTTP {status}, code {code}")
            if not isinstance(data, dict) or not data.get("correlationId"):
                raise RuntimeError(f"{method} {path}: missing correlation envelope")
            if status < 400 and "data" not in data:
                raise RuntimeError(f"{method} {path}: missing success data")
            if status >= 400 and not all(key in data for key in ("code", "message", "fieldErrors", "ts")):
                raise RuntimeError(f"{method} {path}: missing error envelope fields")
            return status, data["data"] if status < 400 else data


def fail(message):
    print(f"M5: {message}", file=sys.stderr)
    return 2
