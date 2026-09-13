"""Cross-platform commands and project paths for FluxPay scripts."""

import os
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
FRONTEND_DIR = PROJECT_ROOT / "frontend" / "fluxpay-ui"


def project_path(path):
    """Resolve relative paths from the project root, not the caller's cwd."""
    path = Path(path)
    return path if path.is_absolute() else PROJECT_ROOT / path


def load_env(path):
    """Load unset environment variables from a project-relative env file."""
    env_path = project_path(path)
    if not env_path.is_file():
        return
    for line in env_path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key, value)


def configure_windows_maven_home():
    """Keep the Maven wrapper in the current Windows user's home directory."""
    if sys.platform != "win32":
        return
    userprofile = os.environ.get("USERPROFILE")
    if not userprofile:
        return
    os.environ.setdefault("MAVEN_USER_HOME", os.path.join(userprofile, ".m2"))
    if "-Duser.home=" not in os.environ.get("MAVEN_OPTS", ""):
        user_home_option = f'-Duser.home="{userprofile}"'
        os.environ["MAVEN_OPTS"] = (
            f"{os.environ.get('MAVEN_OPTS', '')} {user_home_option}".strip()
        )


def _windows_batch_command(path, *args):
    return [
        os.environ.get("COMSPEC", "cmd.exe"),
        "/d",
        "/c",
        str(path),
        *map(str, args),
    ]


def maven_command(*args):
    wrapper = PROJECT_ROOT / ("mvnw.cmd" if sys.platform == "win32" else "mvnw")
    if sys.platform == "win32":
        return _windows_batch_command(wrapper, *args)
    return [str(wrapper), *map(str, args)]


def ojet_command(*args):
    executable = FRONTEND_DIR / "node_modules" / ".bin" / (
        "ojet.cmd" if sys.platform == "win32" else "ojet"
    )
    if sys.platform == "win32":
        return _windows_batch_command(executable, *args)
    return [str(executable), *map(str, args)]


def python_command(path, *args):
    return [sys.executable, str(project_path(path)), *map(str, args)]
