# Approach

## Acceptance Criteria

- [x] Multiple clients can connect and register unique identities.
- [x] A registered client can send a uniquely identified message to a known recipient.
- [x] Every send receives an accepted or rejected result.
- [x] The recipient explicitly acknowledges delivered messages.
- [x] Messages for disconnected recipients remain in a bounded mailbox.
- [x] Re-registering the same identity reattaches its logical mailbox.
- [x] Delivered but unacknowledged messages are redelivered after reconnect.
- [x] Malformed input and resource-limit failures are isolated from unrelated sessions.
- [x] Active connections, frames, mailboxes, and outbound buffers are bounded.
- [x] The server closes active sockets and terminates session tasks during shutdown.
- [x] Pending and unacknowledged messages survive a server restart through SQLite.
- [x] A reproducible Docker image is available.
- [ ] Strict FIFO delivery under concurrent online sends is not guaranteed.

## Architecture

The implementation uses the JDK networking and concurrency APIs so the relay behavior remains visible in the project code.

| Component | Responsibility |
| --- | --- |
| `RelayServer` | Accept connections, enforce the connection limit, own session tasks, recover state, and shut down |
| `ClientSession` | Decode commands, validate connection state, enqueue responses, and deliver messages |
| `ClientRegistry` | Map logical client identities to mailboxes and optional active sessions |
| `RelayService` | Coordinate message IDs, mailbox changes, durable writes, ACKs, and recovery |
| `Mailbox` | Hold a bounded FIFO deque of pending messages for one recipient |
| `RelayMessageRepository` | Define the durable storage boundary |
| `SqliteRelayMessageRepository` | Store, delete, and reload pending messages using JDBC |
| `FrameCodec` and `ProtocolCodec` | Apply length framing and JSON serialization |

Persistence is deliberately kept out of `ClientSession`. Network handling depends on `RelayService`, while `RelayService` depends on the repository interface. The default constructors use a transient adapter for isolated unit and socket tests; the application entry point supplies SQLite.

## Protocol and Connection Lifecycle

The protocol uses persistent TCP connections and length-prefixed JSON frames. A four-byte signed big-endian integer gives the UTF-8 payload length. Payloads must be between one byte and 64 KiB.

Client commands are `REGISTER`, `SEND`, and `ACK`. Server events are `REGISTERED`, `SEND_RESULT`, `DELIVERY`, and `ERROR`.

A new connection must send `REGISTER` before `SEND` or `ACK`. Registration attaches the session to the existing logical client context or creates a new one. Two live connections cannot own the same identity. Immediately after successful registration, every pending mailbox entry is enqueued for delivery.

When a connection closes, only its active-session reference is cleared. Its logical client context and mailbox remain available. Reconnecting with the same ID attaches a new session to that context.

## State and Concurrency

The server creates one virtual-thread task per accepted connection. Each session has a separate virtual writer thread and a bounded outbound queue, preventing socket writes from blocking command processing or other clients.

The registry and pending-ID set use concurrent collections. Each client context owns a lock protecting its active session and mailbox. Sends and ACKs for one recipient are serialized by that recipient lock, while operations for different recipients can proceed concurrently.

SQLite permits one writer at a time. Repository writes are serialized by a repository lock and bounded by a two-second SQLite busy timeout. This is appropriate for a single-process exercise and avoids introducing a database server or connection pool. PostgreSQL would provide stronger multi-process write concurrency, but the exercise explicitly excludes multiple server replicas and does not require production scale.

## Delivery Semantics

The relay provides at-least-once delivery:

1. The sender supplies a `messageId`.
2. The service rejects that ID if another pending message already uses it.
3. The service adds the message to the bounded recipient mailbox.
4. The durable repository insert must succeed before `accepted=true` is returned.
5. If the recipient is online, the message is enqueued for delivery.
6. The message remains pending until the registered recipient sends `ACK` with the same ID.
7. Durable deletion succeeds before the mailbox entry and pending-ID reservation are removed.

A disconnect can occur after delivery but before ACK processing. The message therefore remains pending and is delivered again on the next registration.

