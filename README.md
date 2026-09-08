# Message Relay

A small Java TCP client/server messaging relay built for the Candidate Technical Exercise.

The relay implements:

- unique client registration,
- addressed messaging,
- explicit send acceptance/rejection,
- recipient acknowledgements,
- bounded in-memory mailboxes,
- offline message retention,
- reconnect and redelivery,
- malformed-input handling,
- connection and buffer limits,
- predictable shutdown,
- an interactive command-line client,
- CI packaging,
- and a reproducible Docker image.

The relay behaviour is implemented directly in the application. Jackson is used for JSON serialization; no message broker or messaging framework is used.

See [`APPROACH.md`](APPROACH.md) for the architecture, protocol, concurrency model, delivery semantics, trade-offs, known limitations, and design decisions.

---

## Core Requirements

| # | Requirement | Status |
| --- | --- | --- |
| 1 | A client registers with a unique name/ID and multiple clients may be registered | ✅ Implemented |
| 2 | Either client can send a uniquely identified message to the other | ✅ Implemented |
| 3 | The service confirms whether a SEND was accepted or rejected | ✅ Implemented |
| 4 | The recipient receives the message and explicitly acknowledges it | ✅ Implemented |
| 5 | Messages sent while the recipient is offline are retained within defined limits | ✅ Implemented |
| 6 | A client can reconnect with the same identity and receive offline messages | ✅ Implemented |
| 7 | Delivered-but-unacknowledged messages remain available after reconnect | ✅ Implemented |

Delivery is **at least once** within the lifetime of the running relay process.

An accepted message remains pending until the correct recipient acknowledges it. If a client disconnects before ACKing a delivered message, that message may be delivered again after reconnect.

---

# Prerequisites

## Local build

Required:

- JDK 25
- `JAVA_HOME` configured for JDK 25
- TCP port `9000` available when running the relay
- Internet access during the first Maven dependency download

The Maven Wrapper is included, so a separate Maven installation is not required.

## Docker

For container execution:

- Docker Desktop or another Docker Engine
- Docker Compose support

No database, message broker, cloud account, credentials, or external service is required.

---

# Build

Run all commands from the repository root.

## Windows PowerShell

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

## Linux / macOS

```sh
chmod +x mvnw
./mvnw --batch-mode --no-transfer-progress clean verify
```

`clean verify`:

1. compiles the application,
2. runs unit tests,
3. runs socket integration tests,
4. generates the JaCoCo coverage report,
5. creates the executable JAR.

---

# Runnable Artifact

The executable artifact is:

```text
target/message-relay-1.0.0-SNAPSHOT.jar
```

It is a shaded JAR containing the required runtime dependencies.

Run it with:

```sh
java -jar target/message-relay-1.0.0-SNAPSHOT.jar
```

Expected output:

```text
Message relay listening on port 9000
```

The server accepts an optional port argument. The client accepts optional host and port arguments after the identity:

```sh
java -jar target/message-relay-1.0.0-SNAPSHOT.jar 9100
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient bob localhost 9100
```

The defaults remain server port `9000` and client destination `localhost:9000`. Server port `0` requests an available port from the operating system; the startup message prints the actual bound port.

## Entry-point test coverage

Automated tests now launch the actual command-line client and server entry point in separate JVMs. They verify registration, SEND frames and results, displayed deliveries, explicit ACKs, help and invalid commands, console EOF/quit, server EOF/malformed frames, and the JVM shutdown hook with an active client.

These tests use temporary ports and bounded waits. Child JVMs inherit the JaCoCo agent when Maven enables it, so their execution contributes to `target/site/jacoco/index.html` and the XML report consumed by SonarQube. No coverage exclusions are added. The tests run as part of the existing `clean verify` command.

---

# Interactive CLI Demo

The project includes an interactive TCP client.

A full demonstration uses three terminals.

## Terminal 1 — Server

```sh
java -jar target/message-relay-1.0.0-SNAPSHOT.jar
```

## Terminal 2 — Bob

```sh
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient bob
```

## Terminal 3 — Alice

```sh
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient alice
```

The CLI commands are:

```text
send <recipientId> <messageId> <body>
ack <messageId>
help
quit
```

---

