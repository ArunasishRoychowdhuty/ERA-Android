import http.client
import json
import os
import tempfile
from concurrent.futures import ThreadPoolExecutor
import threading
import time
import unittest
import uuid
from pathlib import Path

from server import GatewayConfig, GatewayHTTPServer, GatewayStore, PROTOCOL_VERSION


class RemoteGatewayProtocolTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.admin_token = "admin-" + ("A" * 56)
        self.hash_key = bytes(range(32))
        self.config = GatewayConfig(
            database_path=str(Path(self.temp_dir.name) / "gateway.sqlite3"),
            admin_token=self.admin_token,
            hash_key=self.hash_key,
            heartbeat_seconds=30,
        )
        self.server = GatewayHTTPServer(("127.0.0.1", 0), self.config)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.host, self.port = self.server.server_address

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp_dir.cleanup()

    def request(self, method, path, payload=None, token=None, protocol=True):
        connection = http.client.HTTPConnection(self.host, self.port, timeout=3)
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        if protocol:
            headers["X-ERA-Protocol-Version"] = str(PROTOCOL_VERSION)
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        raw = response.read()
        result = json.loads(raw.decode("utf-8")) if raw else {}
        status = response.status
        connection.close()
        return status, result

    def admin_pairing(self):
        status, data = self.request(
            "POST",
            "/api/v3/admin/pairings",
            {"protocol_version": PROTOCOL_VERSION},
            token=self.admin_token,
        )
        self.assertEqual(status, 201)
        self.assertIn("pairing_code", data)
        return data["pairing_code"]

    def pair_device(self):
        code = self.admin_pairing()
        status, data = self.request(
            "POST",
            "/api/v3/pairings/exchange",
            {
                "protocol_version": PROTOCOL_VERSION,
                "pairing_code": code,
                "device_label": "ERA test phone",
                "requested_scopes": ["presence:write"],
            },
        )
        self.assertEqual(status, 201)
        return data

    def heartbeat(self, paired, nonce=None, timestamp=None):
        payload = {
            "protocol_version": PROTOCOL_VERSION,
            "device_id": paired["device_id"],
            "client_time_epoch_ms": int(time.time() * 1000) if timestamp is None else timestamp,
            "client_nonce": str(uuid.uuid4()) if nonce is None else nonce,
            "uptime_ms": 123456,
        }
        return self.request(
            "POST",
            f"/api/v3/devices/{paired['device_id']}/heartbeat",
            payload,
            token=paired["access_token"],
        )

    def test_health_reports_protocol(self):
        status, payload = self.request("GET", "/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(payload, {"ok": True, "protocol_version": 3})

    def test_admin_pairing_requires_secret(self):
        status, _ = self.request(
            "POST",
            "/api/v3/admin/pairings",
            {"protocol_version": 3},
            token="wrong-" + ("x" * 40),
        )
        self.assertEqual(status, 401)

    def test_admin_device_listing_requires_protocol_and_admin_auth(self):
        paired = self.pair_device()
        status, _ = self.request(
            "GET", "/api/v3/admin/devices", token=self.admin_token, protocol=False
        )
        self.assertEqual(status, 400)
        status, _ = self.request(
            "GET", "/api/v3/admin/devices", token="wrong-" + ("x" * 40)
        )
        self.assertEqual(status, 401)
        status, payload = self.request(
            "GET", "/api/v3/admin/devices", token=self.admin_token
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(payload["devices"]), 1)
        self.assertEqual(payload["devices"][0]["device_id"], paired["device_id"])
        self.assertNotIn("token_digest", payload["devices"][0])

    def test_pairing_exchange_and_heartbeat(self):
        paired = self.pair_device()
        self.assertEqual(paired["protocol_version"], 3)
        self.assertEqual(paired["scopes"], ["presence:write"])
        self.assertTrue(32 <= len(paired["access_token"]) <= 512)
        self.assertGreater(paired["expires_at_epoch_ms"], int(time.time() * 1000))
        status, heartbeat = self.heartbeat(paired)
        self.assertEqual(status, 200)
        self.assertEqual(heartbeat, {"protocol_version": 3, "next_heartbeat_seconds": 30})

    def test_pairing_code_is_single_use(self):
        code = self.admin_pairing()
        request = {
            "protocol_version": 3,
            "pairing_code": code,
            "device_label": "ERA test phone",
            "requested_scopes": ["presence:write"],
        }
        first_status, first = self.request("POST", "/api/v3/pairings/exchange", request)
        second_status, _ = self.request("POST", "/api/v3/pairings/exchange", request)
        self.assertEqual(first_status, 201)
        self.assertTrue(first.get("access_token"))
        self.assertEqual(second_status, 401)

    def test_concurrent_pairing_code_replay_has_exactly_one_winner(self):
        code = self.admin_pairing()
        request = {
            "protocol_version": 3,
            "pairing_code": code,
            "device_label": "ERA concurrent test",
            "requested_scopes": ["presence:write"],
        }
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(
                lambda _: self.request("POST", "/api/v3/pairings/exchange", request),
                range(2),
            ))
        self.assertEqual(sorted(status for status, _ in results), [201, 401])
        with GatewayStore(self.config).connection() as db:
            count = db.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
        self.assertEqual(count, 1)

    def test_invalid_gateway_configuration_fails_closed(self):
        from server import GatewayConfig
        with self.assertRaises(ValueError):
            GatewayConfig(
                self.config.database_path,
                self.admin_token,
                self.hash_key,
                bind_host="0.0.0.0",
            ).validate()
        with self.assertRaises(ValueError):
            GatewayConfig(
                self.config.database_path,
                "weak",
                self.hash_key,
            ).validate()

    def test_database_does_not_store_plaintext_access_token(self):
        paired = self.pair_device()
        store = GatewayStore(self.config)
        with store.connection() as db:
            row = db.execute(
                "SELECT token_digest FROM devices WHERE device_id = ?",
                (paired["device_id"],),
            ).fetchone()
        self.assertIsNotNone(row)
        self.assertNotEqual(row["token_digest"], paired["access_token"])
        self.assertEqual(len(row["token_digest"]), 64)

    def test_bad_scope_and_protocol_fail_closed(self):
        code = self.admin_pairing()
        status, _ = self.request(
            "POST",
            "/api/v3/pairings/exchange",
            {
                "protocol_version": 3,
                "pairing_code": code,
                "device_label": "ERA test phone",
                "requested_scopes": ["presence:write", "remote:exec"],
            },
        )
        self.assertEqual(status, 403)

        status, _ = self.request(
            "POST",
            "/api/v3/pairings/exchange",
            {
                "protocol_version": 2,
                "pairing_code": code,
                "device_label": "ERA test phone",
                "requested_scopes": ["presence:write"],
            },
        )
        self.assertEqual(status, 400)

    def test_heartbeat_rejects_wrong_token_and_wrong_device(self):
        paired = self.pair_device()
        payload = {
            "protocol_version": 3,
            "device_id": paired["device_id"],
            "client_time_epoch_ms": int(time.time() * 1000),
            "client_nonce": str(uuid.uuid4()),
            "uptime_ms": 10,
        }
        status, _ = self.request(
            "POST",
            f"/api/v3/devices/{paired['device_id']}/heartbeat",
            payload,
            token="invalid-" + ("z" * 40),
        )
        self.assertEqual(status, 401)
        payload["device_id"] = "other_device_123"
        status, _ = self.request(
            "POST",
            f"/api/v3/devices/{paired['device_id']}/heartbeat",
            payload,
            token=paired["access_token"],
        )
        self.assertEqual(status, 400)

    def test_heartbeat_nonce_replay_is_rejected(self):
        paired = self.pair_device()
        nonce = str(uuid.uuid4())
        first_status, _ = self.heartbeat(paired, nonce=nonce)
        store = GatewayStore(self.config)
        with store.connection() as db:
            db.execute(
                "UPDATE devices SET last_heartbeat_at_ms = ? WHERE device_id = ?",
                (int(time.time() * 1000) - 10_000, paired["device_id"]),
            )
        replay_status, _ = self.heartbeat(paired, nonce=nonce)
        self.assertEqual(first_status, 200)
        self.assertEqual(replay_status, 409)

    def test_expired_and_revoked_credentials_are_rejected(self):
        paired = self.pair_device()
        store = GatewayStore(self.config)
        with store.connection() as db:
            db.execute(
                "UPDATE devices SET expires_at_ms = ? WHERE device_id = ?",
                (int(time.time() * 1000) - 1, paired["device_id"]),
            )
        status, _ = self.heartbeat(paired)
        self.assertEqual(status, 401)

        second = self.pair_device()
        status, _ = self.request(
            "POST",
            f"/api/v3/admin/devices/{second['device_id']}/revoke",
            {"protocol_version": 3},
            token=self.admin_token,
        )
        self.assertEqual(status, 200)
        status, _ = self.heartbeat(second)
        self.assertEqual(status, 401)

    def test_pairing_attempts_are_rate_limited(self):
        bad_request = {
            "protocol_version": 3,
            "pairing_code": "AAAAAAAA-BBBBBBBB-CCCCCCCC-DDDDDDDD",
            "device_label": "ERA test phone",
            "requested_scopes": ["presence:write"],
        }
        status = 0
        for _ in range(20):
            status, _ = self.request("POST", "/api/v3/pairings/exchange", bad_request)
            self.assertEqual(status, 401)
        status, _ = self.request("POST", "/api/v3/pairings/exchange", bad_request)
        self.assertEqual(status, 429)


if __name__ == "__main__":
    unittest.main(verbosity=2)
