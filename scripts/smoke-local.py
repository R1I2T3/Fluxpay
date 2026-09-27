#!/usr/bin/env python3
"""Create a real local test payment, publish one canonical event, and verify its timeline."""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

from platform_commands import PROJECT_ROOT, load_env


TOPIC = "payment.initiated"


def api_request(base_url, path, method="GET", token=None, body=None, key=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exception:
        raise RuntimeError(f"backend returned HTTP {exception.code} for {path}") from exception
    if not isinstance(payload, dict) or "data" not in payload:
        raise RuntimeError(f"backend returned an invalid API envelope for {path}")
    return payload


def canonical_envelope(payment_id):
    event_id = str(uuid.uuid4())
    return {
        "eventType": TOPIC,
        "eventId": event_id,
        "paymentId": payment_id,
        "correlationId": str(uuid.uuid4()),
        "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "payload": {
            "schemaVersion": 1,
            "aggregateSequence": 1,
            "summary": "Local canonical-envelope smoke check",
        },
    }


def _external_kafka_script():
    suffix = ".bat" if sys.platform == "win32" else ".sh"
    kafka_home = os.environ.get("KAFKA_HOME", "")
    candidates = []
    if kafka_home:
        if sys.platform == "win32":
            candidates.append(os.path.join(kafka_home, "bin", "windows", "kafka-console-producer.bat"))
        candidates.append(os.path.join(kafka_home, "bin", "kafka-console-producer" + suffix))
    found = shutil.which("kafka-console-producer" + suffix) or shutil.which("kafka-console-producer")
    if found:
        candidates.append(found)
    return next((candidate for candidate in candidates if os.path.exists(candidate)), None)


def producer_invocation(mode, bootstrap, envelope):
    if mode == "compose":
        command = [
            "docker",
            "compose",
            "exec",
            "-T",
            "kafka",
            "/opt/kafka/bin/kafka-console-producer.sh",
            "--bootstrap-server",
            os.environ.get("KAFKA_CONTAINER_BOOTSTRAP_SERVERS", "kafka:9092"),
            "--topic",
            TOPIC,
        ]
    elif mode == "external":
        producer = _external_kafka_script()
        if producer is None:
            raise RuntimeError("external Kafka smoke requires kafka-console-producer; set KAFKA_HOME")
        command = [producer, "--bootstrap-server", bootstrap, "--topic", TOPIC]
    else:
        raise ValueError("infrastructure mode must be compose or external")
    payload = (json.dumps(envelope, separators=(",", ":")) + "\n").encode("utf-8")
    return command, payload


def timeline_contains(timeline, event_id):
    events = timeline.get("data", []) if isinstance(timeline, dict) else []
    return any(
        event.get("eventId") == event_id and event.get("eventType") == TOPIC
        for event in events
        if isinstance(event, dict)
    )


def _seed_payment(base_url, token):
    wallets = api_request(base_url, "/api/wallets", token=token)["data"]
    usd = next((wallet for wallet in wallets if wallet.get("currency") == "USD"), None)
    if usd is None:
        raise RuntimeError("seeded customer has no USD wallet")
    recipients = api_request(base_url, "/api/recipients", token=token)["data"]
    if recipients:
        recipient = recipients[0]
    else:
        recipient = api_request(
            base_url,
            "/api/recipients",
            method="POST",
            token=token,
            body={
                "name": "Smoke Recipient",
                "account": "SMOKE-" + str(uuid.uuid4()),
                "bankName": "Smoke Bank",
                "country": "IN",
                "currency": "INR",
                "status": "ACTIVE",
            },
        )["data"]
    draft = api_request(
        base_url,
        "/api/payments/draft",
        method="POST",
        token=token,
        key="smoke-draft-" + str(uuid.uuid4()),
        body={
            "sourceWalletId": usd["walletId"],
            "recipientId": recipient["id"],
            "sourceAmount": "10.0000",
            "sourceCurrency": "USD",
            "payoutCurrency": "INR",
            "purpose": "FAMILY_SUPPORT",
            "preference": "BALANCED",
        },
    )["data"]
    return draft["id"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--mode", choices=["compose", "external"])
    args = parser.parse_args()
    load_env(args.env_file)

    password = os.environ.get("SEED_CUSTOMER_PASSWORD", "")
    if not password:
        print("smoke failed: SEED_CUSTOMER_PASSWORD is required")
        return 2
    base_url = os.environ.get("SEED_BASE_URL", "http://localhost:8080")
    email = os.environ.get("SEED_ALICE_EMAIL", "priya.sharma@gmail.com")
    mode = args.mode or os.environ.get("FLUXPAY_INFRA_MODE", "compose").lower()
    bootstrap = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    try:
        login = api_request(
            base_url,
            "/api/auth/login",
            method="POST",
            body={"email": email, "password": password},
        )
        token = login["data"]["token"]
        payment_id = _seed_payment(base_url, token)
        envelope = canonical_envelope(payment_id)
        command, payload = producer_invocation(mode, bootstrap, envelope)
        produced = subprocess.run(command, cwd=PROJECT_ROOT, input=payload, capture_output=True, timeout=20)
        if produced.returncode != 0:
            raise RuntimeError("Kafka producer failed")

        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            timeline = api_request(base_url, "/api/payments/" + payment_id + "/timeline", token=token)
            if timeline_contains(timeline, envelope["eventId"]):
                print(f"smoke PASS payment={payment_id} event={envelope['eventId']}")
                return 0
            time.sleep(0.25)
        raise RuntimeError("canonical event was not persisted in the payment timeline")
    except (KeyError, OSError, RuntimeError, ValueError) as exception:
        print(f"smoke failed: {exception}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
