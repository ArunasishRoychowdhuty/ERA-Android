#!/usr/bin/env python3
"""ERA Remote Gateway V3 — stdlib-only HTTPS-upstream presence gateway.

Run behind a private TLS overlay such as Tailscale Serve. This server binds
loopback by default and deliberately has no remote command or file API.
"""
from __future__ import annotations

import hashlib
import hmac
import http.server
from contextlib import contextmanager
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

PROTOCOL_VERSION = 3
MIN_HEARTBEAT_SECONDS = 15
MAX_HEARTBEAT_SECONDS = 120
DEFAULT_HEARTBEAT_SECONDS = 30
MAX_BODY_BYTES = 8 * 1024
PAIRING_CODE_TTL_SECONDS = 120
ACCESS_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60
MAX_ACCESS_TOKEN_TTL_SECONDS = 90 * 24 * 60 * 60
PAIRING_RATE_WINDOW_MS = 5 * 60 * 1000
PAIRING_RATE_MAX_ATTEMPTS = 20
MIN_HEARTBEAT_GAP_MS = 5_000
MAX_CLOCK_SKEW_MS = 5 * 60 * 1000
PAIRING_CODE_RE = re.compile(r"^[A-Z0-9-]{8,64}$")
DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,128}$")
TOKEN_RE = re.compile(r"^[A-Za-z0-9._~+/-]{32,512}={0,}$")


@dataclass(frozen=True)
class GatewayConfig:
    database_path: str
    admin_token: str
    hash_key: bytes
    bind_host: str = "127.0.0.1"
    port: int = 8765
    pairing_ttl_seconds: int = PAIRING_CODE_TTL_SECONDS
    access_token_ttl_seconds: int = ACCESS_TOKEN_TTL_SECONDS
    heartbeat_seconds: int = DEFAULT_HEARTBEAT_SECONDS

    def validate(self) -> None:
        if not TOKEN_RE.fullmatch(self.admin_token):
            raise ValueError("ERA_GATEWAY_ADMIN_TOKEN must be a 32–512 character URL-safe secret.")
        if len(self.hash_key) < 32:
            raise ValueError("ERA_GATEWAY_HASH_KEY must decode to at least 32 bytes.")
        if not (30 <= self.pairing_ttl_seconds <= 600):
            raise ValueError("Pairing TTL must be between 30 and 600 seconds.")
        if not (60 <= self.access_token_ttl_seconds <= MAX_ACCESS_TOKEN_TTL_SECONDS):
            raise ValueError("Access-token TTL must be between 60 seconds and 90 days.")
        if not (MIN_HEARTBEAT_SECONDS <= self.heartbeat_seconds <= MAX_HEARTBEAT_SECONDS):
            raise ValueError("Heartbeat must be between 15 and 120 seconds.")
        if self.bind_host != "127.0.0.1":
            raise ValueError("The gateway must bind to 127.0.0.1 behind the private TLS overlay.")
        if not (1 <= self.port <= 65535):
            raise ValueError("Gateway port must be between 1 and 65535.")


class GatewayError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def now_ms() -> int:
    return int(time.time() * 1000)


def canonical_pairing_code(value: Any) -> str:
    if not isinstance(value, str):
        raise GatewayError(400, "invalid_pairing_code", "Pairing code is invalid.")
    code = value.strip().upper()
    if not PAIRING_CODE_RE.fullmatch(code):
        raise GatewayError(400, "invalid_pairing_code", "Pairing code is invalid.")
    return code


