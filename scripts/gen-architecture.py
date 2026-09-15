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
    for n in nodes:
        if n["id"] in ("l3-messaging-outboxrelay", "l3-messaging-paymenteventconsumer", "l3-messaging-outboxservice"):
            n["detail"]["topics"] = ["payment.initiated", "payout.submitted", "payout.completed"]
    return nodes


HTML_TEMPLATE = """<!doctype html><html><head><meta charset="utf-8"><title>FluxPay Architecture</title>
<style>body{font-family:system-ui,sans-serif;display:flex;margin:0}#left{width:42%;border-right:1px solid #ddd;padding:12px;overflow:auto;height:100vh}#right{width:58%;padding:12px;overflow:auto;height:100vh}button.crumb{margin-right:6px}ul{list-style:none;padding-left:16px}li{margin:4px 0}.node{cursor:pointer;color:#0b5fff;text-decoration:underline;background:none;border:none;padding:0;font-size:14px}pre{background:#f6f6f6;padding:8px;overflow:auto}</style>
</head><body><div id="left"><div id="crumbs" aria-label="breadcrumb"></div><h2 id="title"></h2><ul id="tree"></ul></div>
<div id="right"><h2>Detail</h2><div id="detail">Click a class.</div><footer id="foot"></footer></div>
<script>const MODEL=/*__MODEL__*/[];</script>
<script src="__INLINE__"></script></body></html>"""


VIEWER_JS = """
let cur='l0-system';
function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function byId(id){return MODEL.find(n=>n.id===id);}
function render(id){
 cur=id; const node=byId(id);
 const crumbs=[]; let c=node; while(c){crumbs.unshift(c); c=c.parent?byId(c.parent):null;}
 document.getElementById('crumbs').innerHTML=crumbs.map(n=>`<button class=crumb data-id="${esc(n.id)}">${esc(n.label)}</button>`).join(' / ');
 document.getElementById('title').textContent=node.label+' (L'+node.level+')';
 const list=(node.children||[]).map(cid=>byId(cid)).filter(Boolean);
 document.getElementById('tree').innerHTML=list.map(n=>`<li><button class=node data-id="${esc(n.id)}">${esc(n.label)} ${n.kind==='class'?'[class]':''}</button></li>`).join('')||'<li><i>leaf — see detail</i></li>';
 showDetail(node);
 document.querySelectorAll('button[data-id]').forEach(b=>b.onclick=()=>{const t=byId(b.dataset.id); if(t.children&&t.children.length){render(t.id);} else {showDetail(t);}});
}
function showDetail(n){
 const d=n.detail||{}; let h=`<h3>${esc(n.label)}</h3><p>kind=${n.kind} level=${n.level}</p>`;
 if(n.kind==='class'){h+=`<pre>file: ${esc(d.file||'')}\\nstereotype: ${esc(d.stereotype||'')}\\nmethods: ${esc((d.methods||[]).join(', ')||'no public methods parsed')}\\nendpoints: ${esc((d.endpoints||[]).join('; ')||'-')}</pre>`;}
 else {h+=`<p>${esc(d.summary||'')} — ${(n.children||[]).length} children. Click child to drill down.</p>`;}
 document.getElementById('detail').innerHTML=h;
}
document.addEventListener('DOMContentLoaded',()=>render('l0-system'));
"""


def render_html(nodes: list[dict]) -> str:
    import datetime

    payload = json.dumps(nodes)
    try:
        sha = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=PROJECT_ROOT, text=True).strip()
    except Exception:
        sha = "nogit"
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    html = HTML_TEMPLATE.replace("/*__MODEL__*/[]", payload)
    html = html.replace('<script src="__INLINE__"></script>', "").replace("__INLINE__", "")
    html = html.replace(
        "</body>",
        f"<script>{VIEWER_JS}</script><script>document.getElementById('foot').textContent='generatedAt {stamp} sha {sha}';</script></body>",
    )
    # fix double script tag from template: ensure single injection
    return html


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--write", action="store_true")
    p.add_argument("--check", action="store_true")
    p.add_argument("--out", default="docs/architecture.html")
    p.add_argument("--root", default=str(PROJECT_ROOT))
    a = p.parse_args(argv)
    root = Path(a.root)
    nodes = build_model(root)
    if a.check:
        return 0
    if a.write:
        out = root / a.out if not Path(a.out).is_absolute() else Path(a.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render_html(nodes), encoding="utf-8")
        print(f"wrote {out} ({len(nodes)} nodes)")
        return 0
    p.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
