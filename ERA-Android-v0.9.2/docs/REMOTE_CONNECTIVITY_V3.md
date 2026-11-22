# ERA Remote Connectivity V3 — Client and Private Gateway

Status: **Android client and a deployable loopback-only gateway implementation are included. Local protocol tests pass. Deployment to your own private tailnet and real-phone end-to-end validation are still pending.**

## Security boundary

V3 supports only:
- One-time device pairing.
- Issuing an opaque bearer token scoped to `presence:write`.
- Authenticated device presence heartbeats.
- Admin-only device listing and server-side token revocation.

It does **not** support remote shell, ADB, generic RPC, a remote command queue, arbitrary app actions, file transfer, screen streaming or remote-control operations.

The gateway is designed to bind to `127.0.0.1` and sit behind a private Tailscale Serve HTTPS endpoint. It refuses to bind to `0.0.0.0`. Tailscale ACLs must restrict access to the admin device and the ERA phone. Do not use Tailscale Funnel or public ingress.

## Client protocol

All Android client requests use HTTPS on port 443, normal platform TLS certificate/hostname verification, redirects disabled and bounded JSON responses. The endpoint field accepts DNS hostnames only; HTTP, IP literals, localhost, paths, query strings, fragments and user-info are rejected.

### 1. Pairing exchange

`POST /api/v3/pairings/exchange`

Headers:
- `X-ERA-Protocol-Version: 3`
- `Content-Type: application/json`

Request:
```json
{
  "protocol_version": 3,
  "pairing_code": "ONE-TIME-CODE",
  "device_label": "ERA Android device",
  "requested_scopes": ["presence:write"]
}
```

Successful response:
```json
{
  "protocol_version": 3,
  "device_id": "device_identifier",
  "access_token": "opaque-random-token",
  "heartbeat_interval_seconds": 30,
  "expires_at_epoch_ms": 1799999999000,
  "scopes": ["presence:write"]
}
```

The Android client validates protocol version, device ID, token characters and length, future token expiry (maximum 90 days), heartbeat interval (15–120 seconds), and requires the **only** returned scope to be `presence:write`. It clears the one-time pairing code from UI state after success. The bearer token is encrypted using Android Keystore AES-GCM before storage in app-private preferences.

### 2. Heartbeat

`POST /api/v3/devices/{device_id}/heartbeat`

Headers:
- `Authorization: Bearer <access_token>`
- `X-ERA-Protocol-Version: 3`
- `Content-Type: application/json`
- `Cache-Control: no-store`

Request:
```json
{
  "protocol_version": 3,
  "device_id": "device_identifier",
  "client_time_epoch_ms": 1799999999000,
  "client_nonce": "random-uuid",
  "uptime_ms": 123456
}
```

Response:
```json
{
  "protocol_version": 3,
  "next_heartbeat_seconds": 30
}
```

A 401 or 403 causes the Android client to stop retrying and require a fresh pairing. Other transport/server errors use capped exponential retry with jitter up to 60 seconds. There is no remote-command polling.

## Included gateway implementation

Source: `remote-gateway/server.py`
Tests: `remote-gateway/tests/test_gateway.py`

The server uses Python 3.10+ standard library only (HTTP server, SQLite, HMAC, JSON and crypto-grade `secrets`); no pip packages are required.

### Security controls

- Pairing code is 128-bit random, expires after 120 seconds by default, is single-use and only its HMAC digest is stored.
- Pairing exchange is rate limited per source address.
- Device bearer token is randomly generated; only a keyed HMAC-SHA-256 digest is stored in SQLite.
- The only accepted scope is `presence:write`.
- Pairing code consumption uses a SQLite write transaction so simultaneous replay has exactly one winner.
- Heartbeats validate token, matching device ID, protocol version, UUID nonce, timestamp skew, uptime and current device expiry/revocation state.
- Replayed nonces, expired/revoked devices, wrong tokens, wrong scopes and wrong protocol versions are rejected.
- Admin endpoints require a separate high-entropy secret: issue one-time pairing codes, list devices, and revoke device credentials.
- Request bodies are limited to 8 KiB; client response bodies are limited to 16 KiB.
- Request bodies, pairing codes and Authorization headers are not logged. Error responses do not echo secret inputs.
- The gateway binds to loopback only; deployment docs put it behind private HTTPS overlay access control.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/healthz` | Protocol health |
| POST | `/api/v3/admin/pairings` | Admin issues a short-lived pairing code |
| POST | `/api/v3/pairings/exchange` | Android exchanges the one-time code |
| POST | `/api/v3/devices/{device_id}/heartbeat` | Authenticated presence heartbeat |
| GET | `/api/v3/admin/devices` | Admin lists paired devices |
| POST | `/api/v3/admin/devices/{device_id}/revoke` | Admin revokes server-side token |

All admin operations require the protocol header. Admin POST operations also require a JSON body with `protocol_version: 3`; the authenticated device-list GET has no request body.

## Deploy on a private Linux host

### Requirements

- A Linux host/VM with Python 3.10+.
- Tailscale installed and joined to your private tailnet on that host and the Android phone.
- HTTPS certificates enabled for the tailnet.
- Tailscale ACLs/grants that allow only the intended administrator device and ERA phone to reach the gateway.
- ERA Android v0.9.2 or later.

### 1. Generate secrets

On the gateway host, create two independent secrets:

```sh
python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
python3 -c 'import secrets; print(secrets.token_hex(32))'
```

Copy `.env.example` to `/etc/era-gateway/era-gateway.env`, replace the admin token and hash key with the generated values, and set `ERA_GATEWAY_PUBLIC_URL` to the host's actual MagicDNS FQDN, for example `https://era-gateway.<your-tailnet>.ts.net`.

