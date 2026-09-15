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


if __name__ == "__main__":
    unittest.main()
