#!/usr/bin/env python3
"""HostVDS 与三毛机场订阅的只读流量查询桥接服务。

跑在自己的 VPS 上，对手机暴露一个 /traffic 接口，返回两个来源的剩余流量。

为什么需要这一层中转：
    查 HostVDS 余量需要账号 Cookie，查机场订阅需要订阅链接，两者都是敏感凭证。
    塞进 APK 里等于公开（反编译就能拿到），所以凭证只以文件形式放在 VPS 上，
    手机端只访问这个不需要认证的只读接口。

设计要点：
    * 只用 Python 标准库，不装任何第三方包，部署时不用操心依赖。
    * 带缓存（默认 10 分钟）。手机每次打开面板都会请求一次，直接回源会把上游打烦，
      也可能触发风控。
    * 上游挂了也不会返回错误页，而是返回上次成功的数据并标记 stale，
      手机端据此显示"旧数据"而不是一片空白。
    * 缓存会落盘，服务重启后仍能立刻给出上次的数据。
"""

from __future__ import annotations

import base64
import json
import logging
import os
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


HOSTVDS_URL = "https://hostvds.com/api/products/?type=traffic"
HOSTVDS_TOKEN_URL = "https://hostvds.com/api/token/"
HOSTVDS_ORIGIN = "https://hostvds.com"
HOSTVDS_REFERER = "https://hostvds.com/control/servers/list"
HOSTVDS_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0 Safari/537.36"
)
# 机场订阅接口只认客户端 UA，伪装成 Clash 才会在响应头里带上流量信息。
SANMAO_USER_AGENT = "clash-verge/v2.4.0"
# 上游响应体大小上限 1MB，防御性措施，正常响应只有几 KB。
MAX_RESPONSE_BYTES = 1_048_576
# HostVDS 的 access token 是有效期 24 小时的 SimpleJWT。提前 5 分钟就当它过期，
# 免得请求正好卡在失效的那一瞬间——多登录一次的代价远小于一次抓取失败。
TOKEN_REFRESH_SKEW_SECONDS = 300
# 1 GiB 的字节数。机场按 1024 进制算，HostVDS 按 1000 进制算，两者不能混用。
GIB = 1024**3
LOGGER = logging.getLogger("traffic-status")


class ProviderFailure(Exception):
    """上游失败的统一表示，携带一个可以安全暴露给客户端的状态码。

    关键在"可以安全暴露"：原始异常里可能含有 URL、Cookie 片段等敏感信息，
    绝不能直接吐给客户端。所以全部收敛成 auth_expired / upstream_error 之类的
    固定短码，手机端再把短码翻译成人话（见 TrafficStatusFormatting）。
    """

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _number(value: Any, field: str) -> float:
    """把上游给的值转成非负浮点数，任何异常情况都归为 invalid_response。"""
    # 必须先挡 bool：Python 里 bool 是 int 的子类，float(True) 会得到 1.0，
    # 那样上游返回个 true 就被悄悄当成 1GB 流量了。
    if isinstance(value, bool):
        raise ProviderFailure("invalid_response")
    try:
        number = float(value)
    except (TypeError, ValueError) as error:
        raise ProviderFailure("invalid_response") from error
    if number < 0:
        raise ProviderFailure("invalid_response")
    return number


def _round(value: float) -> float:
    """统一保留 3 位小数。手机端最多显示 2 位，多留一位余量。"""
    return round(value, 3)


def _utc_now() -> str:
    """当前 UTC 时间，ISO 8601 格式并把 +00:00 换成 Z（手机端的解析器认这个写法）。"""
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _next_month_date(value: Any) -> str | None:
    """把 HostVDS 给的"上次重置时间"推算成"下次重置日期"。

    上游只告诉你上一次是什么时候重置的，而用户关心的是下次什么时候恢复，
    所以这里把月份加一（跨年则年份进位）。日期不变，因为按月计费固定在同一天。

    任何解析失败都返回 None：这只是个锦上添花的信息，不值得让整次查询失败。
    """
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        # Python 3.10 及更早的 fromisoformat 不认结尾的 Z，手动换成 +00:00。
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        next_month = parsed.month + 1
        year = parsed.year
        if next_month == 13:
            next_month = 1
            year += 1
        return parsed.replace(year=year, month=next_month).date().isoformat()
    except ValueError:
        return None


