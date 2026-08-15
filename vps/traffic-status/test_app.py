import unittest

from app import ProviderFailure, _failure_state, parse_hostvds, parse_subscription_userinfo


class HostVdsParsingTest(unittest.TestCase):
    def test_parses_provider_billing_units_and_next_reset(self):
        result = parse_hostvds(
            [
                {
                    "external_id": "server-1",
                    "type": "traffic",
                    "traffic_month": 197173,
                    "paid_traffic": 0,
                    "is_traffic_free": False,
                    "monthly_reset": "2026-07-20T17:42:32.543213+03:00",
                    "network_plan_details": {
                        "name": "default-1000",
                        "base_traffic": 1000,
                    },
                }
            ]
        )

        self.assertEqual("ok", result["status"])
        self.assertEqual(197.173, result["used_gb"])
        self.assertEqual(1000.0, result["total_gb"])
        self.assertEqual(802.827, result["remaining_gb"])
        self.assertEqual("2026-08-20", result["reset_at"])

    def test_requires_an_unambiguous_server(self):
        with self.assertRaises(ProviderFailure) as raised:
            parse_hostvds([])
        self.assertEqual("server_not_found", raised.exception.code)


class SanmaoParsingTest(unittest.TestCase):
    def test_parses_header_and_clamps_negative_remaining(self):
        result = parse_subscription_userinfo(
            "upload=9563180399; download=537442360943; "
            "total=536870912000; expire=1808625150"
        )

        self.assertEqual(509.439, result["used_gb"])
        self.assertEqual(500.0, result["total_gb"])
        self.assertEqual(0.0, result["remaining_gb"])
        self.assertEqual(9.439, result["over_gb"])
        self.assertEqual("2027-04-25", result["expire_at"])

    def test_rejects_missing_header(self):
        with self.assertRaises(ProviderFailure) as raised:
            parse_subscription_userinfo(None)
        self.assertEqual("missing_userinfo", raised.exception.code)


class FailureStateTest(unittest.TestCase):
    def test_preserves_last_successful_values(self):
        previous = {"status": "ok", "remaining_gb": 42.5, "fetched_at": "before"}
        state = _failure_state(previous, "auth_expired", "now")

        self.assertEqual(42.5, state["remaining_gb"])
        self.assertEqual("auth_expired", state["status"])
        self.assertTrue(state["stale"])
        self.assertEqual("now", state["attempted_at"])


if __name__ == "__main__":
    unittest.main()
