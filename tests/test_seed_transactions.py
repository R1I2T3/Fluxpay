"""Offline tests: no database changes or financial requests are made."""

import contextlib
import io
import os
import unittest
from unittest import mock

from test_seed_local import HttpResponse, load_script


IDENTITIES = {
    "system": {"id": "11111111-1111-1111-1111-111111111111"},
    "admin": {"id": "22222222-2222-2222-2222-222222222222"},
    "priya.sharma@gmail.com": {"id": "33333333-3333-3333-3333-333333333333"},
    "arjun.mehta@gmail.com": {"id": "44444444-4444-4444-4444-444444444444"},
}
SETTINGS = {
    "FLUXPAY_SYSTEM_USER_ID": IDENTITIES["system"]["id"],
    "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
    "FLUXPAY_DEVELOPMENT_SIMULATED_COMPLIANCE_ENABLED": "true",
    "SEED_CUSTOMER_PASSWORD": "not-a-real-password",
    "SEED_ADMIN_PASSWORD": "not-a-real-admin-password",
}


class SeedApi:
    """Stateful API double implementing durable replay and current payment reads."""

    def __init__(self):
        self.requests = []
        self.replays = {}
        self.recipients = []
        self.payments = {}
        self.journals = []
        self.legacy = False
        self.review = False
        self.fail_once = None
        self.rails = [{"railType": "BANK_NETWORK"}]

    def __call__(self, base, path, body=None, *, token=None, key=None, method=None):
        self.requests.append((path, body, key, method))
        if key in self.replays:
            old_path, old_body, response = self.replays[key]
            assert (path, body) == (old_path, old_body), "Idempotency payload changed"
            return dict(response)
        if self.fail_once and self.fail_once in path:
            self.fail_once = None
            raise RuntimeError("Synthetic interruption")
        if path == "/api/auth/login":
            return {"token": "synthetic-token"}
        assert token == "synthetic-token"
        if path == "/api/admin/rail-types":
            return {"railTypes": self.rails}
        if path == "/api/users/me":
            return {"kycStatus": "VERIFIED"}
        if path == "/api/bank-accounts/link":
            result = {"id": "bank-" + body["currency"]}
        elif path.endswith("/topup") or path in (
            "/api/wallets/withdraw", "/api/wallets/transfer", "/api/wallets/convert"
        ):
            assert key
            self.journals.append((path, body, key))
            result = {"journalReference": "journal-" + key}
        elif path == "/api/wallets":
            return [{"currency": c, "walletId": "wallet-" + c} for c in ("USD", "EUR", "INR")]
        elif path == "/api/recipients":
            if body is None:
                return self.recipients.copy()
            result = {**body, "id": "recipient-" + str(len(self.recipients))}
            self.recipients.append(result)
        elif path == "/api/payments/draft":
            result = {**body, "id": "payment-" + str(len(self.payments)), "status": "DRAFT"}
            self.payments[result["id"]] = dict(result)
        elif path.startswith("/api/payments/"):
            payment_id = path.split("/")[3]
            payment = self.payments[payment_id]
            if path.endswith("/quotes"):
                if method == "POST":
                    payment["status"] = "QUOTED"
                result = {"expiresAt": "2099-01-01T00:00:00Z", "quotes": [{
                    "id": "quote-" + payment_id,
                    "route" if self.legacy else "routeCode": "HDFC_INR_PRIORITY",
                }]}
            elif path.endswith("/confirm"):
                payment.update(status="UNDER_REVIEW" if self.review else "PROCESSING",
                               selectedQuoteId=body["quoteId"])
                result = dict(payment)
            elif path.endswith("/submit-payout"):
                assert body["routeCode"] == "HDFC_INR_PRIORITY"
                payment["status"] = "COMPLETED"
                result = {"status": "COMPLETED"}
            else:
                return dict(payment)
        else:
            raise AssertionError("Unexpected endpoint: " + path)
        if key:
            self.replays[key] = (path, body, dict(result))
        return result


