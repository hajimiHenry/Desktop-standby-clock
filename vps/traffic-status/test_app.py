"""流量桥接服务的单元测试。

只测三个纯解析函数，不发任何真实网络请求，用真实抓到的上游响应样本做输入。
运行方式：在 vps/traffic-status 目录下执行 `python3 -m unittest test_app`。
"""

import base64
import json
import tempfile
import unittest
from pathlib import Path

from app import (
    ProviderFailure,
    _failure_state,
    _jwt_expires_at,
    _read_credentials,
    parse_hostvds,
    parse_subscription_userinfo,
)


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


class CredentialsTest(unittest.TestCase):
    """凭证文件解析测试。重点是不能破坏密码本身的字符。"""

    def _write(self, text):
        directory = tempfile.mkdtemp()
        path = Path(directory) / "hostvds.credentials"
        path.write_text(text, encoding="utf-8")
        return path

    def test_preserves_password_characters_verbatim(self):
        """密码里的空格、井号、反斜杠都必须原样保留——对 email 做 strip 是安全的，
        对密码做 strip 会把合法字符吃掉，那种 bug 极难排查。"""
        path = self._write("  user@example.com  \n  pa ss#word\\x  \n")
        email, password = _read_credentials(path)

        self.assertEqual("user@example.com", email)
        self.assertEqual("  pa ss#word\\x  ", password)

    def test_tolerates_crlf_line_endings(self):
        """凭证可能在别处编辑后传上来，行尾带 \r 不该导致密码错误。"""
        path = self._write("user@example.com\r\nsecret\r\n")
        self.assertEqual(("user@example.com", "secret"), _read_credentials(path))

    def test_reports_not_configured_when_incomplete(self):
        """只有邮箱没有密码时要报 not_configured，而不是拿空密码去登录。"""
        path = self._write("user@example.com\n\n")
        with self.assertRaises(ProviderFailure) as raised:
            _read_credentials(path)
        self.assertEqual("not_configured", raised.exception.code)

    def test_reports_not_configured_when_missing(self):
        """文件不存在等同于未配置，调用方据此回落到 Cookie 模式。"""
        with self.assertRaises(ProviderFailure) as raised:
            _read_credentials(Path(tempfile.mkdtemp()) / "absent")
        self.assertEqual("not_configured", raised.exception.code)


class JwtExpiryTest(unittest.TestCase):
    """JWT 有效期解析测试。只解码不验签，所以样本可以随便造。"""

    @staticmethod
    def _token(claims):
        def segment(data):
            raw = json.dumps(data).encode("utf-8")
            return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")

        return f"{segment({'alg': 'HS256'})}.{segment(claims)}.signature"

    def test_reads_expiry_claim(self):
        """正常 JWT 应读出 exp。1786883633 是实际抓到的那个令牌的过期时间。"""
        self.assertEqual(1786883633.0, _jwt_expires_at(self._token({"exp": 1786883633})))

    def test_treats_unparsable_token_as_expired(self):
        """段数不对、base64 坏掉、没有 exp——一律返回 0（视为已过期）。
        这样调用方会重新登录，而不是攥着一个不知死活的令牌去发请求。"""
        self.assertEqual(0.0, _jwt_expires_at("not-a-jwt"))
        self.assertEqual(0.0, _jwt_expires_at("a.b.c"))
        self.assertEqual(0.0, _jwt_expires_at(self._token({"user_id": 1})))


if __name__ == "__main__":
    unittest.main()
