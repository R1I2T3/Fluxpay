#!/usr/bin/env python3
"""Generate self-contained interactive architecture viewer."""

import argparse
import datetime
import json
import re
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
BACKEND_ROOT = PROJECT_ROOT / "backend/src/main/java/com/fluxpay"

STEREOTYPES = {
    "@RestController": "RestController",
    "@Service": "Service",
    "@Repository": "Repository",
    "@Component": "Component",
    "@Configuration": "Configuration",
}

CLASS_RE = re.compile(r"(?:public\s+)?(?:final\s+)?(?:class|record|interface|enum)\s+(\w+)")
METHOD_RE = re.compile(r"public\s+(?:[\w<>\[\],\s]+\s+)?(\w+)\s*\(")
MAPPING_RE = re.compile(r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)(?:\("([^"]*)"\))?')
REQUEST_MAPPING_RE = re.compile(r'@RequestMapping\("([^"]*)"\)')


def parse_java_file(path: Path) -> dict:
    text = path.read_text(encoding="utf-8", errors="ignore")
    m = CLASS_RE.search(text)
    class_name = m.group(1) if m else path.stem
    stereotype = "Class"
    for marker, label in STEREOTYPES.items():
        if marker in text:
            stereotype = label
            break
    methods = METHOD_RE.findall(text)[:30]
    endpoints: list[str] = []
    base = ""
    rm = REQUEST_MAPPING_RE.search(text)
    if rm:
        base = rm.group(1)
    for kind, sub in MAPPING_RE.findall(text):
        http = kind.replace("Mapping", "").upper()
        endpoints.append(f"{http} {base}{sub}")
    rel = path.relative_to(PROJECT_ROOT).as_posix()
    pkg = path.parent.relative_to(BACKEND_ROOT).as_posix()
    return {
        "file": rel,
        "class_name": class_name,
        "package": pkg,
        "stereotype": stereotype,
        "methods": methods,
        "endpoints": endpoints[:10],
    }


def build_model(project_root: Path) -> list[dict]:
    backend_root = project_root / "backend/src/main/java/com/fluxpay"
    if not backend_root.is_dir():
        raise FileNotFoundError(f"backend root missing: {backend_root}")
    nodes: list[dict] = []
    nodes.append(
        {
            "id": "l0-system",
            "label": "FluxPay System",
            "level": 0,
            "parent": None,
            "kind": "system",
            "children": ["l1-ui", "l1-backend", "l1-oracle", "l1-kafka", "l1-fx"],
            "detail": {"summary": "Payment backend + OJET UI + Oracle/Kafka"},
        }
    )
    for cid, label in [
        ("l1-ui", "fluxpay-ui OJET"),
        ("l1-backend", "Spring Boot :8080"),
        ("l1-oracle", "Oracle FREE"),
        ("l1-kafka", "Kafka 3.7.0"),
        ("l1-fx", "Frankfurter FX"),
    ]:
        parent = "l0-system"
        kids: list[str] = []
        nodes.append(
            {
                "id": cid,
                "label": label,
                "level": 1,
                "parent": parent,
                "kind": "container",
                "children": kids,
                "detail": {"summary": label},
            }
        )
    pkg_nodes: dict[str, str] = {}
    java_files = sorted(backend_root.rglob("*.java"))
    if not java_files:
        raise RuntimeError(f"no java files under {backend_root}")
    for jf in java_files:
        info = parse_java_file(jf)
        pkg = info["package"]
        pkg_id = "l2-" + pkg.replace("/", "-")
        if pkg_id not in pkg_nodes:
            pkg_nodes[pkg_id] = pkg
            nodes.append(
                {
                    "id": pkg_id,
                    "label": pkg,
                    "level": 2,
                    "parent": "l1-backend",
                    "kind": "package",
                    "children": [],
                    "detail": {"summary": pkg},
                }
            )
            by = next(n for n in nodes if n["id"] == "l1-backend")
            by["children"].append(pkg_id)
        class_id = "l3-" + pkg.replace("/", "-") + "-" + info["class_name"].lower()
        nodes.append(
            {
                "id": class_id,
                "label": info["class_name"],
                "level": 3,
                "parent": pkg_id,
                "kind": "class",
                "children": [],
                "detail": {
                    "file": info["file"],
                    "stereotype": info["stereotype"],
                    "methods": info["methods"],
                    "endpoints": info["endpoints"],
                    "topics": [],
                },
            }
        )
        pkg_node = next(n for n in nodes if n["id"] == pkg_id)
        pkg_node["children"].append(class_id)
    return nodes