class SeedTransactionTests(unittest.TestCase):
    def setUp(self):
        self.script = load_script()
        self.api = SeedApi()
        self.enterContext(mock.patch.dict(os.environ, SETTINGS, clear=True))
        self.enterContext(mock.patch.object(self.script, "api_data", side_effect=self.api))
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))

    def run_seed(self):
        return self.script.seed_customer_transactions("http://localhost:8080", IDENTITIES)

    def test_thirty_completed_transactions_four_recipients_and_safe_rerun(self):
        for _ in range(2):
            counts = self.run_seed()
            self.assertEqual(sum(counts.values()), 30)
            self.assertEqual(set(counts.values()), {6})
            self.assertEqual(len(self.api.journals), 24)
            self.assertEqual(len(self.api.payments), 6)
            self.assertEqual({p["status"] for p in self.api.payments.values()}, {"COMPLETED"})
            self.assertEqual(len(self.api.recipients), 4)
        payouts = [r for r in self.api.requests if r[0].endswith("/submit-payout")]
        self.assertEqual(len(payouts), 6, "Completed payments must not resubmit on rerun")
        topups = [body for path, body, _ in self.api.journals if path.endswith("/topup")]
        self.assertEqual(len(topups), 6)
        p2p = [body for path, body, _ in self.api.journals if path.endswith("/transfer")]
        self.assertTrue(all(b["toEmail"] == "arjun.mehta@gmail.com" for b in p2p))

    def test_resume_after_confirmation_without_duplicate_funding_or_payment(self):
        self.api.fail_once = "/submit-payout"
        with self.assertRaisesRegex(RuntimeError, "interruption"):
            self.run_seed()
        self.assertEqual(len(self.api.journals), 24)
        self.assertEqual(self.api.payments["payment-0"]["status"], "PROCESSING")
        self.assertEqual(sum(self.run_seed().values()), 30)
        self.assertEqual(len(self.api.journals), 24)
        self.assertEqual(len(self.api.payments), 6)
        confirms = [r for r in self.api.requests if r[0] == "/api/payments/payment-0/confirm"]
        self.assertEqual(len(confirms), 1)
        payouts = [r for r in self.api.requests if r[0] == "/api/payments/payment-0/submit-payout"]
        self.assertEqual(payouts[0][2], payouts[1][2])

    def test_legacy_route_quotes_also_complete(self):
        self.api.legacy = True
        self.assertEqual(sum(self.run_seed().values()), 30)

    def test_review_stops_without_payout_or_fake_success(self):
        self.api.review = True
        with self.assertRaisesRegex(RuntimeError, "UNDER_REVIEW"):
            self.run_seed()
        self.assertFalse(any(r[0].endswith("/submit-payout") for r in self.api.requests))

    def test_missing_rail_stops_before_bank_link_and_money_movement(self):
        self.api.rails = []
        with self.assertRaisesRegex(ValueError, "BANK_NETWORK"):
            self.run_seed()
        self.assertEqual(self.api.journals, [])
        self.assertFalse(any(r[0] == "/api/bank-accounts/link" for r in self.api.requests))

    def test_nonlocal_url_or_disabled_simulation_stops_before_any_requests(self):
        with self.assertRaisesRegex(ValueError, "localhost"):
            self.script.seed_customer_transactions("https://production.example", IDENTITIES)
        os.environ["FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED"] = "false"
        with self.assertRaisesRegex(ValueError, "SIMULATED_PAYOUTS"):
            self.run_seed()
        self.assertEqual(self.api.requests, [])

    def test_system_id_mismatch_stops_before_requests(self):
        os.environ["FLUXPAY_SYSTEM_USER_ID"] = ""
        with self.assertRaisesRegex(ValueError, "FLUXPAY_SYSTEM_USER_ID"):
            self.run_seed()
        self.assertEqual(self.api.requests, [])

    def test_kyc_fixtures_are_insert_only_and_preserve_review_decisions(self):
        cursor = mock.Mock()
        cursor.fetchone.side_effect = [None, ("VERIFIED",)]
        self.script.prepare_transaction_kyc(cursor, IDENTITIES, b"admin")
        merges = [c for c in cursor.execute.call_args_list if "MERGE" in c.args[0]]
        self.assertEqual(len(merges), 2)
        for call in merges:
            self.assertNotIn("WHEN MATCHED THEN", call.args[0])
            self.assertTrue(call.kwargs["doc_number"].startswith("LOCAL-SEED-FIXTURE-"))
        cursor.reset_mock()
        cursor.fetchone.side_effect = [("PENDING",)]
        with self.assertRaisesRegex(ValueError, "review it in Admin"):
            self.script.prepare_transaction_kyc(cursor, IDENTITIES, b"admin")
        self.assertEqual(cursor.execute.call_count, 1)

    def test_expired_quote_is_refreshed_before_confirm(self):
        expired = "2000-01-01T00:00:00Z"
        quote = {"id": "fresh", "routeCode": "HDFC_INR_PRIORITY"}
        call = mock.Mock(side_effect=[
            {"status": "QUOTED"}, {"expiresAt": expired, "quotes": []},
            {"quotes": [quote]}, {"status": "PROCESSING", "selectedQuoteId": "fresh"},
            {"quotes": [quote]}, {"status": "COMPLETED"}, {"status": "COMPLETED"},
        ])
        self.script.complete_seed_payment(call, "test-payment")
        self.assertEqual(call.call_args_list[2].kwargs["method"], "POST")
        self.assertIn(expired, call.call_args_list[2].kwargs["step"])
        self.assertEqual(call.call_args_list[3].args[1], {"quoteId": "fresh"})

    def test_uncertain_payout_is_not_retried_or_counted_complete(self):
        quote = {"id": "accepted", "routeCode": "HDFC_INR_PRIORITY"}
        call = mock.Mock(side_effect=[
            {"status": "PROCESSING", "selectedQuoteId": "accepted"},
            {"quotes": [quote]}, {"status": "UNCERTAIN"},
        ])
        with self.assertRaisesRegex(RuntimeError, "UNCERTAIN"):
            self.script.complete_seed_payment(call, "test-payment")
        self.assertEqual(call.call_count, 3)

    def test_http_adapter_includes_auth_idempotency_and_bodyless_post(self):
        with mock.patch.object(self.script.urllib.request, "urlopen", return_value=HttpResponse(201, {"data": {}})) as opened:
            self.script.request_json("http://localhost:8080", "/api/payments/p/quotes",
                                     token="test-token", key="stable-key", method="POST")
        request = opened.call_args.args[0]
        self.assertEqual(request.get_method(), "POST")
        self.assertIsNone(request.data)
        self.assertEqual(request.get_header("Authorization"), "Bearer test-token")
        self.assertEqual(request.get_header("Idempotency-key"), "stable-key")

    def test_local_transaction_route_uses_the_current_catalogue_shape(self):
        self.assertEqual(self.script.TRANSACTION_DEMO_PROVIDER[1], "HDFC Bank India")
        self.assertEqual(self.script.TRANSACTION_DEMO_ROUTE[1], "HDFC INR Priority Transfer")
        self.assertEqual(len(self.script.TRANSACTION_DEMO_ROUTE), len(self.script.ROUTES[0]))

    def test_default_cli_runs_transaction_seed_without_extra_flag(self):
        # Exercise main's default rather than only the transaction helper.
        os.environ.update(SEED_SYSTEM_PASSWORD="test-system",
                          ORACLE_JDBC_URL="jdbc:oracle:thin:@//localhost:1521/FREEPDB1")
        provisioned = {"users": 4, "systemWallets": 15, "providers": 5, "routes": 14,
                       "systemUserId": IDENTITIES["system"]["id"]}
        with (mock.patch.object(self.script.sys, "argv", ["seed-local.py"]),
              mock.patch.object(self.script, "load_env"),
              mock.patch.object(self.script, "register_or_login", side_effect=list(IDENTITIES.values())),
              mock.patch.object(self.script, "provision_local_database", return_value=provisioned) as provision,
              mock.patch.object(self.script, "seed_customer_transactions", return_value={"sample": 20}) as seed,
              mock.patch.object(self.script, "retime_local_transactions") as retime):
            self.assertEqual(self.script.main(), 0)
        self.assertTrue(provision.call_args.kwargs["include_transactions"])
        seed.assert_called_once_with("http://localhost:8080", IDENTITIES)
        retime.assert_called_once_with(IDENTITIES["priya.sharma@gmail.com"]["id"])


if __name__ == "__main__":
    unittest.main()
