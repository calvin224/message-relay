# Approach

## Design scope

The objective is a small, explainable relay that owns its protocol and message state. The design uses standard TCP sockets, explicit acknowledgements, and in-memory mailboxes to make delivery and failure behaviour visible in the application code.

The server implements the core registration, send, delivery, ACK, offline retention, and reconnect scenarios. Remaining work covers aggregate resource bounding, lifecycle hardening, and the command-line client. Docker and persistence are optional extensions after those core improvements.

## Acceptance criteria

| Criterion | Current state and evidence |
| --- | --- |
| Multiple distinct clients can register | Implemented; socket tests register multiple clients. Only one active session per identity is permitted. |
| Registered clients can address uniquely identified messages to each other | Implemented symmetrically in the server. Tests demonstrate Alice sending to Bob; a bidirectional exchange is not separately tested. |
| Send acceptance/rejection is explicit | `SEND_RESULT` reports mailbox acceptance or business rejection. Invalid commands return `ERROR`. |
| Recipient receives and explicitly acknowledges | Implemented and socket-tested. ACK removes only from the registered recipient's mailbox; wrong-recipient/repeated ACKs have service tests. |
| Messages sent while a recipient is offline are retained | Implemented and socket-tested for an identity that registered previously, up to 100 pending messages per mailbox. Aggregate storage is not bounded. |
| Re-registering an identity restores its mailbox | Implemented and socket-tested after disconnect cleanup. |
| Delivered but unacknowledged messages survive disconnect | Implemented and socket-tested; pending messages are replayed on registration. |
| Mailboxes, sizes, connections, and buffers are bounded | Partial: per-mailbox/frame/session limits and DELIVERY-size admission exist; retained identities, total memory, and other response sizes need work. |
| A slow/malformed client does not block unrelated work | Per-session readers/writers and bounded outbound queues provide isolation; malformed JSON recovery is tested. No deadlines or adversarial slow-client tests. |
| Concurrent operations preserve state | Concurrent identity map/ID set and per-recipient locks protect state. No stress proof, strict FIFO guarantee, or atomic registration/replay transition. |
| Predictable shutdown | Explicit `stop()` is integration-tested. Executable signal handling and full worker termination are incomplete. |
| Runnable source, tests, artifact, and instructions | Build and 29 tests verified; an earlier packaged launch was blocked by occupied port 9000. See README verification record. |
| Optional FIFO / Docker / durable storage | FIFO not guaranteed; Dockerfile present but needs a packaging fix; no persistence implementation on `main`. |

## Completed step: delivery-size validation

**Purpose:** reject messages the server cannot deliver within the protocol's frame limit. This advances the core requirements for bounded message sizes, explicit send rejection, and reconnect delivery that does not repeatedly fail on an oversized pending message.

Before this change, the server checked the incoming SEND frame and only checked DELIVERY when writing it to the recipient socket. These frames contain different fields: DELIVERY includes the registered sender ID. A valid-sized SEND could therefore produce a DELIVERY over 65,536 bytes. The message had already been accepted and stored by the time the writer failed, leaving it pending and liable to fail again on reconnect.

The change moves that check before acceptance:

1. Build and serialize the eventual DELIVERY, including the sender ID and JSON escaping.
2. Validate its UTF-8 byte size with the same check used by the frame writer.
3. If oversized, return a rejected `SEND_RESULT` before calling the service that reserves the ID and stores the message.
4. Otherwise, continue through the existing recipient, duplicate-ID, mailbox, and delivery logic.

The regression test first reproduced incorrect acceptance in three cases: ASCII, multibyte UTF-8, and escaped JSON. After the fix, each case verifies rejection at 65,537 bytes, an empty mailbox after rejection, successful reuse of the rejected ID with a 65,536-byte delivery, and removal after the recipient's ACK. Both connections remain usable through that exchange. The full build passes all 29 test cases and produces the executable JAR.

**Next step:** bound the number of retained logical identities. The existing 100-connection limit does not prevent clients from registering new identities over time, since disconnected identities and mailboxes remain in memory. A registry cap should reject new identities when full while still allowing existing identities to reconnect without losing queued messages. This is the next part of resource bounding; aggregate byte budgets, deadlines, and the complete terminal client remain separate steps.

