"""流量桥接服务的单元测试。

只测三个纯解析函数，不发任何真实网络请求，用真实抓到的上游响应样本做输入。
运行方式：在 vps/traffic-status 目录下执行 `python3 -m unittest test_app`。
"""

import unittest

from app import ProviderFailure, _failure_state, parse_hostvds, parse_subscription_userinfo


class HostVdsParsingTest(unittest.TestCase):
    """HostVDS 解析测试，重点是单位换算和下次重置日期的推算。"""

    def test_parses_provider_billing_units_and_next_reset(self):
        """内部单位 197173 应换算成 197.173 GB（除以 1000）；
        base_traffic 1000 应换算成 1000 GB 总量；
        重置日期 7 月 20 日应推算出下次是 8 月 20 日。"""
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
        """一台服务器都没匹配上时要明确报错，而不是返回一份空数据。"""
        with self.assertRaises(ProviderFailure) as raised:
            parse_hostvds([])
        self.assertEqual("server_not_found", raised.exception.code)


class SanmaoParsingTest(unittest.TestCase):
    """机场订阅响应头的解析测试。"""

    def test_parses_header_and_clamps_negative_remaining(self):
        """这是一份已经超量的样本：上传加下载共 509.439 GiB，套餐只有 500 GiB。
        剩余应当被夹到 0（而不是负数），超出的 9.439 单独放进 over_gb。
        expire 时间戳按东八区换算得到 2027-04-25。"""
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
        """响应头整个缺失（机场换了实现、或链接失效）时要报专门的错误码。"""
        with self.assertRaises(ProviderFailure) as raised:
            parse_subscription_userinfo(None)
        self.assertEqual("missing_userinfo", raised.exception.code)


class FailureStateTest(unittest.TestCase):
    """降级逻辑的测试：上游挂了，旧数据要能保住。"""

    def test_preserves_last_successful_values(self):
        """失败后 remaining_gb 这类业务数据应原样保留，
        只有 status 被改成错误码，并额外打上 stale 标记。"""
        previous = {"status": "ok", "remaining_gb": 42.5, "fetched_at": "before"}
        state = _failure_state(previous, "auth_expired", "now")

        self.assertEqual(42.5, state["remaining_gb"])
        self.assertEqual("auth_expired", state["status"])
        self.assertTrue(state["stale"])
        self.assertEqual("now", state["attempted_at"])


if __name__ == "__main__":
    unittest.main()
