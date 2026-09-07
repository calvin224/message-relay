# Approach

## Scope

The goal of this implementation is a small, explainable message relay that owns its registration, mailbox, delivery, acknowledgement, reconnect, and failure behaviour.

The final implementation uses:

- Java 25,
- standard TCP sockets,
- a length-prefixed JSON protocol,
- Java virtual threads,
- bounded in-memory mailboxes,
- bounded per-client outbound queues,
- explicit acknowledgements,
- focused unit tests,
- real socket integration tests.

Jackson is used for JSON serialization only.

No message broker, database, messaging framework, or cloud messaging service owns the relay behaviour.

The required core scenarios were prioritised first. Docker was implemented as an optional bonus.

Strict FIFO and durable persistence were intentionally not included in the final implementation because both were optional bonuses and would add significantly more sequencing, recovery, and failure-state complexity.

---

# Acceptance Criteria

## Core Scenario

| # | Requirement | Status | Main Classes | Evidence |
| --- | --- | --- | --- | --- |
| 1 | A client registers with a unique name/ID and multiple clients may register simultaneously | ✅ | `ClientSession`, `ClientRegistry`, `ClientContext` | Socket registration tests |
| 2 | Either client can send a uniquely identified message to the other | ✅ | `SendCommand`, `RelayMessage`, `ClientSession`, `RelayService` | Service and socket SEND tests |
| 3 | The service confirms whether it accepted or rejected the SEND | ✅ | `SendResult`, `SendResultEvent`, `RelayService` | Accepted/rejected SEND tests |
| 4 | The recipient receives the message and explicitly ACKs it | ✅ | `DeliveryEvent`, `AckCommand`, `ClientSession`, `RelayService` | Delivery + ACK integration test |
| 5 | Messages sent to an offline recipient are retained within resource limits | ✅ | `ClientContext`, `Mailbox`, `RelayService` | Offline-delivery and mailbox-limit tests |
| 6 | The same identity can reconnect and receive offline messages | ✅ | `ClientRegistry`, `ClientContext`, `ClientSession.deliverPendingMessages()` | Reconnect integration tests |
| 7 | Delivered-but-unacknowledged messages remain available after disconnect | ✅ | `Mailbox`, `RelayService`, reconnect replay | Unacknowledged-redelivery integration test |

---

# Behaviour Requirements

| Requirement | Implementation |
| --- | --- |
| Re-register identity without losing queued messages | Disconnect clears the active session but keeps the logical `ClientContext` and mailbox. |
| Correct-recipient ACK only | ACKs operate against the mailbox of the identity registered on that TCP connection. |
| At-least-once delivery | Messages remain pending until ACK and may therefore be delivered more than once. |
| Bounded mailbox | 100 pending messages per logical identity. |
| Bounded message size | 65,536-byte UTF-8 frame payload limit. |
| Bounded connections | Maximum 100 active connections. |
| Bounded buffers | Maximum 128 outbound events per client session. |
| Invalid input | Explicit protocol errors where possible. |
| Resource limit reporting | Defined SEND rejection/error behaviour for mailbox, frame, and connection limits. |
| Duplicate SEND | Pending message IDs are globally unique; duplicates are rejected. |
| Repeated ACK | Harmless no-op after a message has already been removed. |
| Stale ACK | Behaviour is documented; clients should avoid ID reuse. |
| Ordering | Sequential mailbox insertion order is preserved; strict concurrent FIFO is not guaranteed. |
| Slow clients | Dedicated writer + bounded outbound queue isolates normal slow writes from unrelated clients. |
| Malformed clients | Errors are handled per connection. |
| Concurrent operations | Concurrent collections and recipient-scoped locks protect shared state. |
| Predictable shutdown | Active sockets and listener are closed; executor shutdown is bounded; JVM shutdown hook invokes `stop()`. |

---

# Architecture

```text
                     +-------------------+
                     |    RelayClient    |
                     | interactive CLI   |
                     +---------+---------+
                               |
                               | TCP
                               |
                     +---------v---------+
                     |    RelayServer    |
                     | listener / limits |
                     | lifecycle         |
                     +---------+---------+
                               |
                        creates session
                               |
                     +---------v---------+
                     |   ClientSession   |
                     | protocol dispatch |
                     | reader / writer   |
                     +----+---------+----+
                          |         |
                          |         |
              +-----------v--+   +--v---------------+
              | FrameCodec / |   |   RelayService   |
              |ProtocolCodec |   | message rules    |
              +--------------+   +--------+----------+
                                         |
                               +---------v---------+
                               |  ClientRegistry   |
                               | logical clients   |
                               +---------+---------+
                                         |
                               +---------v---------+
                               |  ClientContext    |
                               | session / lock /  |
                               | mailbox           |
                               +---------+---------+
                                         |
                               +---------v---------+
                               |     Mailbox       |
                               | pending messages  |
                               +-------------------+
```

