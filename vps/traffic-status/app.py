#!/usr/bin/env python3
"""Read-only traffic status bridge for HostVDS and the Sanmao subscription."""

from __future__ import annotations

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
HOSTVDS_REFERER = "https://hostvds.com/control/servers/list"
HOSTVDS_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0 Safari/537.36"
)
SANMAO_USER_AGENT = "clash-verge/v2.4.0"
MAX_RESPONSE_BYTES = 1_048_576
GIB = 1024**3
LOGGER = logging.getLogger("traffic-status")


class ProviderFailure(Exception):
    """A sanitized upstream failure safe to expose as a status code."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _number(value: Any, field: str) -> float:
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
    return round(value, 3)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _next_month_date(value: Any) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
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
    items = payload
    if isinstance(payload, dict):
        items = payload.get("results", payload.get("data"))
    if not isinstance(items, list):
        raise ProviderFailure("invalid_response")

    traffic_items = [item for item in items if isinstance(item, dict) and item.get("type") == "traffic"]
    if server_id:
        traffic_items = [item for item in traffic_items if item.get("external_id") == server_id]
    if len(traffic_items) != 1:
        raise ProviderFailure("server_not_found" if not traffic_items else "multiple_servers")

    item = traffic_items[0]
    used_internal = _number(item.get("traffic_month", item.get("trafficMonth")), "traffic_month")
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
    base_gb = _number(network_plan.get("base_traffic"), "base_traffic")
    paid_internal = _number(item.get("paid_traffic", 0), "paid_traffic")
    total_internal = base_gb * 1000 + paid_internal
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
    if not header:
        raise ProviderFailure("missing_userinfo")
    values: dict[str, str] = {}
    for part in header.split(";"):
        key, separator, value = part.strip().partition("=")
        if separator and key:
            values[key.strip().lower()] = value.strip()
    if not {"upload", "download", "total"}.issubset(values):
        raise ProviderFailure("invalid_response")

    upload = _number(values["upload"], "upload")
    download = _number(values["download"], "download")
    total = _number(values["total"], "total")
    used = upload + download
    remaining = max(total - used, 0)
    over = max(used - total, 0)

    expire_at = None
    if values.get("expire"):
        try:
            expire_timestamp = int(values["expire"])
            expire_at = datetime.fromtimestamp(
                expire_timestamp, timezone(timedelta(hours=8))
            ).date().isoformat()
        except (ValueError, OverflowError, OSError):
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
    try:
        value = path.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise ProviderFailure("not_configured") from error
    if not value:
        raise ProviderFailure("not_configured")
    return value


def _read_limited(response: Any) -> bytes:
    body = response.read(MAX_RESPONSE_BYTES + 1)
    if len(body) > MAX_RESPONSE_BYTES:
        raise ProviderFailure("invalid_response")
    return body


def fetch_hostvds(cookie_file: Path, server_id: str, timeout_seconds: float) -> dict[str, Any]:
    cookie = _read_secret(cookie_file)
    request = Request(
        HOSTVDS_URL,
        headers={
            "Accept": "application/json",
            "Cookie": cookie,
            "Referer": HOSTVDS_REFERER,
            "User-Agent": HOSTVDS_USER_AGENT,
        },
        method="GET",
    )
    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            payload = json.loads(_read_limited(response).decode("utf-8"))
    except HTTPError as error:
        raise ProviderFailure("auth_expired" if error.code in (401, 403) else "upstream_error") from error
    except (URLError, TimeoutError, OSError) as error:
        raise ProviderFailure("upstream_error") from error
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProviderFailure("invalid_response") from error
    return parse_hostvds(payload, server_id)


def fetch_sanmao(url_file: Path, timeout_seconds: float) -> dict[str, Any]:
    url = _read_secret(url_file)
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
    state = dict(previous) if isinstance(previous, dict) else {}
    state.update({"status": code, "stale": bool(previous), "attempted_at": attempted_at})
    return state


class TrafficCollector:
    def __init__(self) -> None:
        self.cookie_file = Path(os.getenv("HOSTVDS_COOKIE_FILE", "/config/hostvds.cookie"))
        self.sanmao_url_file = Path(os.getenv("SANMAO_URL_FILE", "/config/sanmao.url"))
        self.cache_file = Path(os.getenv("CACHE_FILE", "/data/cache.json"))
        self.server_id = os.getenv("HOSTVDS_SERVER_ID", "").strip()
        self.cache_ttl_seconds = max(10, int(os.getenv("CACHE_TTL_SECONDS", "600")))
        self.timeout_seconds = max(1.0, float(os.getenv("UPSTREAM_TIMEOUT_SECONDS", "15")))
        self.lock = threading.Lock()
        self.last_refresh_monotonic = 0.0
        self.snapshot = self._load_cache()

    def _load_cache(self) -> dict[str, Any]:
        try:
            loaded = json.loads(self.cache_file.read_text(encoding="utf-8"))
            if isinstance(loaded, dict) and isinstance(loaded.get("providers"), dict):
                return loaded
        except (OSError, json.JSONDecodeError):
            pass
        return {"providers": {}}

    def _save_cache(self) -> None:
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
            if temporary_path is not None and temporary_path.exists():
                temporary_path.unlink()

    def get_snapshot(self) -> dict[str, Any]:
        now = time.monotonic()
        if now - self.last_refresh_monotonic < self.cache_ttl_seconds:
            return self.snapshot
        with self.lock:
            now = time.monotonic()
            if now - self.last_refresh_monotonic < self.cache_ttl_seconds:
                return self.snapshot
            return self._refresh()

    def _refresh(self) -> dict[str, Any]:
        attempted_at = _utc_now()
        previous = self.snapshot.get("providers", {})
        jobs = {
            "hostvds": lambda: fetch_hostvds(
                self.cookie_file, self.server_id, self.timeout_seconds
            ),
            "sanmao": lambda: fetch_sanmao(self.sanmao_url_file, self.timeout_seconds),
        }
        providers: dict[str, Any] = {}
        with ThreadPoolExecutor(max_workers=2, thread_name_prefix="traffic-upstream") as executor:
            futures = {name: executor.submit(job) for name, job in jobs.items()}
            for name, future in futures.items():
                try:
                    state = future.result()
                    state["fetched_at"] = attempted_at
                    providers[name] = state
                    LOGGER.info("provider=%s status=ok", name)
                except ProviderFailure as error:
                    providers[name] = _failure_state(previous.get(name), error.code, attempted_at)
                    LOGGER.warning("provider=%s status=%s", name, error.code)
                except Exception:
                    providers[name] = _failure_state(previous.get(name), "internal_error", attempted_at)
                    LOGGER.exception("provider=%s status=internal_error", name)
        self.snapshot = {"providers": providers, "updated_at": attempted_at}
        self.last_refresh_monotonic = time.monotonic()
        try:
            self._save_cache()
        except OSError:
            LOGGER.exception("cache_write_failed")
        return self.snapshot


class TrafficRequestHandler(BaseHTTPRequestHandler):
    collector: TrafficCollector
    server_version = "TrafficStatus/1.0"
    sys_version = ""

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path == "/healthz":
            self._send_json(HTTPStatus.OK, {"status": "ok"})
            return
        if path != "/traffic":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        self._send_json(HTTPStatus.OK, self.collector.get_snapshot())

    def do_HEAD(self) -> None:
        path = urlsplit(self.path).path
        self._send_json(
            HTTPStatus.OK if path in ("/healthz", "/traffic") else HTTPStatus.NOT_FOUND,
            None,
        )

    def _send_json(self, status: HTTPStatus, payload: dict[str, Any] | None) -> None:
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
        LOGGER.info("client=%s %s", self.client_address[0], format_string % args)


def main() -> None:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    port = int(os.getenv("PORT", "8080"))
    TrafficRequestHandler.collector = TrafficCollector()
    server = ThreadingHTTPServer(("0.0.0.0", port), TrafficRequestHandler)
    LOGGER.info("listening port=%d", port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
