# HTTP API

Base path: `/v1`. Interactive documentation: `/swagger-ui.html`, OpenAPI document: `/v3/api-docs`.

## Authentication

Every request carries the API key of the client application, created in the dashboard (**API keys**):

```
X-Api-Key: usg_...
```

`Authorization: Bearer usg_...` is accepted too. A missing, unknown or revoked key gives `401`.

## Queue a message

`POST /v1/messages`

```json
{
  "to": "+243812345678",
  "body": "Your code is 482913",
  "ttlSeconds": 300,
  "clientReference": "otp-42"
}
```

| Field | Required | Rules |
|---|---|---|
| `to` | yes | E.164: `+`, country code, number, 8 to 15 digits. No spaces. |
| `body` | yes | 1 to 918 characters. Long texts are sent as several SMS parts by the phone. |
| `ttlSeconds` | no | 30 to `GATEWAY_MAX_TTL` (default 24 h). Default: `GATEWAY_DEFAULT_TTL` (5 min). |
| `clientReference` | no | Up to 100 characters, unique per API key. |

Responses:

- `202 Accepted`: queued. `Location` points to the message.
- `200 OK`: a message with this `clientReference` already exists for this key; it is returned unchanged
  and nothing new is queued. Retry a timed-out request with the same reference without fear of a double SMS.
- `400 Bad Request`: invalid field, see `errors`.
- `401 Unauthorized`: missing or invalid key.

```json
{
  "id": "5f0c2b8e-3f43-4d5e-9a57-0c1f7b0b2a11",
  "to": "+243812345678",
  "status": "QUEUED",
  "clientReference": "otp-42",
  "attempts": 0,
  "failureReason": null,
  "createdAt": "2026-11-09T10:15:00Z",
  "expiresAt": "2026-11-09T10:20:00Z",
  "sentAt": null,
  "deliveredAt": null
}
```

The text of the message is never returned.

## Get a message

`GET /v1/messages/{id}` returns the same document. A message queued with another API key gives `404`.

## Statuses

| Status | Meaning | Final |
|---|---|---|
| `QUEUED` | Waiting for a connected phone. | no |
| `DISPATCHED` | Pushed to a phone, waiting for its acknowledgement. | no |
| `ACCEPTED` | The phone acknowledged it and is sending it. | no |
| `SENT` | The phone handed it to the mobile network. | no (`DELIVERED` may follow) |
| `DELIVERED` | The network confirmed delivery to the recipient. | yes |
| `FAILED` | See `failureReason`. | yes |
| `EXPIRED` | Its time to live ran out before a phone accepted it. | yes |

Not every operator sends delivery reports: `SENT` may be the last status you get.

`failureReason` values:

| Value | Meaning |
|---|---|
| `RESULT_ERROR_*` | Error reported by Android, e.g. `RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_GENERIC_FAILURE`. |
| `MAX_ATTEMPTS_REACHED` | Phones were given the message `GATEWAY_MAX_ATTEMPTS` times without acknowledging it. |
| `REPORT_TIMEOUT` | A phone acknowledged it but never said whether it was sent. |

## Webhooks

Instead of asking for the status of a message, your application can be told: add an endpoint in the dashboard
(**Webhooks**), choose its events, and the gateway sends a `POST` to it at each status change.

| Event | Sent when the message becomes |
|---|---|
| `message.sent` | `SENT` |
| `message.delivered` | `DELIVERED` |
| `message.failed` | `FAILED` |
| `message.expired` | `EXPIRED` |
| `webhook.test` | (never: sent from the dashboard to check an endpoint) |

```http
POST /your/webhook HTTP/1.1
Content-Type: application/json
Usg-Event: message.delivered
Usg-Delivery: 1246d23a-7621-48ea-9421-f895ca089bbe
Usg-Signature: t=1731600000,v1=5f3c9a…

{
  "id": "1246d23a-7621-48ea-9421-f895ca089bbe",
  "type": "message.delivered",
  "createdAt": "2026-11-14T10:34:20Z",
  "data": {
    "messageId": "0f81ef6c-ee45-4176-a50b-f8753e9d3f37",
    "clientReference": "order-42",
    "to": "+243812345678",
    "status": "DELIVERED",
    "attempts": 1,
    "failureReason": null,
    "createdAt": "2026-11-14T10:33:56Z",
    "sentAt": "2026-11-14T10:34:00Z",
    "deliveredAt": "2026-11-14T10:34:20Z"
  }
}
```

The message text is never sent. `id` is the same on every attempt of one request: use it to ignore a request you
already handled.

**Check the signature.** Each endpoint has a secret (`whsec_…`), shown once when it is created or renewed.
`Usg-Signature` holds `t`, the time of sending in Unix seconds, and `v1`, the hexadecimal HMAC-SHA256 of
`<t>.<raw body>` with the secret. Recompute it on the raw body, compare in constant time, and refuse a request whose
`t` is more than 5 minutes away from your clock.

```python
import hashlib, hmac, time

def verify(secret: str, header: str, body: bytes) -> bool:
    parts = dict(item.split("=", 1) for item in header.split(","))
    expected = hmac.new(secret.encode(), parts["t"].encode() + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, parts["v1"]) and abs(time.time() - int(parts["t"])) < 300
```

**Answer quickly.** Answer `2xx` within 10 seconds (`GATEWAY_WEBHOOK_TIMEOUT`), then do the work. Any other answer,
a timeout or a redirect counts as a failure: the request is sent again after 30 s, 2 min, 10 min, 30 min, 1 h, 3 h,
then every 6 h, up to 8 attempts (`GATEWAY_WEBHOOK_MAX_ATTEMPTS`). Requests may arrive out of order: rely on the
`status` and the times, not on the order. The dashboard shows each request, its answer, and can send it again.

**Addresses.** A self-hosted gateway may call addresses of its own network. With
`GATEWAY_WEBHOOK_ALLOW_PRIVATE_NETWORKS=false` it refuses `localhost`, `10.x`, `172.16–31.x`, `192.168.x`,
link-local and similar addresses, checked again before every request; `GATEWAY_WEBHOOK_REQUIRE_HTTPS=true` requires
`https://`. A public service sets both.

## Errors

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem documents
(`Content-Type: application/problem+json`):

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "The request is not valid.",
  "errors": { "to": "must be an E.164 number, e.g. +243812345678" }
}
```

## Suspended organization

While an organization is suspended by the operator of the gateway, every call with one of its keys answers `403`
with a problem document (`"detail": "This organization is suspended."`). Nothing is deleted: the keys work again
once the organization is reactivated.