The most important architectural distinction is:

```text
logical client != TCP connection
```

A connection may disappear.

The logical client and its mailbox remain for the lifetime of the relay process.

That allows disconnect/reconnect semantics without trying to preserve a dead socket.

---

# Class Responsibilities

| Class | Responsibility |
| --- | --- |
| `Main` | Creates the relay, installs the shutdown hook, and starts the application. |
| `RelayServer` | Owns the listening socket, connection permits, active sockets, virtual-thread executor, and shutdown lifecycle. |
| `ClientSession` | Owns one TCP connection, reads frames, dispatches commands, tracks registration, and queues outbound events. |
| `ClientRegistry` | Maps logical client IDs to retained `ClientContext` instances. |
| `ClientContext` | Holds identity, active session, mailbox, and recipient-scoped lock. |
| `Mailbox` | Stores bounded pending messages in insertion order. |
| `RelayService` | Owns SEND acceptance, duplicate-ID handling, mailbox storage, and ACK removal. |
| `RelayMessage` | Internal representation of a pending message. |
| `SendResult` | Transport-independent SEND result. |
| `FrameCodec` | Implements TCP framing and frame-size validation. |
| `ProtocolCodec` | Maps JSON to/from command/event records. |
| `RegisterCommand` | Registration request. |
| `SendCommand` | Addressed SEND request. |
| `AckCommand` | Recipient acknowledgement. |
| `RegisteredEvent` | Registration confirmation. |
| `SendResultEvent` | SEND acceptance/rejection response. |
| `DeliveryEvent` | Recipient message delivery. |
| `ErrorEvent` | Protocol/resource error. |
| `RelayClient` | Interactive CLI used to demonstrate the protocol. |

---

# Why TCP?

I considered an HTTP/REST design because registration, SEND, and ACK naturally map to API operations.

For example:

```text
POST /clients
POST /messages
POST /messages/{id}/ack
```

However, the exercise also puts significant emphasis on:

- persistent connections,
- server-to-client delivery,
- disconnect/reconnect behaviour,
- connection lifecycle,
- framing,
- explicit ACKs,
- slow clients,
- bounded buffers.

Pure REST request/response does not naturally provide a persistent server-to-client delivery channel.

A REST-based production design would likely need an additional mechanism such as:

```text
REST
  -> commands

WebSocket / SSE / long polling
  -> delivery
```

That is a valid design, but it introduces multiple communication models.

For this exercise I chose raw TCP so the relevant behaviour remains explicit:

```text
persistent bidirectional TCP connection
            +
application framing
            +
REGISTER / SEND / DELIVERY / ACK protocol
```

The business logic remains separated behind `RelayService`, so another transport adapter could be introduced later without putting HTTP-specific logic into the relay domain.

TCP is therefore not claimed to be universally better than REST; it was selected because it directly exposes the connection and messaging behaviours emphasised by this exercise.

---

# Protocol Model

## TCP Framing

TCP is a byte stream and does not preserve message boundaries.

Each protocol message is therefore:

```text
4-byte signed big-endian integer
        +
N bytes UTF-8 JSON
```

The integer contains the payload size.

Maximum JSON payload:

```text
65,536 bytes
```

The four-byte prefix is not included in this limit.

Invalid sizes or incomplete frames close the affected connection.

---

# Protocol Messages

## REGISTER

```json
{
  "type": "REGISTER",
  "clientId": "bob"
}
```

Response:

```json
{
  "type": "REGISTERED",
  "clientId": "bob"
}
```

Only one active connection may own a given identity.

---

## SEND

```json
{
  "type": "SEND",
  "messageId": "msg-1",
  "recipientId": "bob",
  "body": "hello bob"
}
```

The sender ID is taken from the registered connection rather than supplied by the client.

Accepted response:

```json
{
  "type": "SEND_RESULT",
  "messageId": "msg-1",
  "accepted": true,
  "reason": null
}
```

Rejected example:

```json
{
  "type": "SEND_RESULT",
  "messageId": "msg-1",
  "accepted": false,
  "reason": "Duplicate message ID"
}
```

---

## DELIVERY

```json
{
  "type": "DELIVERY",
  "messageId": "msg-1",
  "senderId": "alice",
  "body": "hello bob"
}
```

Receiving a DELIVERY does not remove the message.

---

## ACK

```json
{
  "type": "ACK",
  "messageId": "msg-1"
}
```

The acknowledging identity is derived from the registered connection.

The protocol does not send a separate ACK-success response.

---

## ERROR

Example:

```json
{
  "type": "ERROR",
  "code": "INVALID_MESSAGE",
  "message": "messageId is required"
}
```

Errors cover malformed input, invalid command types, required-field validation, registration conflicts, and connection limits.

---

# Connection Lifecycle

## Registration

```text
TCP connect
    |
REGISTER bob
    |
REGISTERED bob
    |
normal messaging
```

---

## Disconnect

When Bob's socket closes:

```text
ClientSession disconnected
        |
active session cleared
        |
ClientContext("bob") remains
        |
Mailbox remains
```

This is why the logical identity survives the TCP connection.

---

## Offline SEND

Alice can then send to Bob:

```text
Alice SEND
    |
RelayService
    |
Bob Mailbox
```

Because Bob has no active session, no live DELIVERY is attempted.

---

## Reconnect

Bob reconnects:

```text
new TCP connection
        |
REGISTER bob
        |
existing ClientContext reused
        |
REGISTERED
        |
pending mailbox replayed
```

The same mechanism also replays messages that were previously delivered but not acknowledged.

State is retained only while the relay process itself remains running.

---

# Delivery Semantics

The relay provides:

```text
at-least-once delivery
within a running relay process
```

SEND acceptance means:

```text
the relay successfully stored the message
in the recipient's bounded in-memory mailbox
```

It does not mean the recipient has received or processed it.

The lifecycle is:

```text
SEND
  |
store message
  |
SEND_RESULT ACCEPTED
  |
DELIVERY if recipient online
  |
wait for ACK
  |
remove message
```

If the recipient disconnects after DELIVERY but before ACK:

```text
message remains pending
        |
recipient reconnects
        |
message delivered again
```

Duplicate deliveries are therefore possible by design.

---

# ACK Semantics

Only the intended recipient can remove its message.

For:

```text
Alice -> Bob : msg-1
```

Bob can ACK `msg-1`.

Another client's ACK cannot remove the message because ACK processing uses that client's own registered mailbox.

Repeated ACKs after removal are harmless.

Unknown ACK IDs are ignored.

Message IDs may be reused after ACK. This introduces a documented stale-ACK limitation: a sufficiently delayed ACK combined with reuse of an old ID could refer to a newer message.

Clients should therefore use globally unique message IDs.

---

# Duplicate SEND Behaviour

Pending message IDs are stored in a concurrent set.

If `msg-1` is already pending:

```text
SEND msg-1
```

is rejected.

When the original message is successfully ACKed, its pending-ID reservation is released.

This gives the system one unambiguous pending message for each message ID.

---

# Ordering

`Mailbox` stores messages using an insertion-ordered deque.

Sequential messages are therefore stored and replayed in insertion order.

Strict FIFO is **not guaranteed** when multiple clients send concurrently.

Concurrent sends compete for the recipient lock, and online delivery occurs separately from mailbox insertion.

Reconnect replay and newly arriving online messages may also interleave.

FIFO was an optional bonus in the exercise, so the final implementation documents this behaviour rather than adding additional sequencing infrastructure.

---

# State Model

Each logical client owns:

```text
ClientContext
    |
    +-- clientId
    +-- active ClientSession or null
    +-- Mailbox
    +-- ReentrantLock
```

A message remains in the mailbox while it is:

```text
accepted
    |
delivered
    |
possibly redelivered
```

It leaves the mailbox only after ACK.

There is no separate `in-flight` collection because delivered-but-unacknowledged messages are still pending.

---

# Concurrency Model

## Connections

`RelayServer` uses a virtual-thread-per-task executor.

Each admitted socket has an independent `ClientSession`.

This keeps blocking socket code simple while avoiding a platform thread for every blocked connection.

---

## Reads

The session reads framed commands from its own connection.

A malformed or blocked reader affects that connection rather than the server's other sessions.

---

## Writes

Each `ClientSession` has:

```text
bounded outbound queue
        +
dedicated writer virtual thread
```

