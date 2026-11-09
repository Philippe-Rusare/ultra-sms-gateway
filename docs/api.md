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