def parse_hostvds(payload: Any, server_id: str = "") -> dict[str, Any]:
    """解析 HostVDS 的产品列表接口，提取流量用量。

    上游没有"查某台服务器流量"的专用接口，只能拉全部产品列表再自己筛。
    HOSTVDS_SERVER_ID 环境变量用来指定是哪一台，账号下只有一台时可以不填。

    单位陷阱：上游的 traffic_month 用的是内部单位，1000 内部单位 = 1 GB，
    所以下面到处在除以 1000。
    """
    # 上游返回格式在不同时期变过：有时是裸数组，有时包在 results 或 data 里，两种都兼容。
    items = payload
    if isinstance(payload, dict):
        items = payload.get("results", payload.get("data"))
    if not isinstance(items, list):
        raise ProviderFailure("invalid_response")

    traffic_items = [item for item in items if isinstance(item, dict) and item.get("type") == "traffic"]
    if server_id:
        traffic_items = [item for item in traffic_items if item.get("external_id") == server_id]
    # 必须精确命中一台。一台都没有说明 ID 配错了；多台说明账号下有多台但没指定是哪台，
    # 这时随便挑一台显示会误导用户，不如直接报错让人去配 HOSTVDS_SERVER_ID。
    if len(traffic_items) != 1:
        raise ProviderFailure("server_not_found" if not traffic_items else "multiple_servers")

    item = traffic_items[0]
    # 字段名上游用过两种写法（下划线和驼峰），都试一遍。
    used_internal = _number(item.get("traffic_month", item.get("trafficMonth")), "traffic_month")
    # 不限流量套餐：只有"已用"有意义，总量和剩余都返回 None。
    if item.get("is_traffic_free") is True:
        return {
            "status": "ok",
            "stale": False,
            "unlimited": True,
            "unit": "GB",
            "used_gb": _round(used_internal / 1000),
            "total_gb": None,
            "remaining_gb": None,
            "over_gb": 0.0,
            "reset_at": _next_month_date(item.get("monthly_reset")),
        }

    network_plan = item.get("network_plan_details")
    if not isinstance(network_plan, dict) or network_plan.get("base_traffic") is None:
        raise ProviderFailure("invalid_response")
    # 总量 = 套餐基础流量 + 额外购买的流量。
    # 注意 base_traffic 的单位已经是 GB，要乘 1000 换算成内部单位才能和另外两个相加。
    base_gb = _number(network_plan.get("base_traffic"), "base_traffic")
    paid_internal = _number(item.get("paid_traffic", 0), "paid_traffic")
    total_internal = base_gb * 1000 + paid_internal
    # 两个都用 max(..., 0) 夹到非负：用超了的时候 remaining 该是 0 而不是负数，
    # 超出部分单独放在 over 里。手机端据此切换显示"还剩多少"还是"超了多少"。
    remaining_internal = max(total_internal - used_internal, 0)
    over_internal = max(used_internal - total_internal, 0)
    return {
        "status": "ok",
        "stale": False,
        "unlimited": False,
        "unit": "GB",
        "used_gb": _round(used_internal / 1000),
        "total_gb": _round(total_internal / 1000),
        "remaining_gb": _round(remaining_internal / 1000),
        "over_gb": _round(over_internal / 1000),
        "reset_at": _next_month_date(item.get("monthly_reset")),
    }


