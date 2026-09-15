import importlib.util
import json
import sys
import unittest
import uuid
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))


def load_script():
    path = SCRIPTS_DIR / "smoke-local.py"
    spec = importlib.util.spec_from_file_location("smoke_local", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SmokeLocalTests(unittest.TestCase):
    def test_envelope_is_canonical_and_tied_to_the_created_payment(self):
        script = load_script()
        payment_id = "11111111-1111-1111-1111-111111111111"
        envelope = script.canonical_envelope(payment_id)

        uuid.UUID(envelope["eventId"])
        uuid.UUID(envelope["correlationId"])
        self.assertEqual(envelope["eventType"], "payment.initiated")
        self.assertEqual(envelope["paymentId"], payment_id)
        self.assertEqual(envelope["payload"]["schemaVersion"], 1)
        self.assertEqual(envelope["payload"]["aggregateSequence"], 1)

    def test_timeline_match_requires_the_exact_event_id_and_canonical_topic(self):
        script = load_script()
        wanted = str(uuid.uuid4())
        timeline = {
            "data": [
                {"eventId": str(uuid.uuid4()), "eventType": "payment.initiated"},
                {"eventId": wanted, "eventType": "payment.initiated"},
            ]
        }
        self.assertTrue(script.timeline_contains(timeline, wanted))
        self.assertFalse(script.timeline_contains(timeline, str(uuid.uuid4())))

    def test_compose_producer_uses_container_tool_and_exact_json_line(self):
        script = load_script()
        envelope = script.canonical_envelope("11111111-1111-1111-1111-111111111111")
        command, payload = script.producer_invocation("compose", "localhost:9092", envelope)

        self.assertEqual(command[:5], ["docker", "compose", "exec", "-T", "kafka"])
        self.assertIn("payment.initiated", command)
        self.assertEqual(json.loads(payload.decode("utf-8")), envelope)


if __name__ == "__main__":
    unittest.main()