class GatewayStore:
    def __init__(self, config: GatewayConfig):
        self.config = config
        db_path = Path(config.database_path)
        if config.database_path != ":memory:":
            db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_lock = threading.Lock()
        self.initialize()

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.config.database_path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    @contextmanager
    def connection(self):
        db = self.connect()
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def initialize(self) -> None:
        with self._init_lock, self.connection() as db:
            db.execute("PRAGMA journal_mode = WAL")
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS pairing_codes (
                    code_digest TEXT PRIMARY KEY,
                    created_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    consumed_at_ms INTEGER
                );
                CREATE TABLE IF NOT EXISTS pairing_rate_limits (
                    peer TEXT PRIMARY KEY,
                    window_started_ms INTEGER NOT NULL,
                    attempts INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS devices (
                    device_id TEXT PRIMARY KEY,
                    token_digest TEXT NOT NULL,
                    label TEXT NOT NULL,
                    scopes_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    revoked_at_ms INTEGER,
                    last_seen_at_ms INTEGER,
                    last_heartbeat_at_ms INTEGER
                );
                CREATE TABLE IF NOT EXISTS heartbeat_nonces (
                    device_id TEXT NOT NULL,
                    nonce_digest TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (device_id, nonce_digest),
                    FOREIGN KEY (device_id) REFERENCES devices(device_id) ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS idx_pairing_expiry ON pairing_codes(expires_at_ms);
                CREATE INDEX IF NOT EXISTS idx_nonce_time ON heartbeat_nonces(created_at_ms);
                """
            )

    def digest(self, purpose: bytes, value: str) -> str:
        return hmac.new(self.config.hash_key, purpose + value.encode("utf-8"), hashlib.sha256).hexdigest()

    def create_pairing_code(self) -> tuple[str, int]:
        # 128 bits of entropy; formatting groups improve human transcription.
        raw = secrets.token_hex(16).upper()
        code = f"{raw[:8]}-{raw[8:16]}-{raw[16:24]}-{raw[24:]}"
        created = now_ms()
        expires = created + self.config.pairing_ttl_seconds * 1000
        digest = self.digest(b"pairing:", code)
        with self.connection() as db:
            db.execute("DELETE FROM pairing_codes WHERE expires_at_ms <= ?", (created,))
            db.execute(
                "INSERT INTO pairing_codes(code_digest, created_at_ms, expires_at_ms) VALUES (?, ?, ?)",
                (digest, created, expires),
            )
        return code, expires

    def consume_pairing_code(
        self, code: str, label: str, peer: str
    ) -> tuple[str, str, int, int]:
        code_digest = self.digest(b"pairing:", code)
        created = now_ms()
        access_expires = created + self.config.access_token_ttl_seconds * 1000
        token = secrets.token_urlsafe(48)
        device_id = secrets.token_urlsafe(18)
        token_digest = self.digest(b"access:", token)
        rejected = False
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            rate = db.execute(
                "SELECT window_started_ms, attempts FROM pairing_rate_limits WHERE peer = ?",
                (peer,),
            ).fetchone()
            if rate is None or created - int(rate["window_started_ms"]) >= PAIRING_RATE_WINDOW_MS:
                db.execute(
                    "INSERT INTO pairing_rate_limits(peer, window_started_ms, attempts) VALUES (?, ?, 1) "
                    "ON CONFLICT(peer) DO UPDATE SET window_started_ms=excluded.window_started_ms, attempts=1",
                    (peer, created),
                )
            else:
                attempts = int(rate["attempts"])
                if attempts >= PAIRING_RATE_MAX_ATTEMPTS:
                    raise GatewayError(429, "rate_limited", "Pairing rate limit reached. Try again later.")
                db.execute(
                    "UPDATE pairing_rate_limits SET attempts = attempts + 1 WHERE peer = ?",
                    (peer,),
                )

            pairing = db.execute(
                "SELECT expires_at_ms, consumed_at_ms FROM pairing_codes WHERE code_digest = ?",
                (code_digest,),
            ).fetchone()
            if pairing is None or pairing["consumed_at_ms"] is not None or int(pairing["expires_at_ms"]) <= created:
                rejected = True
            else:
                db.execute(
                    "UPDATE pairing_codes SET consumed_at_ms = ? WHERE code_digest = ? AND consumed_at_ms IS NULL",
                    (created, code_digest),
                )
                db.execute(
                    "INSERT INTO devices(device_id, token_digest, label, scopes_json, created_at_ms, expires_at_ms) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (device_id, token_digest, label, '["presence:write"]', created, access_expires),
                )
        if rejected:
            raise GatewayError(401, "pairing_rejected", "Pairing code is invalid, expired or already used.")
        return device_id, token, access_expires, self.config.heartbeat_seconds

    def record_heartbeat(
        self,
        device_id: str,
        token: str,
        client_time_ms: int,
        nonce: str,
        uptime_ms: int,
    ) -> int:
        if not DEVICE_ID_RE.fullmatch(device_id) or not TOKEN_RE.fullmatch(token):
            raise GatewayError(401, "unauthorized", "Device authentication failed.")
        try:
            canonical_nonce = str(uuid.UUID(nonce))
        except (ValueError, TypeError, AttributeError):
            raise GatewayError(400, "invalid_nonce", "Heartbeat nonce must be a UUID.") from None
        if canonical_nonce != nonce.lower():
            raise GatewayError(400, "invalid_nonce", "Heartbeat nonce must be canonical.")
        current = now_ms()
        if isinstance(client_time_ms, bool) or not isinstance(client_time_ms, int):
            raise GatewayError(400, "invalid_timestamp", "Heartbeat timestamp is invalid.")
        if abs(current - client_time_ms) > MAX_CLOCK_SKEW_MS:
            raise GatewayError(400, "stale_timestamp", "Heartbeat timestamp is outside the accepted clock window.")
        if isinstance(uptime_ms, bool) or not isinstance(uptime_ms, int) or not (0 <= uptime_ms <= 2**63 - 1):
            raise GatewayError(400, "invalid_uptime", "Heartbeat uptime is invalid.")

        expected_token_digest = self.digest(b"access:", token)
        nonce_digest = self.digest(b"nonce:", canonical_nonce)
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            device = db.execute(
                "SELECT token_digest, scopes_json, expires_at_ms, revoked_at_ms, last_heartbeat_at_ms "
                "FROM devices WHERE device_id = ?",
                (device_id,),
            ).fetchone()
            if device is None or not hmac.compare_digest(device["token_digest"], expected_token_digest):
                raise GatewayError(401, "unauthorized", "Device authentication failed.")
            if device["revoked_at_ms"] is not None or int(device["expires_at_ms"]) <= current:
                raise GatewayError(401, "credential_expired", "Device pairing is expired or revoked.")
            try:
                scopes = json.loads(device["scopes_json"])
            except (TypeError, json.JSONDecodeError):
                raise GatewayError(403, "scope_rejected", "Device scope is invalid.") from None
            if scopes != ["presence:write"]:
                raise GatewayError(403, "scope_rejected", "Device scope is not permitted.")
            last_heartbeat = device["last_heartbeat_at_ms"]
            if last_heartbeat is not None and current - int(last_heartbeat) < MIN_HEARTBEAT_GAP_MS:
                raise GatewayError(429, "heartbeat_too_frequent", "Heartbeat arrived too frequently.")
            try:
                db.execute(
                    "INSERT INTO heartbeat_nonces(device_id, nonce_digest, created_at_ms) VALUES (?, ?, ?)",
                    (device_id, nonce_digest, current),
                )
            except sqlite3.IntegrityError:
                raise GatewayError(409, "nonce_replay", "Heartbeat nonce was already used.") from None
            db.execute(
                "DELETE FROM heartbeat_nonces WHERE created_at_ms < ?",
                (current - 24 * 60 * 60 * 1000,),
            )
            db.execute(
                "UPDATE devices SET last_seen_at_ms = ?, last_heartbeat_at_ms = ? WHERE device_id = ?",
                (current, current, device_id),
            )
        return self.config.heartbeat_seconds

    def list_devices(self) -> list[dict[str, Any]]:
        with self.connection() as db:
            rows = db.execute(
                "SELECT device_id, label, scopes_json, created_at_ms, expires_at_ms, revoked_at_ms, last_seen_at_ms "
                "FROM devices ORDER BY created_at_ms DESC LIMIT 200"
            ).fetchall()
        return [
            {
                "device_id": row["device_id"],
                "label": row["label"],
                "scopes": json.loads(row["scopes_json"]),
                "created_at_epoch_ms": row["created_at_ms"],
                "expires_at_epoch_ms": row["expires_at_ms"],
                "revoked": row["revoked_at_ms"] is not None,
                "last_seen_at_epoch_ms": row["last_seen_at_ms"],
            }
            for row in rows
        ]

    def revoke_device(self, device_id: str) -> bool:
        if not DEVICE_ID_RE.fullmatch(device_id):
            raise GatewayError(400, "invalid_device_id", "Device identifier is invalid.")
        with self.connection() as db:
            cursor = db.execute(
                "UPDATE devices SET revoked_at_ms = ? WHERE device_id = ? AND revoked_at_ms IS NULL",
                (now_ms(), device_id),
            )
        return cursor.rowcount == 1


class GatewayRequestHandler(http.server.BaseHTTPRequestHandler):
    server_version = "ERA-Gateway-V3"
    sys_version = ""

    @property
    def gateway(self) -> GatewayStore:
        return self.server.gateway_store  # type: ignore[attr-defined]

    @property
    def config(self) -> GatewayConfig:
        return self.server.gateway_config  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        # Never log request bodies, authorization headers, pairing codes or tokens.
        sys.stderr.write(f"ERA-Gateway {self.client_address[0]} {self.command} {self.path.split('?')[0]}\n")

    def _headers(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Pragma", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def _send(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self._headers(status, body)
        self.wfile.write(body)

    def _error(self, error: GatewayError) -> None:
        self._send(error.status, {"error": error.code, "message": error.message})

    def _read_json(self) -> dict[str, Any]:
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            raise GatewayError(415, "unsupported_media_type", "Content-Type must be application/json.")
        raw_length = self.headers.get("Content-Length", "")
        try:
            length = int(raw_length)
        except (TypeError, ValueError):
            raise GatewayError(411, "length_required", "Content-Length is required.") from None
        if length < 1 or length > MAX_BODY_BYTES:
            raise GatewayError(413, "body_too_large", "Request body is empty or exceeds the size limit.")
        data = self.rfile.read(length)
        try:
            payload = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise GatewayError(400, "invalid_json", "Request body must be valid UTF-8 JSON.") from None
        if not isinstance(payload, dict):
            raise GatewayError(400, "invalid_json", "Request body must be a JSON object.")
        return payload

    def _require_protocol(self, payload: dict[str, Any]) -> None:
        header_version = self.headers.get("X-ERA-Protocol-Version")
        if header_version != str(PROTOCOL_VERSION) or payload.get("protocol_version") != PROTOCOL_VERSION:
            raise GatewayError(400, "protocol_mismatch", "ERA protocol version 3 is required.")

    def _require_admin(self) -> None:
        supplied = self.headers.get("Authorization", "")
        expected = "Bearer " + self.config.admin_token
        if not hmac.compare_digest(supplied, expected):
            raise GatewayError(401, "admin_unauthorized", "Administrator authentication failed.")

    def do_GET(self) -> None:
        try:
            path = urlsplit(self.path)
            if path.query or path.fragment:
                raise GatewayError(400, "invalid_url", "Query strings and fragments are not accepted.")
            if path.path == "/healthz":
                self._send(200, {"ok": True, "protocol_version": PROTOCOL_VERSION})
                return
            if path.path == "/api/v3/admin/devices":
                if self.headers.get("X-ERA-Protocol-Version") != str(PROTOCOL_VERSION):
                    raise GatewayError(400, "protocol_mismatch", "ERA protocol version 3 is required.")
                self._require_admin()
                self._send(200, {"devices": self.gateway.list_devices()})
                return
            raise GatewayError(404, "not_found", "Endpoint not found.")
        except GatewayError as error:
            self._error(error)
        except Exception as error:
            sys.stderr.write(f"ERA-Gateway internal error: {type(error).__name__}\\n")
            self._send(500, {"error": "internal_error", "message": "Gateway request failed."})

    def do_POST(self) -> None:
        try:
            path = urlsplit(self.path)
            if path.query or path.fragment:
                raise GatewayError(400, "invalid_url", "Query strings and fragments are not accepted.")
            payload = self._read_json()
            self._require_protocol(payload)
            if path.path == "/api/v3/admin/pairings":
                self._require_admin()
                if set(payload) != {"protocol_version"}:
                    raise GatewayError(400, "invalid_request", "Unexpected pairing administration fields.")
                code, expires = self.gateway.create_pairing_code()
                self._send(201, {
                    "pairing_code": code,
                    "expires_at_epoch_ms": expires,
                    "expires_in_seconds": self.config.pairing_ttl_seconds,
                })
                return

            if path.path == "/api/v3/pairings/exchange":
                expected_keys = {"protocol_version", "pairing_code", "device_label", "requested_scopes"}
                if set(payload) != expected_keys:
                    raise GatewayError(400, "invalid_request", "Pairing request fields are invalid.")
                code = canonical_pairing_code(payload.get("pairing_code"))
                label = payload.get("device_label")
                scopes = payload.get("requested_scopes")
                if not isinstance(label, str) or not label.strip() or len(label) > 48:
                    raise GatewayError(400, "invalid_device_label", "Device label is invalid.")
                if any(ord(ch) < 32 or ord(ch) == 127 for ch in label):
                    raise GatewayError(400, "invalid_device_label", "Device label is invalid.")
                if scopes != ["presence:write"]:
                    raise GatewayError(403, "scope_rejected", "Only presence:write is available in protocol v3.")
                device_id, token, expires, heartbeat = self.gateway.consume_pairing_code(
                    code, label.strip(), self.client_address[0]
                )
                self._send(201, {
                    "protocol_version": PROTOCOL_VERSION,
                    "device_id": device_id,
                    "access_token": token,
                    "heartbeat_interval_seconds": heartbeat,
                    "expires_at_epoch_ms": expires,
                    "scopes": ["presence:write"],
                })
                return

            match = re.fullmatch(r"/api/v3/devices/([A-Za-z0-9_-]{8,128})/heartbeat", path.path)
            if match:
                expected_keys = {
                    "protocol_version", "device_id", "client_time_epoch_ms", "client_nonce", "uptime_ms"
                }
                if set(payload) != expected_keys:
                    raise GatewayError(400, "invalid_request", "Heartbeat fields are invalid.")
                device_id = match.group(1)
                if payload.get("device_id") != device_id:
                    raise GatewayError(400, "device_mismatch", "Heartbeat device identifier does not match its route.")
                authorization = self.headers.get("Authorization", "")
                if not authorization.startswith("Bearer "):
                    raise GatewayError(401, "unauthorized", "Device authentication failed.")
                token = authorization[7:]
                interval = self.gateway.record_heartbeat(
                    device_id=device_id,
                    token=token,
                    client_time_ms=payload.get("client_time_epoch_ms"),
                    nonce=payload.get("client_nonce"),
                    uptime_ms=payload.get("uptime_ms"),
                )
                self._send(200, {
                    "protocol_version": PROTOCOL_VERSION,
                    "next_heartbeat_seconds": interval,
                })
                return

            revoke = re.fullmatch(r"/api/v3/admin/devices/([A-Za-z0-9_-]{8,128})/revoke", path.path)
            if revoke:
                self._require_admin()
                if set(payload) != {"protocol_version"}:
                    raise GatewayError(400, "invalid_request", "Revocation request fields are invalid.")
                revoked = self.gateway.revoke_device(revoke.group(1))
                if not revoked:
                    raise GatewayError(404, "device_not_found", "Device was not found or was already revoked.")
                self._send(200, {"revoked": True, "device_id": revoke.group(1)})
                return
            raise GatewayError(404, "not_found", "Endpoint not found.")
        except GatewayError as error:
            self._error(error)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as error:
            sys.stderr.write(f"ERA-Gateway internal error: {type(error).__name__}\\n")
            self._send(500, {"error": "internal_error", "message": "Gateway request failed."})

    def do_PUT(self) -> None:
        self._send(405, {"error": "method_not_allowed", "message": "Method not allowed."})

    do_PATCH = do_PUT
    do_DELETE = do_PUT
    do_OPTIONS = do_PUT


class GatewayHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], config: GatewayConfig):
        self.gateway_config = config
        self.gateway_store = GatewayStore(config)
        super().__init__(address, GatewayRequestHandler)


def load_config() -> GatewayConfig:
    admin_token = os.environ.get("ERA_GATEWAY_ADMIN_TOKEN", "")
    hash_key_hex = os.environ.get("ERA_GATEWAY_HASH_KEY", "")
    try:
        hash_key = bytes.fromhex(hash_key_hex)
    except ValueError:
        hash_key = b""
    config = GatewayConfig(
        database_path=os.environ.get("ERA_GATEWAY_DATABASE", "./data/era-gateway.sqlite3"),
        admin_token=admin_token,
        hash_key=hash_key,
        bind_host=os.environ.get("ERA_GATEWAY_BIND", "127.0.0.1"),
        port=int(os.environ.get("ERA_GATEWAY_PORT", "8765")),
        pairing_ttl_seconds=int(os.environ.get("ERA_GATEWAY_PAIRING_TTL_SECONDS", "120")),
        access_token_ttl_seconds=int(os.environ.get("ERA_GATEWAY_ACCESS_TTL_SECONDS", str(ACCESS_TOKEN_TTL_SECONDS))),
        heartbeat_seconds=int(os.environ.get("ERA_GATEWAY_HEARTBEAT_SECONDS", str(DEFAULT_HEARTBEAT_SECONDS))),
    )
    config.validate()
    return config


def main() -> None:
    os.umask(0o077)
    config = load_config()
    server = GatewayHTTPServer((config.bind_host, config.port), config)
    print(
        f"ERA Remote Gateway V3 listening on {config.bind_host}:{config.port}; "
        "place it behind a private HTTPS overlay. No remote command API is enabled.",
        flush=True,
    )
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("ERA Remote Gateway V3 stopping.", flush=True)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