Keep the file root-readable only:

```sh
sudo install -d -m 700 /etc/era-gateway /var/lib/era-gateway /opt/era-remote-gateway
sudo install -m 600 /path/to/era-gateway.env /etc/era-gateway/era-gateway.env
```

The database path should remain `/var/lib/era-gateway/gateway.sqlite3`. Rotating the HMAC key invalidates existing pairing hashes and requires devices to pair again.

### 2. Install the service

Copy the repository's `remote-gateway/` directory to `/opt/era-remote-gateway`. Then run:

```sh
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin era-gateway
sudo chown -R root:root /opt/era-remote-gateway
sudo chmod 755 /opt/era-remote-gateway
sudo install -m 644 /opt/era-remote-gateway/systemd/era-remote-gateway.service /etc/systemd/system/era-remote-gateway.service
sudo systemctl daemon-reload
sudo systemctl enable --now era-remote-gateway
sudo systemctl status era-remote-gateway
```

The systemd service runs as the unprivileged `era-gateway` account, has a restrictive umask and filesystem sandbox, and listens on `127.0.0.1:8765`.

Local health check:
```sh
curl --fail http://127.0.0.1:8765/healthz
```

### 3. Add private HTTPS using Tailscale Serve

On the gateway host, ensure Tailscale is connected to the private tailnet. Then run:

```sh
sudo tailscale serve --bg --https=443 http://127.0.0.1:8765
sudo tailscale serve status
```

Tailscale Serve terminates HTTPS using a tailnet certificate and proxies requests to the loopback HTTP service. It is available within the tailnet; do **not** run `tailscale funnel`. Check the returned MagicDNS hostname and put the same URL in ERA's gateway field. See the official [Tailscale Serve command reference](https://tailscale.com/docs/reference/tailscale-cli/serve).

### 4. Issue a pairing code

Run on the gateway host:

```sh
sudo bash -c 'set -a; . /etc/era-gateway/era-gateway.env; set +a; curl --fail-with-body -sS -X POST "$ERA_GATEWAY_PUBLIC_URL/api/v3/admin/pairings" -H "Authorization: Bearer $ERA_GATEWAY_ADMIN_TOKEN" -H "X-ERA-Protocol-Version: 3" -H "Content-Type: application/json" --data-binary "{\"protocol_version\":3}"'
```

The returned code expires after 120 seconds by default and can be exchanged once. Share the code only with the intended device owner over a trusted channel; do not add it to chat logs, source files or issue trackers.

On Android:
1. Connect Tailscale to the same private tailnet.
2. Open ERA → **Remote Connectivity V3**.
3. Enter the HTTPS MagicDNS endpoint and one-time pairing code.
4. Tap **Pair this phone**.
5. After paired status appears, tap **Start session**. Android 13+ may ask for notification permission.
6. A persistent notification displays session state and a **Stop** action.

The service sends only the presence heartbeat. It does not receive remote commands. Session restart after process death is manual.

### 5. Admin list and revoke

List paired devices:
```sh
sudo bash -c 'set -a; . /etc/era-gateway/era-gateway.env; set +a; curl --fail-with-body -sS "$ERA_GATEWAY_PUBLIC_URL/api/v3/admin/devices" -H "Authorization: Bearer $ERA_GATEWAY_ADMIN_TOKEN"'
```

Revoke a device immediately:
```sh
sudo bash -c 'set -a; . /etc/era-gateway/era-gateway.env; set +a; curl --fail-with-body -sS -X POST "$ERA_GATEWAY_PUBLIC_URL/api/v3/admin/devices/DEVICE_ID/revoke" -H "Authorization: Bearer $ERA_GATEWAY_ADMIN_TOKEN" -H "X-ERA-Protocol-Version: 3" -H "Content-Type: application/json" --data-binary "{\"protocol_version\":3}"'
```

The ERA app's **Revoke pairing** button clears its local token. It cannot revoke a server-side token while offline; use the admin revoke endpoint as well.

## Tests

Run on the gateway host or development machine:

```sh
python3 -m py_compile server.py tests/test_gateway.py
python3 -m unittest discover -s tests -v
```

The local tests start a temporary loopback HTTP gateway and cover admin authentication, one-time and concurrent pairing replay, access-token hashing, heartbeat, nonce replay, expiry, revocation, strict scopes, rate limits and loopback-only configuration validation.

## Not validated yet

- Deployment on your own Linux host/Tailscale tailnet.
- A real phone paired against your live HTTPS MagicDNS URL.
- Long-running Doze/battery behavior and recovery after actual network loss.
- Any remote action execution, remote file/screen transfer or shell; those are intentionally outside V3.
