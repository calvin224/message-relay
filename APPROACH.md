# Approach

## Scope and choices

The aim was a small relay that can be explained and changed, not a production messaging platform.

The acceptance criteria are the seven core scenarios: multiple registered clients; addressed, uniquely identified sends; acceptance/rejection; delivery and explicit recipient ACK; offline retention; reconnect with the same identity; and redelivery after disconnect without ACK.

Java 25 provides virtual threads for straightforward blocking socket code. TCP gives a bidirectional connection for commands and deliveries. Length-prefixed JSON keeps the wire format inspectable, with Jackson handling serialization. No messaging library owns relay behaviour.

State is kept in memory. Docker is included, but persistence and strict concurrent FIFO were optional and remain unimplemented.

## Protocol

There are no HTTP routes. Each TCP frame is a four-byte signed big-endian payload length followed by UTF-8 JSON. Valid incoming lengths are 1–65,536 bytes. The `type` field selects the handler.

| Direction | Type | Other fields |
| --- | --- | --- |
| Client → server | `REGISTER` | `clientId` |
| Client → server | `SEND` | `messageId`, `recipientId`, `body` |
| Client → server | `ACK` | `messageId` |
| Server → client | `REGISTERED` | `clientId` |
| Server → sender | `SEND_RESULT` | `messageId`, `accepted`, `reason` |
| Server → recipient | `DELIVERY` | `messageId`, `senderId`, `body` |
| Server → client | `ERROR` | `code`, `message` |

For example, these are JSON payloads; each needs its own length prefix on the wire:

```json
{"type":"SEND","messageId":"msg-1","recipientId":"bob","body":"hello"}
{"type":"SEND_RESULT","messageId":"msg-1","accepted":true,"reason":null}
{"type":"DELIVERY","messageId":"msg-1","senderId":"alice","body":"hello"}
{"type":"ACK","messageId":"msg-1"}
```

IDs must be nonblank and the body must be present; an empty body is allowed. The sender and acknowledging recipient are taken from the registered connection, not trusted client-supplied identity fields. The final encoded DELIVERY size is checked before accepting a SEND.

## Connection and message lifecycle

- A client connects and sends `REGISTER`. Only one active connection may own an identity. Registering twice on one connection is rejected.
- A successful registration attaches the existing or new mailbox, queues `REGISTERED`, then replays pending messages.
- SEND acceptance means stored in the recipient's in-memory mailbox, not received or durably saved. The recipient must have registered at least once; an unknown recipient is rejected.
- Online recipients receive a queued DELIVERY. Offline messages stay in the mailbox.
- Disconnect clears the matching active session, not the identity or mailbox. Reconnect uses a new socket and the same ID. The CLI reconnects manually.
- Only the recipient's ACK removes a pending message and releases its ID. There is no ACK-success response.

Delivery is at least once within the running process: duplicates are possible, including when reconnect overlaps a send. Unacknowledged messages replay on reconnect, not on a timer.

Pending message IDs are globally unique; duplicate SENDs are rejected rather than treated as successful retries. IDs can be reused after ACK. Unknown, repeated and wrong-recipient ACKs are no-ops; an ACK before registration is ignored. A delayed stale ACK can remove a newer message if its ID has been reused, so clients should use fresh IDs.

Mailboxes preserve insertion order. Strict FIFO across concurrent sends and reconnects is not guaranteed because storage and online delivery are separate operations. Independent writers also mean the recipient can observe DELIVERY before the sender observes SEND_RESULT.

## State and concurrency

The class map is in [DESIGN.md](DESIGN.md).

- `ClientRegistry` keeps a concurrent map of identities. Synchronized registration makes the 100-identity capacity check and insertion atomic. Existing IDs are looked up first, so reconnect works at capacity.
- Each `ClientContext` has a lock protecting its active session and mailbox. Registration takes the registry monitor then this lock; other state operations take only the client lock.
- `RelayService` uses a concurrent set to reserve pending message IDs. A failed mailbox insertion releases its reservation.
- Each admitted socket has a virtual reader/session task and a separate virtual writer. Other sessions enqueue events rather than writing directly to that socket.
- Queue offers do not wait. A full outbound queue closes that connection, leaving pending mailbox entries for reconnect.