Only that writer writes normal protocol events to the session's socket.

This prevents concurrent producers from interleaving bytes on the same TCP stream.

It also prevents normal slow network writes from occurring directly inside unrelated message-processing paths.

---

## Shared State

The implementation uses:

- `ConcurrentHashMap` for logical clients,
- a concurrent set for pending message IDs,
- one `ReentrantLock` per logical client.

The per-client lock protects:

- active-session changes,
- mailbox changes,
- recipient-specific operations.

This avoids one global lock serialising all clients.

---

# Resource Bounds

| Resource | Limit | Exceeded Behaviour |
| --- | ---: | --- |
| Frame payload | 65,536 bytes | Invalid connection frame closes socket; oversized resulting DELIVERY rejects SEND |
| Pending mailbox | 100 messages | SEND rejected |
| Active connections | 100 | Best-effort error then excess connection closes |
| Outbound queue | 128 events | Affected client socket closes |
| Shutdown wait | 2 seconds | Remaining executor work is interrupted |

The values are constants to keep the exercise small and reproducible.

A production implementation would likely make them configurable.

---

# DELIVERY Frame Validation

SEND and DELIVERY are not identical protocol messages.

SEND contains:

```text
messageId
recipientId
body
```

DELIVERY contains:

```text
messageId
senderId
body
```

It is therefore possible for an inbound SEND to fit within the frame limit while its resulting DELIVERY exceeds the limit.

Reasons include:

- sender ID size,
- UTF-8 multibyte characters,
- JSON escaping.

Before accepting a registered SEND, the relay serializes the eventual DELIVERY and validates its final UTF-8 size.

If it is too large:

```text
SEND_RESULT
accepted = false
reason = Delivery frame exceeds maximum size
```

The message is not stored and the message ID is not reserved.

Tests cover boundary behaviour including UTF-8 and JSON escaping.

---

# Slow, Malformed, and Disconnected Clients

Malformed JSON is handled inside the associated `ClientSession`.

Where safe, an error response is returned and the connection remains usable.

Malformed input does not terminate the relay or unrelated client sessions.

Slow readers are isolated using:

```text
bounded queue
    +
dedicated writer
```

If a client's outbound queue becomes full, that connection is closed rather than allowing memory use to grow without bound.

One known limitation is that runtime sockets do not currently have registration/read/write/partial-frame deadlines.

An idle client can therefore occupy one of the bounded connection slots indefinitely.

---

# Shutdown

`RelayServer.stop()`:

1. stops the accept loop,
2. closes the listening socket,
3. closes active client sockets,
4. allows client tasks to terminate,
5. bounds executor shutdown,
6. interrupts remaining work if required.

`Main` installs a JVM shutdown hook.

The server also accepts an optional port argument, defaulting to `9000`. Tests use port `0` and read the actual bound port from startup output. The client accepts `<clientId> [host] [port]`, retaining `localhost:9000` as its default destination. This allows isolated entry-point tests without depending on a free fixed port.

Therefore both:

```text
Ctrl+C
Docker SIGTERM
```

invoke the controlled shutdown path.

This behaviour was manually verified through Docker Compose.

---

# Testing Strategy

The test suite is intentionally focused on behavioural requirements and important boundaries.

## Unit Tests

### `FrameCodecTest`

Covers:

- framing round trip,
- UTF-8,
- invalid frame lengths,
- incomplete input,
- oversized output.

### `ProtocolCodecTest`

Covers protocol JSON encoding/decoding and message-type behaviour.

### `RelayServiceTest`

Covers:

- accepted SEND,
- duplicate pending message ID,
- mailbox full,
- wrong-recipient ACK,
- repeated ACK.

---

## Integration Tests

### `ClientSessionIntegrationTest`

Uses real local sockets to cover:

- registration,
- SEND / SEND_RESULT,
- DELIVERY,
- ACK,
- offline retention,
- reconnect,
- delivered-but-unacknowledged redelivery,
- malformed JSON,
- required-field validation.

### `DeliveryFrameSizeIntegrationTest`

Covers:

- exact frame-size boundary,
- one byte over the boundary,
- UTF-8,
- JSON escaping,
- rejection without retaining mailbox/message-ID state.

### `RelayServerIntegrationTest`

Covers server-level behaviour including:

- connection admission limits,
- active clients,
- controlled shutdown.

Tests use bounded test-side timeouts so failures terminate deterministically.

