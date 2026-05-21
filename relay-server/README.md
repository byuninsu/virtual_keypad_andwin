# Android/Windows Key Relay Server

Railway deployment target for a WebSocket relay between an Android overlay app and a Windows input client.

## Protocol

First message after connection:

```json
{ "type": "join", "role": "android", "roomId": "room-123" }
```

or

```json
{ "type": "join", "role": "windows", "roomId": "room-123" }
```

Relay messages are forwarded to the peer as-is, with `from` and `roomId` added by the server.

Examples:

```json
{ "type": "key_down", "key": "ArrowUp" }
{ "type": "key_up", "key": "ArrowUp" }
{ "type": "macro", "name": "skill1" }
{ "type": "release_all" }
```

System messages sent by the server:

- `joined`
- `paired`
- `peer_missing`
- `peer_disconnected`
- `error`

When the Android peer disconnects, the server also sends `release_all` to the Windows peer to reduce stuck-key risk.

## Local Run

```bash
npm install
npm start
```

Health check:

- `GET /health`

Railway will provide `PORT` automatically in production.