def parse_subscription_userinfo(header: str | None) -> dict[str, Any]:
    """解析机场订阅的 Subscription-Userinfo 响应头。

    这是机场订阅的事实标准：流量信息不在响应体里，而在这个响应头中，
    格式形如 `upload=123; download=456; total=789; expire=1808625150`，
    数值单位是字节，expire 是 Unix 时间戳。

    所以这个来源根本不需要下载订阅内容——发个请求读响应头就够了。
    """
    if not header:
        raise ProviderFailure("missing_userinfo")
    # 按分号拆成键值对。用 partition 而不是 split("=")，是因为值里可能含等号
    # （比如 base64 的填充），partition 只在第一个等号处切开。
    values: dict[str, str] = {}
    for part in header.split(";"):
        key, separator, value = part.strip().partition("=")
        if separator and key:
            values[key.strip().lower()] = value.strip()
    # 这三个字段缺一不可，expire 则是可选的。
    if not {"upload", "download", "total"}.issubset(values):
        raise ProviderFailure("invalid_response")

    # 机场的"已用"是上传和下载之和。
    upload = _number(values["upload"], "upload")
    download = _number(values["download"], "download")
    total = _number(values["total"], "total")
    used = upload + download
    remaining = max(total - used, 0)
    over = max(used - total, 0)

    # 到期时间。固定按东八区换算成日期——机场的套餐周期是按北京时间划的，
    # 用 UTC 算会差一天。
    expire_at = None
    if values.get("expire"):
        try:
            expire_timestamp = int(values["expire"])
            expire_at = datetime.fromtimestamp(
                expire_timestamp, timezone(timedelta(hours=8))
            ).date().isoformat()
        except (ValueError, OverflowError, OSError):
            # 时间戳畸形（非数字、超出范围）时放弃这个字段即可，不影响流量数据。
            expire_at = None

    return {
        "status": "ok",
        "stale": False,
        "unlimited": False,
        "unit": "GiB",
        "used_gb": _round(used / GIB),
        "total_gb": _round(total / GIB),
        "remaining_gb": _round(remaining / GIB),
        "over_gb": _round(over / GIB),
        "expire_at": expire_at,
    }


def _read_secret(path: Path) -> str:
    """从文件读取凭证。

    凭证走文件而不是环境变量，是为了能挂成 Docker secret / 只读卷，
    也避免它出现在 `ps` 或容器的环境变量清单里。文件不存在或为空都算"未配置"，
    手机端会显示 COOKIE REQUIRED 提示用户去配。
    """
    try:
        value = path.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise ProviderFailure("not_configured") from error
    if not value:
        raise ProviderFailure("not_configured")
    return value


def _read_limited(response: Any) -> bytes:
    """带上限地读响应体。多读 1 字节，读到了就说明超限，以此判断而不用先读完再看。"""
    body = response.read(MAX_RESPONSE_BYTES + 1)
    if len(body) > MAX_RESPONSE_BYTES:
        raise ProviderFailure("invalid_response")
    return body


def _read_credentials(path: Path) -> tuple[str, str]:
    """读取 HostVDS 登录凭证。文件两行：第一行邮箱，第二行密码。

    不用 JSON 是因为密码里出现引号、反斜杠的概率不低，而两行纯文本无需任何转义，
    人工用编辑器写入时不会因为转义写错而排查半天。

    密码只去掉行尾的换行和回车，不做 strip：首尾空格在密码里是合法字符。
    """
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise ProviderFailure("not_configured") from error
    lines = text.split("\n")
    email = lines[0].strip() if lines else ""
    password = lines[1].rstrip("\r") if len(lines) > 1 else ""
    if not email or not password:
        raise ProviderFailure("not_configured")
    return email, password