### `RelayClientIntegrationTest`

Launches the real `RelayClient.main` in a child JVM against a local socket peer. Tests cover usage without an identity, help and invalid commands, registration, SEND serialization and result display, DELIVERY display, explicit rather than automatic ACK, quit, console EOF, server EOF, and malformed server frames. Bare `send` and `ack` commands now display their specific usage instructions.

### `MainIntegrationTest`

Launches `Main.main` in a child JVM, registers a real socket client, then requests normal JVM exit through a test-only stdin control thread. The test verifies the shutdown-hook output, active-client disconnection, and successful process exit. This exercises JVM shutdown portably without terminating Maven or relying on platform-specific signals. It does not replace Docker/SIGTERM testing or exercise the rare `IOException` logging path in `Main.shutdown`.

The test process helper forwards the active JaCoCo agent to child JVMs and waits for them to exit, allowing coverage to be collected in the existing report. These tests have 15-second JUnit limits, five-second socket/output/exit waits, and bounded cleanup for failed processes. Coverage measures actual entry-point execution; no production classes are excluded to improve the percentage.

---

# Build and Artifact Model

The local path is:

```text
source
  |
Maven Wrapper
  |
compile
  |
tests
  |
JaCoCo
  |
Maven Shade
  |
executable JAR
```

Runnable artifact:

```text
target/message-relay-1.0.0-SNAPSHOT.jar
```

GitHub Actions separates:

```text
Build
Unit Tests
Integration Tests
SonarQube Analysis
Package
```

The package job uploads the executable JAR.

---

# Docker

The Docker bonus is implemented using a multi-stage image:

```text
Java 25 JDK
    |
Maven build
    |
executable shaded JAR
    |
Java 25 JRE
```

The simplest reproducible startup is:

```sh
docker compose up --build
```

The container exposes port `9000`.

No additional infrastructure is required.

---

# Trade-offs

## In-memory state

The exercise allows in-memory state.

This keeps the implementation focused on:

- logical identity,
- connection lifecycle,
- ACK semantics,
- reconnect,
- resource bounds,
- concurrency.

The trade-off is that queued messages disappear if the entire relay process restarts.

Durable restart recovery was optional and was deliberately left out of the final implementation.

---

## No strict FIFO

Sequential mailbox insertion order is maintained, but strict ordering across concurrent sends is not guaranteed.

FIFO was optional.

The final solution therefore documents the semantics instead of introducing additional sequencing and dispatch complexity.

---

## Fixed configuration

Resource limits and port values are constants.

This keeps the exercise easy to reproduce and inspect.

A production system would externalise these settings.

---

## No authentication/TLS

Authentication and encryption are outside the scope of this exercise.

An offline identity can therefore be claimed by another connection that knows its ID.

---

# Known Limitations

- In-memory messages and identities are lost on process restart.
- Durable persistence/recovery is not implemented.
- Strict FIFO under concurrent sends is not guaranteed.
- Retained logical identities have no global expiry or cap.
- Production sockets have no read/write/partial-frame deadlines.
- Idle connections can occupy connection capacity indefinitely.
- IDs may be reused after ACK, allowing a theoretical stale-ACK ambiguity.
- There is no automatic client reconnect/backoff.
- There is no periodic retry or dead-letter mechanism.
- There is no authentication or TLS.
- There is no protocol version negotiation.
- The implementation targets one relay process rather than distributed replicas.

These limitations are stated explicitly rather than hidden behind stronger delivery claims.

---

# Potential Next Steps

If this were extended beyond the exercise:

1. cap total retained identities and queued bytes,
2. introduce configurable resource limits,
3. add socket registration/read/write deadlines,
4. add stronger concurrency and stress tests,
5. add a completed-ID/deduplication retention strategy,
6. add strict FIFO sequencing if required,
7. add durable storage and restart recovery if required,
8. add authentication and TLS,
9. add structured logging and metrics,
10. consider a REST command API with WebSocket delivery if that better matched product requirements.

---

# AI Tool Usage

I used ChatGPT during the exercise as a development assistant.

I primarily used it to write some automated tests where I supplied explicit behaviours and expected outcomes, which helped save time while still allowing me to review and run the tests myself.

I also consulted ChatGPT throughout the process for feedback on design decisions and trade-offs, including protocol structure, connection lifecycle, concurrency, resource limits, and alternative approaches.

The final design choices, implementation, verification, and ability to explain or modify the code remain my responsibility.
