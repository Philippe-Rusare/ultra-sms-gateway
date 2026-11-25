# Device protocol

How an Android phone talks to the gateway. The Android app implements this; anything else that speaks it
(a test script, another platform) can act as a device.

## Pairing

A phone gets its server address and its device token by scanning a QR code, instead of typing them.

1. In the dashboard, **Devices → Add device with a QR code** creates a one-time code, valid 10 minutes, and
   shows it as a QR code. The QR code is shown once; only the hash of the code is stored.
2. The QR code holds a link: `ultrasms://pair?server=<dashboard address, URL-encoded>&code=usgp_...`.
   The app also opens this link directly, for a phone that cannot scan.
3. The app exchanges the code, without authentication (the code is the credential):

```
POST <server>/v1/device/pair
Content-Type: application/json

{ "code": "usgp_..." }
```

```json
{
  "deviceId": "8b1e…",
  "name": "Vodacom phone 1",
  "token": "usgd_...",
  "deviceUrl": "wss://<gateway>/v1/device/ws"
}
```

4. The app stores `deviceUrl` and `token`, then connects as described below. The token is returned only by
   this call.

A code that is unknown, expired or already used gets `404` with a problem document. The device is created
in the organization of the person who started the pairing.

An edition that limits the phones of an organization (a `DeviceLimit` bean, such as the hosted edition and its
plans) answers `409` with a problem document when the organization already has every phone it may have; the code
stays unused. Past a lower limit (a cheaper plan), the phones paired first keep working and the others are paused:
they stay connected but get no `send` frame.

Right after `hello`, and again each time it changes, the gateway tells the phone whether it is paused:

```json
{"type": "pause", "paused": true, "limit": 1, "active": ["Comptoir"]}
```

`limit` is the number of phones the plan allows (null without a limit) and `active` the names of the phones that
do send (empty when the phone is not paused). The app shows a "paused" screen instead of "online", and comes back
by itself when a `pause` frame says `"paused": false`. A gateway without plans always sends `"paused": false`.

Typing the server URL and a token by hand still works (**Create a token instead**).

## Connection

```
GET wss://<gateway>/v1/device/ws
Authorization: Bearer usgd_...
X-App-Version: 1.0.0
```

- The token comes from the pairing above, or is created by hand in the dashboard and shown once.
- A missing, unknown or revoked token is refused with `401` during the handshake.
- The token is only accepted in the header, never in the URL, where proxies would log it.
- One connection per device: a new connection closes the previous one (close code `4000`).
- Revoking a device closes its connection (close code `4001`); the app must stop reconnecting.
- A phone of a **suspended organization** is refused with `403` during the handshake; one already connected is
  closed with code `4003`. The app keeps retrying with its usual delays: it comes back by itself once the
  organization is reactivated.
- `X-App-Version` is the version of the app (from 1.0.0). When the gateway sets a minimum
  (`GATEWAY_MINIMUM_APP_VERSION`), an older version is refused with `426 Upgrade Required` and the header
  `X-Minimum-App-Version`; a phone already connected that reports an older `appVersion` in its heartbeat gets an
  `update_required` frame, then close code `4002`. In both cases the app must stop reconnecting and ask to update.
  A version is compared number by number (1.10.0 comes after 1.9.2); a phone that sends no version is accepted.

All frames are JSON text frames with a `type` field. Unknown fields must be ignored by both sides, so that
the protocol can grow without breaking older apps.

## Gateway → device

### `hello`

Sent once, right after the connection opens.

```json
{
  "type": "hello",
  "deviceId": "8b1e…",
  "serverTime": "2026-11-09T10:15:00Z",
  "maxInFlight": 3,
  "deviceName": "Vodacom · counter",
  "organization": "Demo Company",
  "plan": null
}
```

`maxInFlight` is the number of messages the gateway may push before the device reports on them.

`deviceName` is the name given to the phone in the dashboard, and `organization` the name of the organization
it belongs to: the app shows them. `plan` is the organization's plan (`free`, `pro`, `business`) on an edition
that sells plans, and `null` on a self-hosted gateway. Older apps ignore these fields.

### `send`

```json
{
  "type": "send",
  "id": "5f0c2b8e-…",
  "to": "+243812345678",
  "body": "Your code is 482913",
  "expiresAt": "2026-11-09T10:20:00Z"
}
```

The device must:

1. Answer `ack` right away (within `GATEWAY_ACK_TIMEOUT`, 60 s by default), otherwise the gateway gives the
   message to another device.
2. Skip it if it already handled this `id` (the gateway may push a message again after a reconnection),
   but still answer `ack` and its last known result.
3. Not send it if `expiresAt` is past; answer `failed` with reason `EXPIRED_ON_DEVICE`.
4. Send it with `SmsManager.sendMultipartTextMessage`, one message at a time, a few seconds apart.
5. Report the result with `sent` or `failed`, then `delivered` when the delivery report arrives.

The device must not log `body`: it often holds a one-time code.

### `error`

```json
{ "type": "error", "code": "INVALID_FRAME", "id": null }
```

`code` is `INVALID_FRAME` (not JSON), `MISSING_ID` or `UNKNOWN_TYPE`.

### `update_required`

```json
{"type": "update_required", "minimumVersion": "1.2.0"}
```

This version of the app is no longer accepted. Sent just before the connection is closed with code `4002`.

## Device → gateway

| Frame | When |
|---|---|
| `{"type":"ack","id":"…"}` | The `send` frame was received and stored. |
| `{"type":"sent","id":"…"}` | Android reported `RESULT_OK` for every part. |
| `{"type":"failed","id":"…","reason":"RESULT_ERROR_NO_SERVICE"}` | Android reported an error, or the message expired on the device. |
| `{"type":"delivered","id":"…"}` | The delivery report arrived for every part. |
| `{"type":"heartbeat",…}` | Every 30 seconds, see below. |

`reason` is a short code: letters, digits, `_ . : -`, at most 200 characters. Use the name of the Android
`SmsManager.RESULT_*` constant when there is one.

Reports for a message assigned to another device are ignored. Reports are idempotent: sending the same one
twice is harmless.

### `heartbeat`

```json
{
  "type": "heartbeat",
  "battery": 76,
  "charging": true,
  "signal": 3,
  "operator": "Vodacom CD",
  "network": "LTE",
  "appVersion": "0.1.0"
}
```

All fields except `type` are optional. `battery` is 0–100, `signal` 0–4 (Android `SignalStrength.getLevel()`).
The values appear in the dashboard.

## Keeping the connection alive

- Send a WebSocket ping every 30 seconds (OkHttp: `pingInterval(30, SECONDS)`) and a `heartbeat` frame
  every 30 seconds.
- On disconnection, reconnect with an exponential backoff (1 s, 2 s, 4 s… up to 60 s), and right away when
  the network comes back (`ConnectivityManager.NetworkCallback`).
- Run the connection in a foreground service, ask to be exempted from battery optimisation, and restart
  after boot.

## Delivery guarantees

The gateway delivers each message to devices **at least once**. Together with the device-side duplicate
check, an SMS is sent **once** in normal operation. A duplicate is still possible in one rare case: a device
sends the SMS, then loses its connection before its `ack` reaches the gateway, and the message is given to
another device after the acknowledgement timeout.