## Architecture and state

Java 25 is the configured compile/runtime baseline. Standard blocking TCP sockets keep the transport visible in application code. Virtual threads make a reader and writer per connection straightforward without manually managing a selector/event loop. They reduce the cost of blocked threads, but do not replace admission limits or timeouts. Jackson provides JSON serialization and JUnit provides testing; neither owns relay behaviour.

| Component | Responsibility |
| --- | --- |
| `Main` | Creates `RelayServer(9000)` and runs its blocking accept loop. |
| `RelayServer` | Listener, connection semaphore, active sockets, virtual-thread session executor, stop/cleanup. |
| `ClientSession` | Frame reading, protocol dispatch/validation, session identity, outbound queue and writer. |
| `FrameCodec` / `ProtocolCodec` | TCP message boundaries / JSON command and event mapping. |
| `ClientRegistry` | Concurrent map from identity to the retained logical client context. |
| `ClientContext` | Identity, mailbox, active session reference, per-client `ReentrantLock`. |
| `Mailbox` | Insertion-ordered deque of up to 100 pending messages. |
| `RelayService` | Mailbox acceptance, global pending-ID reservation, recipient-scoped ACK deletion. |
| `RelayClient` | Minimal registration-only sample program. |

Each accepted message stores `messageId`, `senderId`, `recipientId`, and `body`. Queued and delivered-but-unacknowledged messages share the same mailbox; there is no separate durable or in-flight store. The global concurrent pending-ID set prevents two currently pending messages from sharing an ID, even across different senders/recipients.

Disconnect clears the matching active session reference, but keeps the identity and mailbox. Cleanup checks the session object so an old session cannot clear a different active session. Nothing is persisted. `RelayMessageRepository.save(...)` is an unused interface, not a persistence implementation.

## Protocol and connection lifecycle

### Framing

The connection is a persistent, bidirectional TCP byte stream. Each command/event is:

1. A four-byte, big-endian signed integer containing the payload byte count.
2. Exactly that many bytes of UTF-8 JSON.

Inbound length must be between 1 and 65,536 inclusive; the prefix is excluded from that count. Invalid length, truncated payload, or socket I/O failure closes the connection, without a guaranteed protocol error response. A partial frame can currently wait indefinitely if the peer keeps the connection open.

Type names are case-sensitive. JSON must be an object with a textual, nonblank `type`. The wire format has no version negotiation. Unknown fields and incompatible values are handled by the current Jackson record mapping; this is not a separately defined strict JSON-schema validator.

### Commands and events

The following are payload examples; each needs the binary prefix on the wire.

Register on each new connection:

```json
{"type":"REGISTER","clientId":"bob"}
```

Successful registration:

```json
{"type":"REGISTERED","clientId":"bob"}
```

After Alice registers on a separate connection, she can send:

```json
{"type":"SEND","messageId":"alice-001","recipientId":"bob","body":"hello"}
```

Alice receives:

```json
{"type":"SEND_RESULT","messageId":"alice-001","accepted":true,"reason":null}
```

Bob receives:

```json
{"type":"DELIVERY","messageId":"alice-001","senderId":"alice","body":"hello"}
```

Bob explicitly acknowledges after processing:

```json
{"type":"ACK","messageId":"alice-001"}
```

There is no ACK response. The server derives sender/acknowledging identity from the registered connection; clients cannot select another sender with a `SEND` field. `clientId`, `recipientId`, and `messageId` must be non-null/nonblank. `body` must be non-null; an empty body is permitted. IDs are used as supplied, without trimming or case normalisation, and have no independent length limit beyond the containing frame.

### Registration, reconnect, and close

