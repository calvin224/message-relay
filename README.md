# Message Relay

A small Java TCP client/server relay for the technical exercise. The server owns registration, in-memory mailboxes, delivery, reconnect redelivery, and acknowledgements. Jackson handles JSON; no broker or messaging framework is used.

The implementation supports registration, addressed messaging, explicit acknowledgements, offline retention, and redelivery after reconnect. The core scenarios are covered by automated tests. Remaining resource, lifecycle, and client limitations are documented below.

See [APPROACH.md](APPROACH.md) for acceptance criteria, the wire protocol, concurrency decisions, test boundaries, and a prioritised completion plan.

## Implementation progress

**Latest completed step: reject messages that cannot fit in a delivery frame.** Previously, a SEND could fit the 65,536-byte limit, but adding the sender ID to DELIVERY could push it over that limit. The server would accept and retain the message, then close the recipient connection when delivery failed. Reconnecting would encounter the same undeliverable message again.

The server now checks the final DELIVERY size before accepting the message. An oversized delivery is rejected without consuming mailbox space or reserving its ID. A delivery exactly at the limit still works. This addresses the exercise requirements for message-size bounds and explicit send acceptance/rejection.

| Area | Progress |
| --- | --- |
| Registration, send, delivery, ACK, offline retention, reconnect | Implemented; core scenarios covered by tests |
| Delivery-size validation | Implemented and regression-tested; 29 test cases pass and the executable JAR builds |
| Retained identities and aggregate memory | Next: cap retained identities while preserving existing clients and queued messages |
| Deadlines, shutdown, concurrency edge cases | Further core work required |
| Complete terminal demonstration | Planned: send, receive, explicit ACK, disconnect, and reconnect |
| FIFO, Docker, restart persistence | Optional extensions after the core requirements |

