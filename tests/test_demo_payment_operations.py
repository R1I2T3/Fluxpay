import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import unittest
from pathlib import Path
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))


def load_script():
    path = SCRIPTS_DIR / "demo-payment-operations.py"
    spec = importlib.util.spec_from_file_location("demo_payment_operations", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HttpResponse:
    def __init__(self, status, body):
        self.status = status
        self.body = json.dumps(body).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self):
        return self.body


class DemoPaymentOperationsTests(unittest.TestCase):
    def setUp(self):
        self.script = load_script()
        self.base_url = "http://backend:8080"
        self.requests = []
        self.idempotency_keys = []

    def response_for(self, request, timeout):
        self.requests.append(request)
        key = request.get_header("Idempotency-key")
        if key:
            self.idempotency_keys.append(key)
        path = request.full_url.removeprefix(self.base_url)
        method = request.get_method()
        if path == "/api/wallets" and method == "GET":
            return HttpResponse(
                200,
                {
                    "data": [
                        {
                            "walletId": "wallet-usd",
                            "currency": "USD",
                            "availableBalance": "50.0000",
                        }
                    ]
                },
            )
        if path == "/api/recipients" and method == "GET":
            return HttpResponse(
                200,
                {
                    "data": [
                        {"id": "recipient-inr", "currency": "INR", "status": "ACTIVE"},
                    ]
                },
            )
        if path == "/api/payments/draft" and method == "POST":
            return HttpResponse(201, {"data": {"id": "payment-1"}})
        if path == "/api/payments/payment-1/quotes" and method == "POST":
            return HttpResponse(
                201,
                {
                    "data": {
                        "quotes": [
                            {"id": "quote-other", "routeCode": "BANK_EXPRESS"},
                            {"id": "quote-selected", "routeCode": "BANK_STANDARD"},
                        ]
                    }
                },
            )
        if path == "/api/payments/payment-1/confirm" and method == "POST":
            return HttpResponse(200, {"data": {"id": "payment-1", "status": "PROCESSING"}})
        if path == "/api/payments/payment-1/submit-payout" and method == "POST":
            return HttpResponse(200, {"data": {"id": "payment-1", "routeCode": "BANK_STANDARD"}})
        if path == "/api/auth/login" and method == "POST":
            return HttpResponse(
                200,
                {
                    "data": {
                        "token": "signed-token",
                        "user": {"kycStatus": "VERIFIED"},
                    }
                },
            )
        raise AssertionError(f"unexpected request: {method} {path}")

    def test_readme_contains_complete_payment_operations_runbook(self):
        readme = (ROOT / "README.md").read_text()
        required = (
            "scripts/demo-payment-operations.py retry-success",
            "scripts/demo-payment-operations.py refund-exhaustion",
            "FLUXPAY_DEVELOPMENT_RECOVERY_DELAY_SECONDS=5",
            "BANK_STANDARD",
            "BANK_EXPRESS",
            "Admin → Payment Operations",
            "5–8 minute",
            "verified KYC",
            "funded USD wallet",
            "active INR recipient",
            "skipped integration tests are not acceptance",
        )
        for text in required:
            self.assertIn(text, readme)

    def test_create_demo_payment_uses_exact_quote_and_unique_idempotency_keys(self):
        with mock.patch.object(self.script.urllib.request, "urlopen", side_effect=self.response_for):
            result = self.script.create_demo_payment(
                self.base_url,
                "token",
                "retry-success",
                "BANK_STANDARD",
            )

        self.assertEqual(result["routeCode"], "BANK_STANDARD")
        self.assertEqual(result["scenario"], "retry-success")
        self.assertEqual(len(set(self.idempotency_keys)), 4)
        self.assertNotIn("/refund", [request.full_url for request in self.requests])
        self.assertEqual(
            [request.full_url.removeprefix(self.base_url) for request in self.requests],
            [
                "/api/wallets",
                "/api/recipients",
                "/api/payments/draft",
                "/api/payments/payment-1/quotes",
                "/api/payments/payment-1/confirm",
                "/api/payments/payment-1/submit-payout",
            ],
        )
        draft = json.loads(self.requests[2].data)
        confirm = json.loads(self.requests[4].data)
        payout = json.loads(self.requests[5].data)
        self.assertEqual(draft["sourceAmount"], "10.0000")
        self.assertEqual(draft["purpose"], "FAMILY_SUPPORT")
        self.assertEqual(draft["preference"], "BALANCED")
        self.assertEqual(confirm["quoteId"], "quote-selected")
        self.assertEqual(payout["routeCode"], "BANK_STANDARD")

    def test_missing_requested_route_fails_clearly(self):
        with self.assertRaisesRegex(RuntimeError, "BANK_STANDARD.*BANK_EXPRESS"):
            self.script.find_quote(
                {"quotes": [{"routeCode": "BANK_EXPRESS", "id": "q-2"}]},
                "BANK_STANDARD",
            )

    def test_wallet_selection_skips_zero_balance_wallets(self):
        wallet = self.script.select_wallet(
            [
                {"walletId": "eur", "currency": "EUR", "availableBalance": "0.0000"},
                {"walletId": "usd", "currency": "USD", "availableBalance": "50.0000"},
            ],
            "10.0000",
        )
        self.assertEqual(wallet["walletId"], "usd")

    def test_recipient_selection_requires_an_active_recipient(self):
        recipient = self.script.select_recipient(
            [
                {"id": "blocked", "status": "BLOCKED"},
                {"id": "active", "status": "ACTIVE"},
            ]
        )
        self.assertEqual(recipient["id"], "active")
        with self.assertRaisesRegex(RuntimeError, "active recipient"):
            self.script.select_recipient([{"id": "blocked", "status": "BLOCKED"}])

    def test_create_demo_payment_stops_before_draft_without_a_funded_wallet(self):
        def only_unfunded_wallet(request, timeout):
            self.requests.append(request)
            return HttpResponse(
                200,
                {
                    "data": [
                        {"walletId": "empty", "currency": "USD", "availableBalance": "0.0000"},
                    ]
                },
            )

        with (
            mock.patch.object(self.script.urllib.request, "urlopen", side_effect=only_unfunded_wallet),
            self.assertRaisesRegex(RuntimeError, "funded wallet"),
        ):
            self.script.create_demo_payment(self.base_url, "token", "retry-success", "BANK_STANDARD")

        self.assertEqual([request.full_url for request in self.requests], [f"{self.base_url}/api/wallets"])

    def test_create_demo_payment_stops_before_draft_without_an_active_recipient(self):
        def no_active_recipient(request, timeout):
            self.requests.append(request)
            if request.full_url.endswith("/api/wallets"):
                return HttpResponse(
                    200,
                    {
                        "data": [
                            {"walletId": "funded", "currency": "USD", "availableBalance": "50.0000"},
                        ]
                    },
                )
            return HttpResponse(200, {"data": [{"id": "blocked", "currency": "INR", "status": "BLOCKED"}]})

        with (
            mock.patch.object(self.script.urllib.request, "urlopen", side_effect=no_active_recipient),
            self.assertRaisesRegex(RuntimeError, "active recipient"),
        ):
            self.script.create_demo_payment(self.base_url, "token", "retry-success", "BANK_STANDARD")

        self.assertEqual(
            [request.full_url for request in self.requests],
            [f"{self.base_url}/api/wallets", f"{self.base_url}/api/recipients"],
        )

    def test_api_request_sets_headers_only_for_a_body_and_accepts_all_2xx_responses(self):
        with mock.patch.object(self.script.urllib.request, "urlopen", side_effect=self.response_for):
            without_body = self.script.api_request(
                self.base_url,
                "/api/wallets",
                method="GET",
                token="token",
            )
            with_body = self.script.api_request(
                self.base_url,
                "/api/auth/login",
                method="POST",
                body={"email": "customer@example.test", "password": "secret"},
            )

        self.assertIn("data", without_body)
        self.assertIn("data", with_body)
        self.assertEqual(self.requests[0].get_header("Accept"), "application/json")
        self.assertEqual(self.requests[0].get_header("Authorization"), "Bearer token")
        self.assertIsNone(self.requests[0].get_header("Content-type"))
        self.assertIsNone(self.requests[0].get_header("Idempotency-key"))
        self.assertEqual(self.requests[1].get_header("Content-type"), "application/json")

    def test_api_request_reports_status_code_message_without_exposing_token(self):
        token = "super-secret-token"

        def fail(request, timeout):
            error = __import__("urllib.error").error.HTTPError(
                request.full_url,
                409,
                "Conflict",
                {},
                io.BytesIO(
                    json.dumps({"error": {"code": "IDEMPOTENCY_CONFLICT", "message": "key already used"}}).encode(
                        "utf-8"
                    )
                ),
            )
            raise error

        with (
            mock.patch.object(self.script.urllib.request, "urlopen", side_effect=fail),
            self.assertRaises(RuntimeError) as raised,
        ):
            self.script.api_request(
                self.base_url,
                "/api/payments/draft",
                method="POST",
                token=token,
                body={"amount": "10.0000"},
                key="demo-key",
            )

        message = str(raised.exception)
        self.assertIn("409", message)
        self.assertIn("IDEMPOTENCY_CONFLICT", message)
        self.assertIn("key already used", message)
        self.assertNotIn(token, message)

    def test_main_checks_password_and_route_before_network_calls(self):
        output = io.StringIO()
        for environment, expected in (
            ({"FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD"}, "SEED_CUSTOMER_PASSWORD"),
            (
                {"SEED_CUSTOMER_PASSWORD": "customer-password", "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": ""},
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE",
            ),
        ):
            with (
                self.subTest(expected=expected),
                mock.patch.object(
                    sys,
                    "argv",
                    ["demo-payment-operations.py", "retry-success"],
                ),
                mock.patch.dict(self.script.os.environ, environment, clear=True),
                mock.patch.object(
                    self.script.urllib.request,
                    "urlopen",
                ) as urlopen,
                contextlib.redirect_stdout(output),
            ):
                self.assertEqual(self.script.main(), 2)
                urlopen.assert_not_called()
                self.assertIn(expected, output.getvalue())
                output.seek(0)
                output.truncate(0)

    def test_main_logs_in_as_seeded_customer_and_prints_only_summary_fields(self):
        output = io.StringIO()
        with (
            mock.patch.object(
                sys,
                "argv",
                ["demo-payment-operations.py", "retry-success"],
            ),
            mock.patch.dict(
                self.script.os.environ,
                {
                    "SEED_CUSTOMER_PASSWORD": "customer-password",
                    "SEED_ALICE_EMAIL": "selected.customer@example.test",
                    "SEED_BASE_URL": self.base_url,
                    "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD",
                },
                clear=True,
            ),
            mock.patch.object(self.script.urllib.request, "urlopen", side_effect=self.response_for),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(self.script.main(), 0)

        login = self.requests[0]
        self.assertEqual(login.full_url, f"{self.base_url}/api/auth/login")
        self.assertEqual(
            json.loads(login.data),
            {"email": "selected.customer@example.test", "password": "customer-password"},
        )
        text = output.getvalue()
        self.assertIn("scenario=retry-success", text)
        self.assertIn("paymentId=payment-1", text)
        self.assertIn("routeCode=BANK_STANDARD", text)
        self.assertIn("expectedAttempts=", text)
        self.assertNotIn("customer-password", text)
        self.assertNotIn("signed-token", text)

    def test_main_rejects_an_unverified_customer_before_payment_calls(self):
        output = io.StringIO()

        def unverified_login(request, timeout):
            self.requests.append(request)
            return HttpResponse(
                200,
                {"data": {"token": "signed-token", "user": {"kycStatus": "PENDING"}}},
            )

        with (
            mock.patch.object(sys, "argv", ["demo-payment-operations.py", "refund-exhaustion"]),
            mock.patch.dict(
                self.script.os.environ,
                {
                    "SEED_CUSTOMER_PASSWORD": "customer-password",
                    "FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE": "BANK_EXPRESS",
                },
                clear=True,
            ),
            mock.patch.object(self.script.urllib.request, "urlopen", side_effect=unverified_login),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(self.script.main(), 2)

        self.assertEqual(len(self.requests), 1)
        self.assertIn("verified KYC", output.getvalue())


if __name__ == "__main__":
    unittest.main()