1. Open a TCP connection and send `REGISTER`. Registering a new ID creates an empty logical mailbox.
2. A second registration on the same connection returns `ALREADY_REGISTERED`. A different connection using an active identity returns `IDENTITY_IN_USE`; it does not take over.
3. Successful registration enqueues `REGISTERED` and replays pending mailbox messages. There is a concurrency caveat: the active session is published before that response/replay is enqueued, so a concurrent sender can enqueue a delivery first.
4. `SEND` requires registration and a recipient that has registered at least once during this server lifetime. A never-seen recipient is rejected, rather than implicitly creating a mailbox.
5. On EOF/I/O failure, the socket closes, the writer is interrupted, the identity is detached from that session, and the admitted connection permit is eventually released. Pending messages remain.
6. Reconnect by opening a new socket and registering the same ID. Until the old session's cleanup finishes, this may return `IDENTITY_IN_USE`; a client should retry with bounded backoff. Automatic reconnect/backoff is not implemented in `RelayClient`.

There is no authentication: knowledge of an offline identity is enough to claim it. Authentication/encryption are outside the exercise scope.

### Errors and limits

Errors have the shape:

```json
{"type":"ERROR","code":"INVALID_MESSAGE","message":"messageId is required"}
```

| Condition | Response/behaviour |
| --- | --- |
| Missing/blank required command field, or null body | `ERROR / INVALID_MESSAGE` |
| Malformed JSON or command mapping failure | `ERROR / MALFORMED_MESSAGE` |
| Missing, invalid, unknown, or server-only message type | `ERROR / INVALID_MESSAGE_TYPE` |
| Register twice on the same socket | `ERROR / ALREADY_REGISTERED` |
| Identity already active elsewhere | `ERROR / IDENTITY_IN_USE` |
| More than 100 admitted connections | Best-effort `ERROR / CONNECTION_LIMIT_REACHED`, then close the excess socket |
| Valid SEND before registration | `SEND_RESULT`, `accepted:false`, reason `Connection must register before sending` |
| Never-registered recipient | `SEND_RESULT`, `accepted:false`, reason `Unknown recipient` |
| Globally pending message ID reused | `SEND_RESULT`, `accepted:false`, reason `Duplicate message ID` |
| Recipient already has 100 pending messages | `SEND_RESULT`, `accepted:false`, reason `Recipient mailbox is full` |
| Registered SEND would produce a DELIVERY over 65,536 UTF-8 bytes | `SEND_RESULT`, `accepted:false`, reason `Delivery frame exceeds maximum size`; no message or pending ID is retained |
| Outbound queue already has 128 events | Close the affected socket; no guaranteed overflow error event |
| Invalid framing / oversized outgoing frame / I/O failure | Close the affected socket |

Protocol validation errors ordinarily leave a usable connection open. Error delivery is best effort: a broken socket or full outbound queue can prevent the error itself from arriving. SEND rejection reasons are strings rather than dedicated stable error codes.

## Delivery semantics and concurrency

### Acceptance and acknowledgement

Acceptance means the message is stored in the recipient's in-memory mailbox. It does not mean the recipient received or processed it. A sender can lose its connection after the server stores the message but before it receives `SEND_RESULT`, leaving an ambiguous result.

Before mailbox admission, `ClientSession` serializes the eventual `DeliveryEvent` and uses `FrameCodec.validateFrame()` to check its UTF-8 payload size. Validation and frame writing share the same encoding/limit check. This prevents an accepted SEND from becoming an undeliverable oversized DELIVERY when the sender ID is added. The check runs before reserving the message ID, for both online and offline recipients. Oversized delivery rejection takes precedence over recipient, duplicate-ID, and mailbox checks. The event is serialized again when written; this small extra encoding cost keeps the change local to the protocol boundary.

Online delivery enqueues an event without deleting the mailbox entry. Offline messages wait for registration. The registered recipient's ACK removes the matching mailbox entry and then releases its pending ID. ACKs from other recipients cannot remove it. Valid ACKs before registration, unknown IDs, and repeated ACKs after deletion are silently ignored on the wire. The server does not prove that a recipient has actually received a message before accepting its ACK.

IDs become reusable after ACK, and there is no completed-message deduplication history. A delayed ACK for an old ID can remove a newer message with that ID in the same recipient mailbox. Clients should use fresh globally unique IDs and deduplicate received messages themselves. Retrying an ambiguous SEND with a fresh ID can create duplicate business work; retrying the same ID can be rejected while pending or accepted again after the original ACK.

