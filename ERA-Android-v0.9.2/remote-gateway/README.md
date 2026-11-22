# ERA Remote Gateway V3

A small, standard-library-only Python gateway for the ERA Android V3 pairing and heartbeat protocol.

**Security scope:** the service supports one-time pairing, device presence heartbeats, listing paired devices and server-side token revocation. It does **not** implement remote commands, shell execution, ADB, file transfer, screen streaming or remote-control actions.

## Requirements

- Linux host or VM with Python 3.10+.
- Tailscale installed and the host joined to the same private tailnet as the Android phone.
- A Tailscale ACL/grant that permits only the intended admin device and phone to reach this service.
- Android device with the ERA v0.9.1+ app and Tailscale connected to that same tailnet.

No pip dependencies are required. The server uses Python's standard library and SQLite.

## 1. Prepare secrets

Generate two independent secrets on the gateway host:

```sh
python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
python3 -c 'import secrets; print(secrets.token_hex(32))'
```

Copy `.env.example` to `/etc/era-gateway/era-gateway.env`. Paste the first value into `ERA_GATEWAY_ADMIN_TOKEN`, the second into `ERA_GATEWAY_HASH_KEY`, and keep the database path as `/var/lib/era-gateway/gateway.sqlite3`.

Protect the file:

```sh
sudo install -d -m 700 /etc/era-gateway /var/lib/era-gateway /opt/era-remote-gateway
sudo install -m 600 /path/to/era-gateway.env /etc/era-gateway/era-gateway.env
```

Never commit the environment file. The admin token is needed to issue pairing codes and revoke devices. The hash key protects pairing-code and access-token digests at rest. Changing the hash key invalidates all existing pairings; issue new pairings after rotating it.

## 2. Install and run the service

Install the repository's `remote-gateway/` directory at `/opt/era-remote-gateway`. Create a dedicated unprivileged service account:

```sh
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin era-gateway
sudo chown -R root:root /opt/era-remote-gateway
sudo chmod 755 /opt/era-remote-gateway
sudo install -m 644 systemd/era-remote-gateway.service /etc/systemd/system/era-remote-gateway.service
sudo systemctl daemon-reload
sudo systemctl enable --now era-remote-gateway
sudo systemctl status era-remote-gateway
```

The process binds to `127.0.0.1:8765` only. Do not change `ERA_GATEWAY_BIND` to `0.0.0.0` and do not expose port 8765 directly to the internet.

A local health check is:

```sh
curl --fail http://127.0.0.1:8765/healthz
```

Expected response:

```json
{"ok":true,"protocol_version":3}
```

## 3. Publish only inside the private tailnet

On the gateway host, confirm Tailscale is connected and MagicDNS is enabled. Then use Tailscale Serve to terminate HTTPS and forward traffic to the loopback-only gateway:

```sh
sudo tailscale serve --bg --https=443 http://127.0.0.1:8765
sudo tailscale serve status
```

Tailscale Serve provisions the HTTPS endpoint and forwards tailnet traffic to the local HTTP service. The Android client must use the host's MagicDNS FQDN, for example:

`https://era-gateway.<your-tailnet>.ts.net`

Keep Tailscale ACLs restricted to the administrator device and the ERA phone. Do **not** enable Tailscale Funnel or another public ingress for this gateway. The gateway has no IP allowlist because it is intentionally loopback-bound behind the private Serve endpoint; tailnet ACLs are the network access boundary.

## 4. Create a one-time pairing code

Run this on the gateway host. Use the actual MagicDNS URL:

```sh
export ERA_GATEWAY_URL='https://era-gateway.<your-tailnet>.ts.net'
sudo bash -c 'set -a; . /etc/era-gateway/era-gateway.env; set +a; \
  curl --fail-with-body -sS -X POST "$ERA_GATEWAY_URL/api/v3/admin/pairings" \
  -H "Authorization: Bearer $ERA_GATEWAY_ADMIN_TOKEN" \
  -H "X-ERA-Protocol-Version: 3" \
  -H "Content-Type: application/json" \
  -d '\''{"protocol_version":3}'\'''
```

The response contains a one-time `pairing_code` that expires after 120 seconds by default. Treat it as a temporary secret. Do not paste it into chat, logs or source files.

On the Android phone:

1. Connect Tailscale to the same private tailnet.
2. Open ERA → **Remote Connectivity V3**.
3. Enter the HTTPS MagicDNS endpoint and the one-time pairing code.
4. Tap **Pair this phone**.
5. After the paired status appears, tap **Start session** and allow notification permission if Android asks.

The client stores the access token encrypted with Android Keystore and sends authenticated heartbeat requests every 30 seconds by default. Access tokens expire after 30 days by default; pairing must then be repeated.

## 5. Administer paired devices

List devices (the response does not include token digests):

```sh
curl --fail-with-body -sS "$ERA_GATEWAY_URL/api/v3/admin/devices" \
  -H "Authorization: Bearer $ERA_GATEWAY_ADMIN_TOKEN"
```

Revoke a device server-side:

```sh
curl --fail-with-body -sS -X POST \
  "$ERA_GATEWAY_URL/api/v3/admin/devices/DEVICE_ID/revoke" \
  -H "Authorization: Bearer $ERA_GATEWAY_ADMIN_TOKEN" \
  -H "X-ERA-Protocol-Version: 3" \
  -H "Content-Type: application/json" \
  -d '{"protocol_version":3}'
```

The ERA app's **Revoke pairing** button removes the token from that phone. It cannot revoke a server-side token while offline; use the admin revoke endpoint as well, so a copied token is invalidated at the gateway.

## Protocol and security controls

- Pairing codes contain 128 bits of randomness, are stored as HMAC digests, expire and are single-use.
- Pairing exchanges are rate-limited. The gateway accepts only the exact `presence:write` scope.
- Access tokens are random opaque values. Only HMAC-SHA-256 digests are stored in SQLite; token values are returned once at pairing.
- Heartbeats require the authenticated device token, protocol version 3, a matching device ID, a recent timestamp, a UUID nonce and bounded uptime.
- Replayed nonces, expired/revoked devices, wrong tokens, protocol mismatches and unexpected scopes are rejected.
- Heartbeat records contain minimal presence data. Authorization headers, pairing codes and request bodies are never logged.
- The database directory is created with restricted permissions; systemd sets a restrictive umask.
- Responses use `Cache-Control: no-store`, and JSON request/response sizes are bounded.
- There is no generic RPC, remote command queue, shell, ADB, file endpoint or screen-stream endpoint.

## Tests

Run the standard-library test suite on the gateway host or development machine:

```sh
python3 -m py_compile server.py tests/test_gateway.py
python3 -m unittest discover -s tests -v
```

The tests start a temporary loopback HTTP server and cover pairing, one-time use, token hashing, heartbeat, nonce replay, expiry, revocation, strict scopes, admin authentication and pairing rate limits.

## Deployment status

The server implementation and local protocol tests are included in the repository. It has not been deployed to your own tailnet, and no live phone-to-gateway network session has been claimed. You still need a server/VM joined to your tailnet, its MagicDNS hostname and the phone's Tailscale enrollment.
