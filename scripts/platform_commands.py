"""Cross-platform commands and project paths for Fluxpay scripts."""

import os
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
FRONTEND_DIR = PROJECT_ROOT / "frontend" / "fluxpay-ui"


def project_path(path):
    """Resolve relative paths from the project root, not the caller's cwd."""
    path = Path(path)
    return path if path.is_absolute() else PROJECT_ROOT / path


def _local_command(path, *args):
    """Run a local executable, selecting its Windows batch shim when needed."""
    path = Path(path)
    if sys.platform == "win32":
        batch_path = Path(f"{path}.cmd")
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(batch_path), *map(str, args)]
    return [str(path), *map(str, args)]


def maven_command(*args):
    if sys.platform == "win32":
        # Maven commands always run with PROJECT_ROOT as cwd.  A bare batch
        # filename avoids cmd.exe splitting PROJECT_ROOT when it contains spaces.
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", "mvnw.cmd", *map(str, args)]
    return _local_command(PROJECT_ROOT / "mvnw", *args)


def ojet_command(*args):
    if sys.platform == "win32":
        # OJET commands run with FRONTEND_DIR as cwd; avoid cmd.exe splitting
        # the absolute project path when it contains spaces.
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", r"node_modules\.bin\ojet.cmd", *map(str, args)]
    return _local_command(FRONTEND_DIR / "node_modules" / ".bin" / "ojet", *args)


def python_command(script, *args):
    return [sys.executable, str(project_path(script)), *map(str, args)]