### Redelivery and ordering

The implemented model is at-least-once redelivery on reconnect within a live server process, subject to a deliverable frame and available resources. An ACK lost during disconnect causes replay; there is no exactly-once guarantee. An unacknowledged message on a connection that remains open is not periodically retried. No retry timer or dead-letter mechanism exists.

Mailboxes append under a per-recipient lock, and replay iterates that insertion order. However, `RelayService.send()` stores a message and releases the lock before `ClientSession` reacquires it to enqueue online delivery. Concurrent senders can therefore produce delivery order different from mailbox insertion order. Registration replay and online delivery can also overlap and enqueue duplicates. Strict per-recipient FIFO is not claimed.

### Isolation and synchronisation

The server admits up to 100 sessions using a semaphore. Each session has a virtual-thread reader task and a separate virtual-thread writer. Only that writer writes normal session frames, avoiding interleaved bytes. Producers call nonblocking `offer()` on a 128-event outbound queue; if full, the socket is closed. Mailbox mutations and active-session access use the recipient's lock. Mailboxes are not independently thread-safe; their callers must hold that lock.

Different recipients have separate locks, and message delivery normally queues work instead of performing a blocking socket write under a mailbox lock. A slow socket writer therefore does not normally hold up other recipients. There are still gaps: idle readers/writers have no deadlines, and the connection-limit rejection response is written synchronously from the accept loop. These paths need explicit failure tests before claiming comprehensive slow-client isolation.

## Resource and lifecycle trade-offs

- **In-memory retention:** keeps the core understandable and is allowed by the brief. Restart loses all data and identities; delivery guarantees do not cross a restart.
- **Per-client limits:** 100 pending messages and 128 outbound events bound individual collections. The retained identity map has no cap/expiry, so repeated registration of new identities can grow total memory indefinitely. There is no global queued-byte budget.
- **Size checks:** inbound frames are capped before payload allocation, and registered SENDs are rejected before storage if their encoded DELIVERY exceeds the frame limit. The exact boundary, multibyte UTF-8, and JSON escaping are socket-tested. Independent identity/message-ID limits and boundary validation for other server response shapes remain future work.
- **Timeouts:** production sockets have no registration, partial-frame, idle, write, or ACK deadline. Virtual threads reduce the cost of blocked threads, but 100 admitted idle clients can exhaust connection capacity.
- **Shutdown:** `stop()` closes listener and active sockets; `start()` cleanup waits up to two seconds for its executor, then interrupts outstanding tasks. Writer threads are interrupted without joining. `Main` does not install a shutdown hook, so Ctrl+C does not exercise this controlled path. Concurrent start/stop and accept/stop races are not exhaustively tested.
- **Protocol simplicity:** JSON is readable and binary framing handles TCP splitting/coalescing. There is no protocol version, delivery receipt back to the sender, ACK receipt, or completed-ID history.

## Testing and verification

The suite currently has 29 test cases: 16 unit tests and 13 integration cases, including three parameterized delivery-size cases. `test` runs both groups; `clean verify` also packages the JAR and emits JaCoCo coverage.

| Boundary | Existing coverage |
| --- | --- |
| Frame codec | Round trip, UTF-8, zero/negative length, truncated payload, oversized output |
| Protocol codec | Registration round trip and fixture-based command/type decoding |
| Service/domain | Acceptance, duplicate pending IDs, full mailbox, wrong-recipient ACK, repeated ACK, rejection result |
| Session sockets | Registered/unregistered SEND, delivery plus ACK, offline reconnect, unacknowledged replay, malformed JSON recovery, required-field validation |
| Delivery-size sockets | Reject a DELIVERY one byte over the limit without retaining its message/ID, then retry the same ID at the exact limit and receive/ACK it; ASCII, UTF-8, and escaped JSON variants |
| Server sockets | Explicit shutdown with an active client; rejection at the active-connection cap |