def _jwt_expires_at(token: str) -> float:
    """从 JWT 里读出 exp（秒级时间戳）。读不出来返回 0。

    这里只解码不验签——签名是给 HostVDS 服务端验的，我们只想知道什么时候该换新的。
    读不出时返回 0，调用方会当成"已过期"从而立刻重新登录：宁可多登一次，
    也不要攥着一个不知死活的令牌去请求。
    """
    parts = token.split(".")
    if len(parts) != 3:
        return 0.0
    try:
        padded = parts[1] + "=" * (-len(parts[1]) % 4)
        claims = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
    except (ValueError, TypeError, json.JSONDecodeError, UnicodeDecodeError):
        return 0.0
    try:
        return float(claims.get("exp", 0))
    except (TypeError, ValueError):
        return 0.0


def _login_hostvds(email: str, password: str, timeout_seconds: float) -> str:
    """用邮箱密码换取 access token。

    HostVDS 用的是 Django REST Framework SimpleJWT，POST /api/token/ 返回
    access 与 refresh 一对。这里只取 access：它够用 24 小时，而 refresh 自身也会
    过期、还得额外持久化和轮换——既然有密码可以随时重登，就没必要再存一份同样
    会失效的东西，少一个会过期的状态就少一类故障。
    """
    body = json.dumps({"email": email, "password": password}).encode("utf-8")
    request = Request(
        HOSTVDS_TOKEN_URL,
        data=body,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Origin": HOSTVDS_ORIGIN,
            "Referer": HOSTVDS_REFERER,
            "User-Agent": HOSTVDS_USER_AGENT,
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            payload = json.loads(_read_limited(response).decode("utf-8"))
    except HTTPError as error:
        # 400 是字段缺失、401 是密码不对，两者都得人去改配置，重试没有意义，
        # 所以统一映射成 auth_expired 让手机端显示明确提示而不是笼统的错误。
        raise ProviderFailure(
            "auth_expired" if error.code in (400, 401, 403) else "upstream_error"
        ) from error
    except (URLError, TimeoutError, OSError) as error:
        raise ProviderFailure("upstream_error") from error
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProviderFailure("invalid_response") from error
    token = payload.get("access") if isinstance(payload, dict) else None
    if not isinstance(token, str) or not token:
        raise ProviderFailure("invalid_response")
    return token


class HostVdsAuth:
    """管理 HostVDS 的访问令牌。

    背景：HostVDS 的 access token 是有效期仅 24 小时的 SimpleJWT。原先的做法是人工
    从浏览器复制整串 Cookie，等于用手去追一个每天都死的令牌，注定反复失效。这里改成
    服务自己拿邮箱密码调 /api/token/ 换取，令牌缓存到 /data，重启后不必重新登录。

    仍然保留纯 Cookie 模式：没配凭证文件时按老路走，这样升级不会打断既有部署。
    """

    def __init__(
        self, credentials_file: Path, cookie_file: Path, token_cache_file: Path
    ) -> None:
        self.credentials_file = credentials_file
        self.cookie_file = cookie_file
        self.token_cache_file = token_cache_file
        # 换令牌要加锁：ThreadingHTTPServer 下多个请求可能同时发现令牌过期，
        # 不加锁就会并发登录好几次，既浪费也容易触发上游风控。
        self.lock = threading.Lock()
        self.token = ""
        self.expires_at = 0.0
        self._load_cached_token()

    def uses_credentials(self) -> bool:
        """凭证文件存在且非空才走自助登录，否则回落到人工 Cookie 模式。"""
        try:
            return bool(self.credentials_file.read_text(encoding="utf-8").strip())
        except OSError:
            return False

    def access_token(self, timeout_seconds: float, force_login: bool = False) -> str:
        """返回一个当前有效的 access token，必要时自动登录。

        force_login 供调用方在收到 401 时使用：本地判断没过期不代表服务端认账，
        比如密码在别处改过、令牌被吊销、或者两边时钟有漂移。这时强制重登一次。
        """
        with self.lock:
            if not force_login and self.token and time.time() < self.expires_at:
                return self.token
            email, password = _read_credentials(self.credentials_file)
            token = _login_hostvds(email, password, timeout_seconds)
            self.token = token
            self.expires_at = _jwt_expires_at(token) - TOKEN_REFRESH_SKEW_SECONDS
            self._store_cached_token()
            return token

    def _load_cached_token(self) -> None:
        """启动时从磁盘恢复上次的令牌，避免每次重启都白白登录一次。

        文件不存在、损坏、字段不对，一律当作没有缓存——令牌本来就是可再生的，
        丢了重登即可，没必要为读缓存失败做任何补救。
        """
        try:
            loaded = json.loads(self.token_cache_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        if not isinstance(loaded, dict):
            return
        token = loaded.get("access")
        expires_at = loaded.get("expires_at")
        if isinstance(token, str) and token and isinstance(expires_at, (int, float)):
            self.token = token
            self.expires_at = float(expires_at)

    def _store_cached_token(self) -> None:
        """原子地把令牌写盘，权限 600。

        写法和 TrafficCollector._save_cache 一致：临时文件 → fsync → replace。
        额外多一步 chmod：令牌等同于账号访问权限，不能沿用默认的 644。
        """
        payload = json.dumps({"access": self.token, "expires_at": self.expires_at})
        temporary_path = None
        try:
            self.token_cache_file.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=self.token_cache_file.parent,
                prefix="hostvds-token.",
                suffix=".tmp",
                delete=False,
            ) as temporary:
                temporary.write(payload)
                temporary.flush()
                os.fsync(temporary.fileno())
                temporary_path = Path(temporary.name)
            temporary_path.chmod(0o600)
            temporary_path.replace(self.token_cache_file)
            temporary_path = None
        except OSError:
            # 写盘失败不算致命：内存里的令牌照样能用，最坏结果只是重启后多登一次。
            LOGGER.warning("Unable to persist the HostVDS token cache.")
        finally:
            if temporary_path is not None and temporary_path.exists():
                temporary_path.unlink()


def _hostvds_token_headers(token: str) -> dict[str, str]:
    """构造带令牌的请求头。

    同时发 Authorization 头和 Autohization Cookie：前者是 SimpleJWT 的标准形式，
    后者是 HostVDS 前端实际在用的形式——那个拼写是它自己的，不是这里写错了，
    而且它的值还带一对字面双引号。两种都带上，后端认哪个都能work。
    """
    bearer = f"Bearer {token}"
    return {
        "Accept": "application/json",
        "Authorization": bearer,
        "Cookie": f'Autohization="{bearer}"',
        "Referer": HOSTVDS_REFERER,
        "User-Agent": HOSTVDS_USER_AGENT,
    }


def _fetch_hostvds_once(
    headers: dict[str, str], server_id: str, timeout_seconds: float
) -> dict[str, Any]:
    """发一次 HostVDS 请求并解析。认证方式由调用方通过 headers 决定。"""
    request = Request(HOSTVDS_URL, headers=headers, method="GET")
    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            payload = json.loads(_read_limited(response).decode("utf-8"))
    # 401/403 单独区分出来：这说明凭证失效了，需要重新登录或人工介入，
    # 而不是网络问题。手机端会显示 COOKIE EXPIRED 而不是笼统的 OFFLINE。
    except HTTPError as error:
        raise ProviderFailure("auth_expired" if error.code in (401, 403) else "upstream_error") from error
    except (URLError, TimeoutError, OSError) as error:
        raise ProviderFailure("upstream_error") from error
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProviderFailure("invalid_response") from error
    return parse_hostvds(payload, server_id)


def fetch_hostvds(
    auth: HostVdsAuth, server_id: str, timeout_seconds: float
) -> dict[str, Any]:
    """向 HostVDS 请求流量数据并解析。

    两种认证模式：配了凭证文件就用自助登录换来的 access token；没配则回落到人工
    粘贴的 Cookie。请求头都要伪装成浏览器，否则会被挡掉。
    """
    if not auth.uses_credentials():
        headers = {
            "Accept": "application/json",
            "Cookie": _read_secret(auth.cookie_file),
            "Referer": HOSTVDS_REFERER,
            "User-Agent": HOSTVDS_USER_AGENT,
        }
        return _fetch_hostvds_once(headers, server_id, timeout_seconds)

    token = auth.access_token(timeout_seconds)
    try:
        return _fetch_hostvds_once(_hostvds_token_headers(token), server_id, timeout_seconds)
    except ProviderFailure as failure:
        if failure.code != "auth_expired":
            raise
        # 本地判断没过期但服务端不认，强制重登一次再试。只重试这一次：
        # 密码真的失效时应该尽快把 auth_expired 报上去，而不是陷入登录死循环。
        token = auth.access_token(timeout_seconds, force_login=True)
        return _fetch_hostvds_once(_hostvds_token_headers(token), server_id, timeout_seconds)


def fetch_sanmao(url_file: Path, timeout_seconds: float) -> dict[str, Any]:
    """向机场订阅链接发请求，只为了读响应头里的流量信息。"""
    url = _read_secret(url_file)
    # 强制 https：订阅链接本身就是凭证（谁拿到谁就能用你的机场），
    # 明文传输等于送人。
    if urlsplit(url).scheme != "https":
        raise ProviderFailure("invalid_configuration")
    request = Request(
        url,
        headers={"Accept": "*/*", "User-Agent": SANMAO_USER_AGENT},
        method="GET",
    )
    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            header = response.headers.get("Subscription-Userinfo")
    except HTTPError as error:
        raise ProviderFailure("auth_expired" if error.code in (401, 403) else "upstream_error") from error
    except (URLError, TimeoutError, OSError) as error:
        raise ProviderFailure("upstream_error") from error
    return parse_subscription_userinfo(header)


def _failure_state(previous: dict[str, Any] | None, code: str, attempted_at: str) -> dict[str, Any]:
    """构造失败状态：保留上次成功的数据，只覆盖状态字段。

    这是"上游挂了也要有东西看"的关键。先复制上次的完整数据（里面有流量数字），
    再把 status 改成错误码、stale 标记为 True。手机端于是能显示
    "802GB 剩余（数据已过期）"，而不是一片空白。

    stale 用 bool(previous) 而不是写死 True：如果压根没有上次数据（首次启动就失败），
    那就不算"过期数据"，手机端会显示 OFFLINE。
    """
    state = dict(previous) if isinstance(previous, dict) else {}
    state.update({"status": code, "stale": bool(previous), "attempted_at": attempted_at})
    return state


class TrafficCollector:
    """负责取数据、缓存、落盘。全部配置通过环境变量注入，方便容器化部署。"""

    def __init__(self) -> None:
        self.cookie_file = Path(os.getenv("HOSTVDS_COOKIE_FILE", "/config/hostvds.cookie"))
        self.credentials_file = Path(
            os.getenv("HOSTVDS_CREDENTIALS_FILE", "/config/hostvds.credentials")
        )
        # 令牌缓存放 /data 而不是 /config：/config 是只读挂载，而且令牌是可再生的
        # 派生物，不该和人工维护的凭证混在一起。
        self.token_cache_file = Path(
            os.getenv("HOSTVDS_TOKEN_CACHE_FILE", "/data/hostvds-token.json")
        )
        self.hostvds_auth = HostVdsAuth(
            self.credentials_file, self.cookie_file, self.token_cache_file
        )
        self.sanmao_url_file = Path(os.getenv("SANMAO_URL_FILE", "/config/sanmao.url"))
        self.cache_file = Path(os.getenv("CACHE_FILE", "/data/cache.json"))
        self.server_id = os.getenv("HOSTVDS_SERVER_ID", "").strip()
        # 缓存 10 分钟、超时 15 秒。两个 max() 是给下限兜底，
        # 防止有人配成 0 导致每次请求都回源、或超时为 0 导致永远失败。
        self.cache_ttl_seconds = max(10, int(os.getenv("CACHE_TTL_SECONDS", "600")))
        self.timeout_seconds = max(1.0, float(os.getenv("UPSTREAM_TIMEOUT_SECONDS", "15")))
        # 服务是多线程的（ThreadingHTTPServer），刷新时要加锁，见 get_snapshot。
        self.lock = threading.Lock()
        # 用 monotonic 而非墙上时钟计时，避免 NTP 校时导致缓存判断错乱。
        self.last_refresh_monotonic = 0.0
        # 启动时先从磁盘恢复上次的数据，这样重启后第一个请求也能立刻有东西返回。
        self.snapshot = self._load_cache()

    def _load_cache(self) -> dict[str, Any]:
        """从磁盘读缓存。文件不存在、损坏、格式不对，一律当作没有缓存。"""
        try:
            loaded = json.loads(self.cache_file.read_text(encoding="utf-8"))
            if isinstance(loaded, dict) and isinstance(loaded.get("providers"), dict):
                return loaded
        except (OSError, json.JSONDecodeError):
            pass
        return {"providers": {}}

    def _save_cache(self) -> None:
        """原子地把缓存写盘。

        先写临时文件、fsync 落盘、再 replace 覆盖目标文件。同一目录内的 replace
        是原子操作，所以任何时刻读到的缓存文件要么是完整的旧版本、要么是完整的新版本，
        绝不会读到写了一半的残缺 JSON——那会导致下次启动时缓存直接作废。
        """
        self.cache_file.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(self.snapshot, ensure_ascii=False, indent=2, sort_keys=True)
        temporary_path = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=self.cache_file.parent,
                prefix="cache.",
                suffix=".tmp",
                delete=False,
            ) as temporary:
                temporary.write(payload)
                temporary.flush()
                os.fsync(temporary.fileno())
                temporary_path = Path(temporary.name)
            temporary_path.replace(self.cache_file)
        finally:
            # 中途出错时清理残留的临时文件。成功 replace 后临时文件已不存在，
            # 所以这里的 exists() 判断是必要的。
            if temporary_path is not None and temporary_path.exists():
                temporary_path.unlink()

    def get_snapshot(self) -> dict[str, Any]:
        """返回当前数据，缓存过期则先刷新。

        这里是经典的"双重检查加锁"：第一次检查不加锁，让绝大多数命中缓存的请求
        直接返回、完全不争锁；只有认为需要刷新时才加锁，进锁后再查一遍——
        因为可能在等锁期间已经有别的线程刷新过了，不然会重复回源。
        """
        now = time.monotonic()
        if now - self.last_refresh_monotonic < self.cache_ttl_seconds:
            return self.snapshot
        with self.lock:
            now = time.monotonic()
            if now - self.last_refresh_monotonic < self.cache_ttl_seconds:
                return self.snapshot
            return self._refresh()

    def _refresh(self) -> dict[str, Any]:
        """并发查询两个上游并合并结果。调用方必须已持有锁。"""
        attempted_at = _utc_now()
        # 留着上次的数据，某个上游失败时用来填充 stale 状态。
        previous = self.snapshot.get("providers", {})
        jobs = {
            "hostvds": lambda: fetch_hostvds(
                self.hostvds_auth, self.server_id, self.timeout_seconds
            ),
            "sanmao": lambda: fetch_sanmao(self.sanmao_url_file, self.timeout_seconds),
        }
        providers: dict[str, Any] = {}
        # 两个上游并行查询，总耗时取决于慢的那个而不是两者之和。
        with ThreadPoolExecutor(max_workers=2, thread_name_prefix="traffic-upstream") as executor:
            futures = {name: executor.submit(job) for name, job in jobs.items()}
            for name, future in futures.items():
                # 每个上游的异常都单独捕获：一个挂了不能影响另一个的结果。
                try:
                    state = future.result()
                    state["fetched_at"] = attempted_at
                    providers[name] = state
                    LOGGER.info("provider=%s status=ok", name)
                except ProviderFailure as error:
                    # 已知的、可预期的失败，状态码是安全的，直接用。
                    providers[name] = _failure_state(previous.get(name), error.code, attempted_at)
                    LOGGER.warning("provider=%s status=%s", name, error.code)
                except Exception:
                    # 兜底：代码 bug 之类的意外。对外只说 internal_error，
                    # 完整堆栈只进日志——异常信息里可能含有 URL 或凭证片段。
                    providers[name] = _failure_state(previous.get(name), "internal_error", attempted_at)
                    LOGGER.exception("provider=%s status=internal_error", name)
        self.snapshot = {"providers": providers, "updated_at": attempted_at}
        self.last_refresh_monotonic = time.monotonic()
        # 写盘失败（磁盘满、权限不对）只记日志，不影响本次返回——
        # 内存里的数据是好的，落盘只是为了重启后能恢复。
        try:
            self._save_cache()
        except OSError:
            LOGGER.exception("cache_write_failed")
        return self.snapshot


