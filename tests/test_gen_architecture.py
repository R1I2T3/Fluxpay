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


if __name__ == "__main__":
    unittest.main()
