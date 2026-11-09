# Architecture

## Components

| Package | Role |
|---|---|
| `api` | Client HTTP API (`/v1/messages`), validation, problem documents. |
| `apikey` | API keys of client applications and their authentication filter. |
| `message` | The queue: `SmsMessage` entity, state machine (`MessageService`), dispatch loop (`MessageDispatcher`). |
| `device` | Phones: tokens, WebSocket handler, live session registry, frames. |
| `dashboard` | Server-rendered administration pages (Thymeleaf). |
| `config` | Settings (`GatewayProperties`), security zones, WebSocket and OpenAPI setup. |

Database schema: [`V1__init.sql`](../src/main/resources/db/migration/V1__init.sql), managed by Flyway.
Hibernate only validates the schema; every change goes through a new migration.

## The queue is a table

`sms_message` is the queue: no message broker is needed for the volumes a few phones can send (a phone
sends at most a few SMS per second). Each status change is also written to `message_event`, which is the audit
trail shown in the dashboard.

```
QUEUED ──claim──▶ DISPATCHED ──ack──▶ ACCEPTED ──sent──▶ SENT ──delivered──▶ DELIVERED
  ▲                   │                   │
  └──── ack timeout ──┘                   ├──failed──────────▶ FAILED
                      │                   └──report timeout──▶ FAILED
                      └──max attempts────────────────────────▶ FAILED
QUEUED / DISPATCHED ──time to live──▶ EXPIRED
```

Rules worth knowing:

- **Oldest first.** `claim` takes the oldest queued messages that have not expired.
- **Ownership.** A message remembers the last device it was given to. Only that device's reports count,
  including late ones after the message went back to the queue: a late `ack` from it wins over a new dispatch.
- **The device is the source of truth for sending.** A `sent` or `delivered` report is accepted from any
  earlier status, even `EXPIRED` or `FAILED`, because the SMS really left. A `failed` report never overrides
  `SENT`.
- **Text erasure.** The body is set to `null` when the message reaches `SENT`, `DELIVERED`, `FAILED` or
  `EXPIRED`. Recipients and statuses are kept for the audit.
- **Optimistic locking.** `@Version` protects against a device report and the maintenance loop changing
  the same message at once; reports retry, maintenance waits for its next run.

## Dispatch

`MessageDispatcher` runs:

- every `GATEWAY_DISPATCH_INTERVAL` (2 s): maintenance (expiry, re-queue, report timeout), then dispatch;
- right away when a message is queued, a device connects, or a device reports `sent`/`failed` (a slot frees up).

A dispatch gives each connected device up to `maxInFlightPerDevice` messages (dispatched + accepted). Only one
dispatch runs at a time; a request made during a run triggers one more run, so no trigger is lost.

When a device reconnects, the messages it had not acknowledged are pushed to it again before new ones.

## Security

- Three Spring Security chains: client API (API key, stateless), device endpoint (token checked in the
  WebSocket handshake), dashboard (form login, CSRF).
- API keys and device tokens are 256-bit random values, shown once, stored as SHA-256 hashes.
- The dashboard administrator is a single user from the environment; the server refuses to start without
  a password.
- Message text is never logged, never returned by the API, never shown in the dashboard.

## Current limits

- **Single instance.** Device sessions live in memory (`DeviceSessionRegistry`), so run one gateway instance.
  Several instances need a shared registry or sticky routing of devices; this is on the roadmap.
- **No routing yet.** Any connected device may send any message. Routing by operator prefix comes next.
- **One administrator.** No user management in the dashboard.