Session integration tests create real loopback sockets around shared registry/service instances. Server tests exercise the actual listener and shutdown path. Tests use socket read timeouts (typically two seconds), bounded polling/joins, and JUnit integration-test timeouts of 3–10 seconds. They avoid an external server dependency. The server-test free-port helper releases a temporary port before binding the relay, so there is still a port-allocation race.

Missing coverage includes concurrent duplicate reservations and send/ACK/reconnect interleavings, strict ordering, slow-reader saturation, partial-frame deadlines, retained-identity exhaustion, other response-size boundaries, process signal shutdown, and container execution. Several implemented rejection paths also lack dedicated tests. Passing the suite is evidence for the covered scenarios, not proof of these behaviours. Further tests would focus on these invariants and failure cases.

Local validation used JDK 25 and Maven 3.9.16. `clean verify` passed and produced the shaded JAR; the Windows wrapper test command also passed. The packaged process was launched but port 9000 was already occupied, so a successful artifact/client run remains unverified. Docker and Linux/macOS execution have not been verified. See README for exact commands and outputs.

## Remaining work in priority order

### 1. Complete artifact validation

Verify the documented commands from a clean checkout, complete the packaged server/client check with port 9000 available, and confirm the hosted CI result. This extends the existing source-level and socket-test validation to the runnable artifact.

### 2. Close the core correctness gaps

1. Add a cap on retained identities and a global pending-byte budget. Reject new state when capacity is exhausted; do not silently evict accepted messages. Add independent field limits and validate other response sizes. DELIVERY-size admission is implemented and regression-tested. Test remaining boundary rejections and resource release on ACK.
2. Add bounded registration/frame deadlines and slow-writer handling, ensuring rejection cannot stall the accept loop. Test one stalled client alongside a healthy exchange. Expose a small set of port/timeout/limit settings without introducing a configuration framework.
3. Install a shutdown hook that calls `stop()`, coordinate admission with shutdown, and await both readers and writers within a defined deadline. Add a process-level shutdown test and a start/stop race test.
4. Make registration response/replay and online mailbox delivery a coordinated transition under the recipient lock. Test concurrent sends and reconnects with barriers/latches. If claiming optional FIFO, drain deliveries from one per-recipient ordered path instead of separately enqueueing each sender's message.
5. Tighten the ID/ACK contract: require fresh IDs and consider a bounded completed-ID history or delivery-generation token if protecting against stale ACKs after ID reuse. Document the retention window instead of claiming permanent deduplication.

These are planned changes, each paired with focused verification of its behaviour.

### 3. Extend the terminal client

Extend `RelayClient` with send, receive, ACK, disconnect, and reconnect operations using the existing codecs. Explicit ACK control allows a message to be received, left unacknowledged, and redelivered after reconnect. Add host/port arguments and bounded waits.

### 4. Optional work only after the core

- **Docker:** copy the explicit shaded JAR, run tests before publishing, verify container startup/shutdown, and pin base-image digests if reproducibility is claimed.
- **Persistence:** first define the storage transaction and recovery contract. Persist before returning acceptance; atomically remove/mark acknowledged messages, rebuild pending-ID/mailbox state at startup, and define storage-full/write-failure responses. A small SQLite implementation could be evaluated then, with crash/restart tests. Merely adding a repository interface does not provide durability.
- **CI/deployment:** the current pipeline builds/tests/packages and uploads a JAR. A release path could version that tested artifact, record its commit/checksum, and promote it or a tested image. Deployment, replicas, and a production messaging platform are outside this exercise.

## AI assistance and ownership

AI assistance was used for repository review, documentation drafting, and identifying gaps against the exercise requirements. Prompts focused on the implementation on `main`, protocol and concurrency behaviour, known limitations, and a prioritised completion plan. Suggestions were checked against source and test code.

Verification included matching the local `main` commit to the remote, inspecting the protocol/state/lifecycle paths and build configuration, running the build and automated tests, and attempting the packaged launch. The occupied-port launch and unverified Docker path are recorded in the validation results. Responsibility for the implementation and its documented behaviour remains with the author.