class TrafficRequestHandler(BaseHTTPRequestHandler):
    """HTTP 接口，只有两个只读端点：/traffic 取数据，/healthz 供健康检查。

    只支持 GET 和 HEAD，没有任何写操作，所以不需要认证也不怕被人乱调
    ——最坏情况是别人看到你的剩余流量。
    """

    # 类属性，在 main() 里赋值。所有请求线程共用同一个 collector 实例，
    # 缓存才有意义。
    collector: TrafficCollector
    server_version = "TrafficStatus/1.0"
    # 清空 sys_version，避免在响应头里暴露 Python 版本号。
    sys_version = ""

    def do_GET(self) -> None:
        # 只取路径部分，忽略查询串，这样 /traffic?t=123 这种防缓存写法也能正常工作。
        path = urlsplit(self.path).path
        if path == "/healthz":
            self._send_json(HTTPStatus.OK, {"status": "ok"})
            return
        if path != "/traffic":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        self._send_json(HTTPStatus.OK, self.collector.get_snapshot())

    def do_HEAD(self) -> None:
        """HEAD 只回状态码不回内容（payload 传 None），给探活工具用。"""
        path = urlsplit(self.path).path
        self._send_json(
            HTTPStatus.OK if path in ("/healthz", "/traffic") else HTTPStatus.NOT_FOUND,
            None,
        )

    def _send_json(self, status: HTTPStatus, payload: dict[str, Any] | None) -> None:
        """统一的 JSON 响应出口。

        两个响应头值得注意：
        no-store 禁止任何中间层缓存（缓存该由本服务自己管，而不是让 CDN 插一手）；
        nosniff 阻止浏览器猜测内容类型，是个通用的安全加固。
        """
        body = b"" if payload is None else json.dumps(
            payload, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def log_message(self, format_string: str, *args: Any) -> None:
        """覆盖默认实现，把访问日志导到 logging 而不是直接打到 stderr，
        这样能和服务其它日志用同一套格式和级别控制。"""
        LOGGER.info("client=%s %s", self.client_address[0], format_string % args)


def main() -> None:
    """启动 HTTP 服务。用 ThreadingHTTPServer 让多个请求能并发处理，
    否则一个请求在等上游超时时，其它请求全被堵住。"""
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    port = int(os.getenv("PORT", "8080"))
    # 整个进程只建一个 collector，所有请求线程共享它的缓存。
    TrafficRequestHandler.collector = TrafficCollector()
    # 监听 0.0.0.0 是因为跑在容器里，需要接受来自宿主机的转发；
    # 对公网的暴露应当由外层的反向代理来控制。
    server = ThreadingHTTPServer(("0.0.0.0", port), TrafficRequestHandler)
    LOGGER.info("listening port=%d", port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        # Ctrl+C 是正常的停止方式，不该打印一堆堆栈。
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