# Demo 1 — Send and ACK

From Alice:

```text
send bob msg-1 hello bob
```

Alice receives:

```json
{"type":"SEND_RESULT","messageId":"msg-1","accepted":true,"reason":null}
```

Bob receives:

```json
{"type":"DELIVERY","messageId":"msg-1","senderId":"alice","body":"hello bob"}
```

Bob acknowledges it:

```text
ack msg-1
```

The message is then removed from Bob's pending mailbox.

---

# Demo 2 — Offline Delivery

Start Alice and Bob, then disconnect Bob:

```text
quit
```

Alice sends while Bob is offline:

```text
send bob msg-2 sent while you were offline
```

Alice receives:

```json
{"type":"SEND_RESULT","messageId":"msg-2","accepted":true,"reason":null}
```

Reconnect Bob with the same identity:

```sh
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient bob
```

Bob immediately receives:

```json
{"type":"DELIVERY","messageId":"msg-2","senderId":"alice","body":"sent while you were offline"}
```

Bob can then ACK it:

```text
ack msg-2
```

This demonstrates that the logical client and mailbox survive a TCP disconnect.

---

# Demo 3 — Delivered but Unacknowledged Redelivery

1. Alice sends a message to Bob.
2. Bob receives the `DELIVERY`.
3. Bob disconnects without sending an ACK.
4. Bob reconnects using the same identity.
5. The message is delivered again.
6. Bob sends the ACK.

This demonstrates the relay's at-least-once delivery semantics.

---

# Duplicate Message IDs

Message IDs are globally unique while pending.

For example:

```text
send bob msg-10 first message
send bob msg-10 second message
```

The second SEND is rejected while the first `msg-10` remains pending.

Example response:

```json
{
  "type": "SEND_RESULT",
  "messageId": "msg-10",
  "accepted": false,
  "reason": "Duplicate message ID"
}
```

After the correct recipient ACKs the original message, the ID reservation is released.

Clients should use globally unique IDs in real usage.

---

# Docker

A reproducible Docker image is provided as an optional exercise bonus.

## Docker Compose

From the repository root:

```sh
docker compose up --build
```

Expected output includes:

```text
Message relay listening on port 9000
```

The relay is available at:

```text
localhost:9000
```

Stop the service with `Ctrl+C`.

The JVM shutdown hook invokes the controlled server shutdown path before the container exits.

Clean up Compose resources with:

```sh
docker compose down
```

---

## Docker Build Directly

Build:

```sh
docker build -t message-relay:local .
```

Run:

```sh
docker run --rm --name message-relay -p 127.0.0.1:9000:9000 message-relay:local
```

---

# Tests

Run the complete test suite:

## Windows

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress test
```

## Linux / macOS

```sh
./mvnw --batch-mode --no-transfer-progress test
```

Run only unit tests:

```powershell
.\mvnw.cmd "-Dtest=com/messagerelay/unit/**/*Test.java" test
```

Run only integration tests:

```powershell
.\mvnw.cmd "-Dtest=com/messagerelay/integration/**/*Test.java" test
```

The test suite is intentionally focused on important behaviour and boundaries rather than exhaustive implementation coverage.

Covered areas include:

- frame encoding and decoding,
- UTF-8 framing,
- invalid frame sizes,
- truncated frames,
- protocol decoding,
- registration,
- SEND acceptance and rejection,
- duplicate pending message IDs,
- mailbox capacity,
- explicit acknowledgements,
- wrong-recipient ACKs,
- repeated ACKs,
- offline delivery,
- reconnect,
- delivered-but-unacknowledged redelivery,
- malformed JSON recovery,
- semantic field validation,
- active connection limits,
- shutdown with connected clients,
- DELIVERY frame-size boundaries,
- multibyte UTF-8,
- JSON escaping.

Integration tests use real local TCP sockets.

---

# Protocol Summary

The relay uses a persistent bidirectional TCP connection.

Each message is encoded as:

```text
4-byte big-endian payload length
+
UTF-8 JSON payload
```

Example:

```json
{
  "type": "SEND",
  "messageId": "msg-1",
  "recipientId": "bob",
  "body": "hello"
}
```

The complete protocol and connection lifecycle are documented in [`APPROACH.md`](APPROACH.md).

---

# Resource Limits

| Resource | Limit |
| --- | ---: |
| TCP frame payload | 65,536 UTF-8 bytes |
| Pending messages per mailbox | 100 |
| Active TCP connections | 100 |
| Outbound events per client | 128 |
| Executor shutdown wait | 2 seconds |

A SEND is also checked against the size of the final encoded `DELIVERY` before being accepted.

This prevents a SEND that fits the frame limit from producing an undeliverable DELIVERY after fields such as `senderId` or JSON escaping are added.

---

# Invalid Input and Resource Limits

| Condition | Behaviour |
| --- | --- |
| Missing required field | `ERROR / INVALID_MESSAGE` |
| Malformed JSON | `ERROR / MALFORMED_MESSAGE` |
| Invalid/unsupported type | `ERROR / INVALID_MESSAGE_TYPE` |
| Identity already connected | `ERROR / IDENTITY_IN_USE` |
| Register twice on one connection | `ERROR / ALREADY_REGISTERED` |
| SEND before registration | Rejected `SEND_RESULT` |
| Unknown recipient | Rejected `SEND_RESULT` |
| Duplicate pending ID | Rejected `SEND_RESULT` |
| Mailbox full | Rejected `SEND_RESULT` |
| DELIVERY exceeds frame limit | Rejected `SEND_RESULT` |
| Connection limit reached | Best-effort error, then connection close |
| Outbound queue full | Affected connection closes |
| Invalid TCP framing | Affected connection closes |

Malformed clients are isolated to their own connection and do not stop unrelated clients or the relay server.

---

# Configuration and Lifecycle

| Setting | Behaviour |
| --- | --- |
| Server port | `9000` |
| CLI destination | `localhost:9000` |
| Runtime read timeout | None |
| Runtime write timeout | None |
| ACK timeout | None |
| Mailbox persistence | In memory |
| Controlled shutdown | JVM shutdown hook → `RelayServer.stop()` |

The server shutdown sequence is:

```text
Ctrl+C / Docker SIGTERM
        |
        v
JVM shutdown hook
        |
        v
RelayServer.stop()
        |
        +-- close listener
        +-- close active sockets
        +-- stop accepting connections
        +-- allow session tasks to finish
        +-- interrupt remaining tasks after shutdown wait
```

---

# Dependencies

Runtime:

- Java 25
- Jackson Databind 2.19.2

Testing/build:

- JUnit Jupiter 5.13.4
- JaCoCo
- Maven Surefire
- Maven JAR Plugin
- Maven Shade Plugin

No Kafka, RabbitMQ, Redis, ActiveMQ, Pulsar, NATS, database, or cloud messaging service is required.

---

# CI

GitHub Actions contains separate jobs for:

- Build
- Unit Tests
- Integration Tests
- SonarQube Analysis
- Package

The packaging job produces and uploads the executable shaded JAR.

---

# Known Limitations

The final implementation deliberately remains small and explainable.

Known limitations:

- state is in memory and is lost when the relay process restarts,
- durable storage/restart recovery is not implemented,
- strict FIFO under concurrent sends is not guaranteed,
- retained logical identities do not currently have a global expiry/cap,
- runtime socket read/write/partial-frame deadlines are not implemented,
- sufficiently many idle clients could occupy all available connection slots,
- message IDs can be reused after ACK,
- a very delayed stale ACK combined with ID reuse is therefore ambiguous,
- there is no authentication or TLS,
- there is no automatic client reconnect/backoff,
- unacknowledged messages are replayed on reconnect rather than periodically retried,
- the design targets a single relay process rather than distributed replicas.

Persistence and strict FIFO were optional bonus requirements and were intentionally left outside the final solution.

---

# Final Verification

Before sharing the repository:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Verify the packaged artifact:

```powershell
java -jar target/message-relay-1.0.0-SNAPSHOT.jar
```

Verify Docker:

```powershell
docker compose up --build
```

Then manually demonstrate:

```text
REGISTER
SEND
SEND_RESULT
DELIVERY
ACK
disconnect
offline SEND
reconnect
redelivery
ACK
```

The public repository should contain no secrets, credentials, proprietary source code, or sensitive data.