See [the fix rationale](APPROACH.md#completed-step-delivery-size-validation) and [remaining work](APPROACH.md#remaining-work-in-priority-order) for details. This progress describes the current implementation, not a claim that every exercise requirement is complete.

## Prerequisites

- JDK 25, with `JAVA_HOME` pointing to the JDK and its `bin` directory on `PATH`.
- The checked-in Maven wrapper downloads Maven 3.9.16. Alternatively, use an installed Maven 3.9.16.
- Internet access for the first Maven/dependency download; no database, cloud account, or external service is needed to run or test the relay.
- TCP port 9000 must be free to run the application. Tests use temporary local ports.

Runtime dependency: Jackson Databind 2.19.2 and its transitive dependencies. Tests use JUnit Jupiter 5.13.4. Build/plugin versions are in `pom.xml`; Java 25 is the current compilation target.

## Build and test

Run commands from the repository root.

Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Linux/macOS:

```sh
chmod +x mvnw
./mvnw --batch-mode --no-transfer-progress clean verify
```

This compiles the source, runs all unit and socket integration tests, packages the executable JAR, and generates the JaCoCo report. Integration tests run through Surefire as part of `test`; no separate integration-test profile is needed.

For tests only:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress test
```

On Linux/macOS, substitute `./mvnw`. With installed Maven, substitute `mvn` for the wrapper in either command.

Outputs:

| Output | Location |
| --- | --- |
| Executable JAR, including runtime dependencies | `target/message-relay-1.0.0-SNAPSHOT.jar` |
| Test reports | `target/surefire-reports/` |
| Coverage report | `target/site/jacoco/index.html` |

The shade plugin also leaves `target/original-message-relay-1.0.0-SNAPSHOT.jar`. Use the executable JAR above, which includes Jackson.

## Run

Start the server in one terminal:

```sh
java -jar target/message-relay-1.0.0-SNAPSHOT.jar
```

Expected startup output: `Message relay listening on port 9000`.

In separate terminals, run the sample clients:

```sh
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient alice
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient bob
```

Each sample client connects to `localhost:9000`, registers its identity, prints one response, waits 60 seconds, then closes. The default identity is `alice` if no argument is supplied. **The sample client is only a registration smoke test:** it has no send command, continuous receive loop, ACK command, or automatic reconnect. The socket integration tests exercise those server behaviours using protocol clients in the tests.

For the existing automated messaging demonstration:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dtest=ClientSessionIntegrationTest" test
```

Use `./mvnw` instead on Linux/macOS. This covers send/ACK, offline delivery, unacknowledged redelivery, and validation. The protocol uses a binary length prefix, so typing JSON into a plain text TCP client is insufficient; see [the protocol specification](APPROACH.md#protocol-and-connection-lifecycle).

## Configuration and lifecycle

There are currently no configuration files, environment overrides, or server command-line options.

| Setting | Current behaviour/control |
| --- | --- |
| Server address | All local interfaces; `new ServerSocket(port)` |
| Server port | `9000`, hard-coded in `Main`; embedding code can call `new RelayServer(port)` |
| Sample client destination | `localhost:9000`, hard-coded in `RelayClient` |
| Frame payload | Maximum 65,536 UTF-8 bytes, excluding the four-byte prefix |
| Pending mailbox | 100 messages per registered identity, including delivered but unacknowledged messages |
| Active sessions | 100 admitted connections, including connections that have not registered |
| Outbound queue | 128 events per session; overflow closes that socket |
| Registration/read/write/ACK deadlines | None in the running application |
| Programmatic shutdown | `RelayServer.stop()` closes the listener and active sockets; the server loop then waits up to two seconds for its session executor before requesting interruption |
| Terminal shutdown | Ctrl+C terminates the process; `Main` has no shutdown hook calling `stop()` |

Limits are source constants in `FrameCodec`, `Mailbox`, `RelayServer`, and `ClientSession`. Changing them currently requires rebuilding. The two-second executor wait is not an end-to-end shutdown deadline, and writer threads are interrupted rather than explicitly joined.

Before accepting a registered `SEND`, the server checks the complete encoded `DELIVERY` against the same 65,536-byte limit used by the frame writer. This includes the sender ID, UTF-8 encoding, and JSON escaping. Oversized deliveries return `SEND_RESULT` with `accepted:false` and reason `Delivery frame exceeds maximum size`, without storing a message or reserving its ID. A delivery exactly at the limit is accepted if the recipient and mailbox checks also pass.

All identities, queued messages, and pending message IDs are lost when the process exits. There is no recovery across server restarts. An occupied port produces `BindException`; free port 9000 before launching, or change the source and rebuild both entry points as needed.

## Current limitations

- The number of retained identities and aggregate mailbox memory are not capped. Per-mailbox and connection limits do not provide a whole-server memory bound.
- Idle or partial-frame connections have no deadline and can occupy all connection slots. Slow-reader isolation uses per-session writers and bounded queues, but has no write deadline.
- Identity and message-ID lengths have no independent caps. Boundary validation for other server response shapes remains to be completed.
- Strict FIFO is not guaranteed under concurrent sends/reconnects. Redelivery happens on registration, with no timed retry on an existing connection.
- Message IDs are globally unique only while pending. Reuse after ACK is permitted; a stale ACK can then remove a newer message with the same ID for that recipient.
- The executable lacks a shutdown hook, and the sample client does not demonstrate the complete exchange interactively.
- Docker packaging needs correction and verification. Durable storage and restart recovery are not implemented on `main`; the repository interface is unused.

The [development roadmap](APPROACH.md#remaining-work-in-priority-order) prioritises resource bounds, lifecycle handling, and a complete terminal client before optional persistence and infrastructure.

## CI and Docker status

`.github/workflows/ci.yaml` defines Java 25 build, unit-test, integration-test, SonarQube, and packaging jobs for pushes/PRs to `main`. The packaging job uploads the executable JAR as the `message-relay` artifact after build and tests pass. It does not depend on SonarQube analysis. SonarQube uses repository configuration and `SONAR_TOKEN`; local build/test commands do not require those settings. Deployment and image publishing are not configured. Hosted CI status has not been verified as part of the local validation below.

A two-stage Dockerfile exists, but its final `COPY --from=build /app/target/*.jar app.jar` matches both the shaded and original JARs. It needs an explicit executable-JAR path before being treated as a working bonus. It also skips tests and uses mutable image tags. Docker build/run has not been verified for this submission.

After correcting that copy and passing `clean verify`, the intended commands are:

```sh
docker build -f docker/Dockerfile -t message-relay:local .
docker run --rm --name message-relay -p 127.0.0.1:9000:9000 message-relay:local
```

## Verification record

Local validation of the delivery-size fix:

- Windows, JDK 25, Maven 3.9.16: `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify` passed, producing the executable JAR and coverage report: **29 tests, zero failures/errors/skips**.
- The three new regression cases failed before the fix and passed afterwards. They cover ASCII, multibyte UTF-8, and JSON escaping, including rejection without mailbox/ID retention and delivery/ACK at the exact frame limit.
- An earlier packaged-server launch reached socket binding but failed because local port 9000 was occupied. A successful packaged server/client smoke run remains to be repeated with that port free. Automated socket tests passed on temporary ports.
