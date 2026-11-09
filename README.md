# Ultra SMS Gateway

Send SMS from your own applications through Android phones and their SIM cards, at the price of your
operator's SMS bundles instead of the price of a commercial SMS API.

Ultra SMS Gateway is a small self-hosted server:

- an **HTTP API** where your applications queue SMS (OTP codes, notifications…);
- a **queue** that keeps every message until a phone is available, so nothing is lost while a phone is offline;
- a **WebSocket channel** to Android phones that send the SMS with their SIM card and report back;
- a **dashboard** to audit every message, manage phones and API keys.

```
┌──────────────┐  POST /v1/messages   ┌──────────────────────┐   WebSocket    ┌───────────────┐
│ Your apps    │ ───────────────────▶ │  Ultra SMS Gateway   │ ◀────────────▶ │ Android phone │ ──▶ mobile network
│ (API key)    │ ◀─── status ──────── │  API · queue · admin │  send / report │ + SIM card    │
└──────────────┘                      └──────────┬───────────┘                └───────────────┘
                                                 │
                                            PostgreSQL
```

> **Status:** early development. The server works and is tested; the Android app is the next milestone.
> See [Roadmap](#roadmap).

## Features

- Queue with a time to live per message: an OTP that waits too long expires instead of arriving late.
- At-least-once delivery to phones: unacknowledged messages go back to the queue, phones deduplicate.
- Idempotent API: send a `clientReference` and a retried request never sends the SMS twice.
- Message text is erased as soon as the SMS is sent, failed or expired, and is never logged or shown.
- One API key per client application, one token per phone, both revocable from the dashboard.
- Audit trail: every status change of every message, with the phone that handled it.
- Phone health in the dashboard: online state, battery, signal, operator.
- OpenAPI documentation at `/swagger-ui.html`.

## Quick start

Requirements: Docker with Docker Compose.

```bash
git clone <this repository> ultra-sms-gateway
cd ultra-sms-gateway
cp .env.example .env        # then edit the passwords
docker compose up -d --build
```

Open <http://localhost:8080> and sign in with `GATEWAY_ADMIN_USERNAME` / `GATEWAY_ADMIN_PASSWORD`.

1. **API keys → Create key.** Copy the key, it is shown only once.
2. **Devices → Add device.** Copy the server URL and the device token into the Android app.
3. Queue a message:

```bash
curl -X POST http://localhost:8080/v1/messages \
  -H "X-Api-Key: usg_..." \
  -H "Content-Type: application/json" \
  -d '{"to":"+243812345678","body":"Your code is 482913","ttlSeconds":300,"clientReference":"otp-42"}'
```

```json
{ "id": "5f0c…", "to": "+243812345678", "status": "QUEUED", "clientReference": "otp-42", … }
```

4. Follow it with `GET /v1/messages/{id}` or in the dashboard.

In production, put the gateway behind a reverse proxy that terminates TLS (Caddy, Nginx, Traefik):
phones then connect to `wss://your-domain/v1/device/ws`.

## Configuration

All settings are environment variables (see [`application.yaml`](src/main/resources/application.yaml)).

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/smsgateway` | JDBC URL of PostgreSQL |
| `DB_USERNAME` / `DB_PASSWORD` | `smsgateway` | Database credentials |
| `GATEWAY_ADMIN_USERNAME` | `admin` | Dashboard user |
| `GATEWAY_ADMIN_PASSWORD` | *(none, required)* | Dashboard password; the server does not start without it |
| `GATEWAY_DEFAULT_TTL` | `5m` | Time to live of a message without `ttlSeconds` |
| `GATEWAY_MAX_TTL` | `24h` | Largest accepted `ttlSeconds` |
| `GATEWAY_ACK_TIMEOUT` | `60s` | A phone that does not acknowledge a message within this delay loses it to the queue |
| `GATEWAY_REPORT_TIMEOUT` | `10m` | An acknowledged message with no sent/failed report is marked failed |
| `GATEWAY_MAX_IN_FLIGHT` | `3` | Messages a phone holds at once |
| `GATEWAY_MAX_ATTEMPTS` | `5` | Dispatch attempts before a message fails |
| `GATEWAY_DISPATCH_INTERVAL` | `2s` | Period of the maintenance and dispatch loop |
| `GATEWAY_TIME_ZONE` | `UTC` | Time zone of the dashboard, e.g. `Africa/Kinshasa` |
| `PORT` | `8080` | HTTP port |

## Documentation

- [HTTP API](docs/api.md): endpoints, statuses, errors, idempotency.
- [Device protocol](docs/device-protocol.md): the WebSocket protocol the Android app implements.
- [Architecture](docs/architecture.md): queue, state machine, timeouts and current limits.
- [Contributing](CONTRIBUTING.md): development setup and how to propose changes.

## Development

Requirements: JDK 21 or later. Tests use an in-memory H2 database, no Docker needed.

```bash
./mvnw test                    # run the tests
docker compose up -d postgres  # a local PostgreSQL, then:
GATEWAY_ADMIN_PASSWORD=dev DB_PASSWORD=<from .env> ./mvnw spring-boot:run
```

## Roadmap

1. **Server v1** (this repository): API, queue, WebSocket channel, dashboard. ✅
2. **Android app**: minimal configuration screen, foreground service, sending with `SmsManager`, reports.
3. Routing by operator prefix across several phones; fallback to a commercial SMS provider; webhooks.
4. Several gateway instances (shared session registry), metrics.

## Responsible use

Sending application-to-person SMS through consumer SIM cards may be restricted by your operator's terms
or by your country's telecom regulator. Read them before using this project in production, and only send
messages that recipients expect. You are responsible for how you use this software.

## License

[Apache License 2.0](LICENSE).