SQLite assigns a monotonic `sequence_id`, and recovery loads rows in that order. Mailbox iteration is FIFO. Strict FIFO is not claimed for concurrent online sends because mailbox insertion and live delivery occur in separate critical sections and can interleave.

## Duplicate and ACK Behavior

- A duplicate ID is rejected while the original message remains pending.
- An ACK from another recipient does not remove the message.
- A repeated ACK is ignored because the message is no longer pending.
- An ACK received before registration is ignored.
- Clients are expected not to reuse message IDs. The server currently retains deduplication state only while a message is pending, so an extremely delayed ACK could target a later message that reused the same ID.

## Persistence and Recovery

The SQLite database contains only pending messages. The schema and index are created automatically at application startup. A separate migration framework was intentionally avoided because there is one small schema and the exercise favors a bounded, explainable implementation.

If a save fails, the mailbox insertion and message-ID reservation are rolled back and the sender receives a storage-unavailable rejection. If deletion fails during ACK handling, both the durable row and mailbox entry remain pending. If startup recovery fails, the server fails before binding its TCP port.

SQLite was selected instead of PostgreSQL because this is a single-node relay with modest bounded state. It keeps local setup and tests self-contained while still demonstrating durable-before-accept and delete-before-ACK state transitions. The trade-off is serialized writes and dependence on one local filesystem.

## Error and Limit Behavior

- Blank required fields produce `INVALID_MESSAGE`.
- Malformed JSON produces `MALFORMED_MESSAGE` when framing remains valid.
- Missing, unknown, or server-only message types produce `INVALID_MESSAGE_TYPE`.
- A second registration on one connection produces `ALREADY_REGISTERED`.
- Registering an already-connected identity produces `IDENTITY_IN_USE`.
- The 101st active connection receives `CONNECTION_LIMIT_REACHED` and closes.
- The 101st pending message for one mailbox receives a rejected `SEND_RESULT`.
- Filling a session's 128-entry outbound queue closes that slow connection; unacknowledged deliveries remain in its mailbox.
- Invalid or incomplete frame lengths close the connection because frame boundaries can no longer be trusted.

## Testing Boundaries

Unit tests cover message results, framing, serialization, mailbox/service behavior, duplicate IDs, recipient-scoped ACKs, capacity, and storage failures. Socket integration tests cover registration, accepted and rejected sends, delivery, reconnects, redelivery, malformed input, connection limits, and shutdown.

SQLite integration tests use a JUnit temporary directory. They verify ordered persistence after reopening the database, recipient-scoped deletion, service recovery from the same file, and durable removal after ACK. No Docker daemon or network service is needed for tests.

The suite does not currently stress concurrent send ordering, exhaust the outbound queue with a real slow reader, simulate process termination at every persistence boundary, or validate database corruption recovery.

## Known Limitations

- Logical client identities are retained for the server lifetime and are not globally bounded.
- There is no socket registration or idle timeout, so inactive connections can consume the bounded connection slots.
- Message-ID deduplication is retained only while a message is pending.
- Strict FIFO is not guaranteed for concurrent online sends.
- ACK commands do not receive a protocol-level success response.
- SQLite storage is local to one server instance and is unsuitable for active-active replicas.
- Recovery loads all durable pending rows before accepting connections.
- Authentication, encryption, authorization, and schema version migrations are outside the exercise scope.

## Next Steps

1. Bound retained identities and total recovered messages.
2. Add registration and idle socket deadlines.
3. Retain bounded message-ID tombstones or add delivery tokens to make stale ACKs unambiguous.
4. Route mailbox insertion and live delivery through one ordered operation if strict FIFO is required.
5. Add an explicit ACK result event.
6. Add fault-injection tests around process interruption and SQLite corruption.

## AI-Assisted Development

AI assistance was used to compare the implementation against the exercise specification, identify missing persistence and documentation requirements, evaluate PostgreSQL versus SQLite, and propose focused test cases. Suggestions were reviewed against the existing code rather than applied as a framework rewrite. The resulting changes were checked through source inspection and the complete Maven test suite, including temporary-file SQLite integration tests. All submitted behavior and trade-offs remain the candidate's responsibility to explain and modify.
