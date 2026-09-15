import importlib.util
import sys
import unittest
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))


def load_gen():
    path = SCRIPTS_DIR / "gen-architecture.py"
    spec = importlib.util.spec_from_file_location("gen_architecture", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class GenModelTests(unittest.TestCase):
    def test_build_model_contains_known_classes(self):
        gen = load_gen()
        nodes = gen.build_model(PROJECT_ROOT)
        by_id = {n["id"]: n for n in nodes}
        self.assertIn("l0-system", by_id)
        self.assertIn("l1-backend", by_id)
        self.assertIn("l2-service", by_id)
        self.assertIn("l3-service-paymentservice", by_id)
        svc = by_id["l3-service-paymentservice"]
        self.assertEqual(svc["detail"]["file"], "backend/src/main/java/com/fluxpay/service/PaymentService.java")
        self.assertIn("PaymentService", svc["label"])

    def test_parent_links_valid_and_no_dup_ids(self):
        gen = load_gen()
        nodes = gen.build_model(PROJECT_ROOT)
        ids = [n["id"] for n in nodes]
        self.assertEqual(len(ids), len(set(ids)))
        by_id = {n["id"]: n for n in nodes}
        for n in nodes:
            if n["parent"] is not None:
                self.assertIn(n["parent"], by_id)

    def test_write_and_check_roundtrip(self):
        import tempfile

        gen = load_gen()
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "arch.html"
            rc = gen.main(["--write", "--out", str(out), "--root", str(PROJECT_ROOT)])
            self.assertEqual(rc, 0)
            html = out.read_text(encoding="utf-8")
            self.assertIn("l3-service-paymentservice", html)
            self.assertIn("breadcrumb", html)
            self.assertNotIn("/*__MODEL__*/", html)
            rc2 = gen.main(["--check", "--root", str(PROJECT_ROOT)])
            self.assertIn(rc2, (0, 1))

    def test_html_has_drill_and_panel(self):
        gen = load_gen()
        nodes = gen.build_model(PROJECT_ROOT)
        html = gen.render_html(nodes)
        self.assertIn('id="tree"', html)
        self.assertIn('id="detail"', html)
        self.assertIn('id="crumbs"', html)
        self.assertIn("function render", html)
        self.assertIn("PaymentService", html)

    def test_enrichment_has_topics_and_frontend(self):
        gen = load_gen()
        nodes = gen.build_model(PROJECT_ROOT)
        by_id = {n["id"]: n for n in nodes}
        self.assertIn(
            "payment.initiated",
            (PROJECT_ROOT / "backend/src/main/java/com/fluxpay/messaging/EventTopics.java").read_text(
                encoding="utf-8", errors="ignore"
            ),
        )
        self.assertTrue(any(n["id"].startswith("l1-") for n in nodes))
        html_size = len(gen.render_html(nodes).encode("utf-8"))
        self.assertLess(html_size, 500 * 1024)
        for mid in (
            "l3-messaging-outboxrelay",
            "l3-messaging-paymenteventconsumer",
            "l3-messaging-outboxservice",
        ):
            self.assertIn(mid, by_id)
            self.assertIn("payment.initiated", by_id[mid]["detail"]["topics"])

    def test_event_topics_eight_parsed(self):
        gen = load_gen()
        topics = gen.load_event_topics(PROJECT_ROOT)
        self.assertEqual(len(topics), 8)
        for t in (
            "payment.initiated",
            "payment.route.selected",
            "payment.screening.completed",
            "payout.submitted",
            "payout.failed",
            "payout.completed",
            "payment.refunded",
            "payment.review.requested",
        ):
            self.assertIn(t, topics)
        nodes = gen.build_model(PROJECT_ROOT)
        by_id = {n["id"]: n for n in nodes}
        for mid in (
            "l3-messaging-outboxrelay",
            "l3-messaging-paymenteventconsumer",
            "l3-messaging-outboxservice",
        ):
            self.assertEqual(by_id[mid]["detail"]["topics"], topics)
        html = gen.render_html(nodes)
        self.assertIn("payment.route.selected", html)
        self.assertIn("payment.review.requested", html)

    def test_viewer_esc_and_topics_line(self):
        gen = load_gen()
        nodes = gen.build_model(PROJECT_ROOT)
        html = gen.render_html(nodes)
        self.assertIn("function esc", html)
        # esc escapes <: JS mapping must contain &lt; for '<'
        self.assertIn("&lt;", gen.VIEWER_JS)
        self.assertIn("function esc", gen.VIEWER_JS)
        # showDetail renders topics with esc join (C1)
        self.assertIn("topics:", html)
        self.assertIn("(d.topics||[]).join", html)
        self.assertIn("esc((d.topics", html)

    def test_methods_endpoints_truncation_metadata(self):
        import tempfile

        gen = load_gen()
        nodes = gen.build_model(PROJECT_ROOT)
        by_id = {n["id"]: n for n in nodes}
        svc = by_id["l3-service-paymentservice"]
        self.assertIn("methodsTotal", svc["detail"])
        self.assertIn("methodsTruncated", svc["detail"])
        self.assertIn("endpointsTotal", svc["detail"])
        self.assertIn("endpointsTruncated", svc["detail"])
        self.assertIn("methodsTruncated", gen.VIEWER_JS)
        self.assertIn("methodsTotal", gen.VIEWER_JS)
        self.assertIn("more)", gen.VIEWER_JS)
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            p = root / "Big.java"
            body = "\n".join(f"public void m{i}() {{}}" for i in range(35))
            p.write_text(f"public class Big {{\n{body}\n}}", encoding="utf-8")
            info = gen.parse_java_file(p, root)
            self.assertEqual(len(info["methods"]), 30)
            self.assertEqual(info["methodsTotal"], 35)
            self.assertTrue(info["methodsTruncated"])

    def test_check_is_dry_run(self):
        gen = load_gen()
        rc = gen.main(["--check", "--root", str(PROJECT_ROOT)])
        self.assertEqual(rc, 0)
        nodes = gen.build_model(PROJECT_ROOT)
        self.assertGreater(len(nodes), 0)

    def test_parse_and_render_use_passed_root(self):
        gen = load_gen()
        nodes = gen.build_model(PROJECT_ROOT)
        html1 = gen.render_html(nodes, PROJECT_ROOT)
        html2 = gen.render_html(nodes)
        self.assertIn("function esc", html1)
        self.assertIn("function esc", html2)
        p = PROJECT_ROOT / "backend/src/main/java/com/fluxpay/service/PaymentService.java"
        info = gen.parse_java_file(p, PROJECT_ROOT)
        self.assertEqual(info["file"], "backend/src/main/java/com/fluxpay/service/PaymentService.java")


if __name__ == "__main__":
    unittest.main()
