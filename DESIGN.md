# Design

This is TCP, not HTTP: commands share one connection and `ClientSession` routes them by their `type`. See [APPROACH.md](APPROACH.md) for protocol rules.

## Class map

```text
Main
  └─ RelayServer                 listener, connection limit, shutdown
       ├─ ClientRegistry         retained identities
       │    └─ ClientContext     identity, active session, lock
       │         └─ Mailbox      pending RelayMessage records
       ├─ RelayService           SEND acceptance and ACK removal
       │    └─ uses ClientRegistry; returns SendResult
       └─ ClientSession          one per admitted connection
            ├─ uses ClientRegistry and RelayService
            ├─ FrameCodec        length prefix and UTF-8 limits
            ├─ ProtocolCodec     JSON encoding/decoding
            └─ outbound queue → writer → socket
                                             ↕ TCP
                                         RelayClient
                                  console input + event reader
```

The registry and service are shared by sessions. The CLI runs separately and also uses the codecs. A context survives disconnect; a session does not.

## Command flow

| Command | Route |
| --- | --- |
| `REGISTER` | `handleRegister` → registry attaches identity → queue `REGISTERED` → replay mailbox |
| `SEND` | `handleSend` validates fields/size → service reserves ID and stores message → queue `SEND_RESULT` → queue `DELIVERY` if recipient online |
| `ACK` | `handleAck` → service removes message from registered recipient's mailbox → releases pending ID |

Incoming frames pass through `FrameCodec.readFrame`, then `ProtocolCodec.decodeType` and `ClientSession.handleFrame`. Outgoing events go through the recipient session's queue and sole writer, which JSON-encodes and frames them.

An accepted message stays in its mailbox until ACK, even after delivery. On reconnect, the new session reattaches the same context and replays it.

## Data types

These records/enums carry data; they do not own networking or mailbox behaviour.

| Types | Purpose |
| --- | --- |
| `RelayMessage` | Internal message: ID, sender, recipient and body |
| `SendResult` | Internal acceptance/rejection result |
| `RegisterCommand`, `SendCommand`, `AckCommand` | Incoming protocol commands |
| `RegisteredEvent`, `SendResultEvent`, `DeliveryEvent`, `ErrorEvent` | Outgoing protocol events |
| `MessageType` | Command/event discriminator |
| `ErrorCode` | Protocol error category |
| `ClientRegistry.RegistrationResult` | Registered, identity already active, or identity capacity reached |

## Where to look

Production code is under `src/main/java/com/messagerelay`: `server` handles connections/state, `service` handles SEND/ACK, `protocol` handles wire data, and `client` contains the CLI.

Tests mirror those areas under `src/test/java/com/messagerelay`. `TestUtils` provides socket/state helpers, `JavaProcess` runs bounded child JVM tests, and `ShutdownServerProcess` triggers normal JVM exit for shutdown-hook testing. The server test's `ClientConnections` helper owns its test sockets.

Build/run commands are in [README.md](README.md); locking, delivery semantics and limitations are in [APPROACH.md](APPROACH.md).