The limits are listed in [README.md](README.md#limits-and-shutdown). Together, 100 identities and 100 messages per mailbox cap retained messages at 10,000, but this is not an exact JVM heap budget.

Identities are never evicted: abandoned IDs keep their slots until restart. This avoids discarding accepted messages to make room for new clients.

## Errors and resource limits

| Condition | Result |
| --- | --- |
| Missing/blank required fields | `ERROR / INVALID_MESSAGE` |
| Malformed JSON or command shape | `ERROR / MALFORMED_MESSAGE` |
| Missing, unknown or unsupported type | `ERROR / INVALID_MESSAGE_TYPE` |
| Identity already online | `ERROR / IDENTITY_IN_USE` |
| Register twice on one connection | `ERROR / ALREADY_REGISTERED` |
| New ID at identity capacity | `ERROR / IDENTITY_LIMIT_REACHED`; socket stays unregistered and can try an existing offline ID |
| SEND before registration, unknown recipient, duplicate pending ID, full mailbox or oversized DELIVERY | Rejected `SEND_RESULT` with a reason |
| Active connection limit reached | Close without a protocol response; client sees EOF/reset |
| Invalid frame length, truncated stream, socket failure or full outbound queue | Close affected connection |

Recoverable JSON/command errors leave the connection usable unless its output queue fills. A partial frame on a still-open socket can wait indefinitely because there is no read deadline.

Excess connections are closed without writing an error on the accept thread. This avoids a rejection write blocking admission, but the client cannot distinguish capacity rejection from another disconnect.

## Shutdown and build

The JVM shutdown hook calls `RelayServer.stop()`, closing the listener and active sockets. As `start()` unwinds, it shuts down the executor, waits up to two seconds and interrupts remaining tasks. State is lost when the process exits.

The Maven Wrapper runs compilation and tests; JaCoCo records coverage and Shade builds the executable JAR. CI uploads that JAR. Exact commands, ports and Docker lifecycle controls are in [README.md](README.md).

## Tests and boundaries

| Tests | Main coverage |
| --- | --- |
| Protocol and service unit tests | Framing, UTF-8, JSON, invalid sizes, SEND results, duplicate IDs, mailbox capacity and ACK ownership |
| Registry unit tests | Identity cap, active conflicts, retained mailbox, stale disconnect and concurrent admission to the final slot |
| Session/server socket tests | Registration, delivery, ACK, offline/reconnect/redelivery, malformed input, DELIVERY size, capacity rejection and shutdown |
| CLI/server process tests | Actual entry points, console commands, event display, EOF/errors and the JVM shutdown hook |

Tests use real local sockets with temporary ports and bounded waits. Awaitility checks state changes; latches coordinate the registration race. Child JVMs inherit JaCoCo so entry-point execution contributes to coverage. No external service is required.

These are focused regression tests, not exhaustive concurrency, load, slow-client or heap-usage tests. The shutdown-hook test does not replace checking Docker/SIGTERM behaviour.

## Limitations and next steps

The main remaining limitations are:
- No persistence, authentication, TLS or multiple server replicas.
- No runtime registration/read/write deadlines; idle clients can occupy all connection slots.
- No identity expiry or separate global queued-byte budget.
- No automatic reconnect, periodic retry or strict concurrent FIFO.
- ID reuse makes sufficiently delayed stale ACKs ambiguous.

If more work were needed, the next steps would be a tighter byte budget, runtime deadlines with slow-client tests, and stronger stale-ACK/deduplication handling. Persistence and FIFO should only be added if required, rather than expanding the exercise further.

# AI Tool Usage

I used ChatGPT during the exercise as a development assistant.

I primarily used it to write some automated tests where I supplied explicit behaviours and expected outcomes, which helped save time while still allowing me to review and run the tests myself.

I also consulted ChatGPT throughout the process for feedback on design decisions and trade-offs, including protocol structure, connection lifecycle, concurrency, resource limits, and alternative approaches.

The final design choices, implementation, verification, and ability to explain or modify the code remain my responsibility.
