# Message Relay

A small Java TCP client/server relay for the technical exercise. The server owns registration, in-memory mailboxes, delivery, reconnect redelivery, and acknowledgements. Jackson handles JSON; no broker or messaging framework is used.

This documents the implementation on `main` at `697cc64` (confirmed against GitHub during review). Work on other branches is not part of this submission. The core send/receive/reconnect scenarios are implemented and tested, but there are remaining resource, lifecycle, and usability limitations. This is an incomplete exercise submission, not a production service.

See [APPROACH.md](APPROACH.md) for acceptance criteria, the wire protocol, concurrency decisions, test boundaries, and a prioritised completion plan.

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

All identities, queued messages, and pending message IDs are lost when the process exits. There is no recovery across server restarts. An occupied port produces `BindException`; free port 9000 before launching, or change the source and rebuild both entry points as needed.

## Current limitations

- The number of retained identities and aggregate mailbox memory are not capped. Per-mailbox and connection limits do not provide a whole-server memory bound.
- Idle or partial-frame connections have no deadline and can occupy all connection slots. Slow-reader isolation uses per-session writers and bounded queues, but has no write deadline.
- Accepted input can produce an oversized `DELIVERY` frame, because the registered sender ID is added later. Such a message can remain pending while delivery repeatedly closes the recipient connection.
- Strict FIFO is not guaranteed under concurrent sends/reconnects. Redelivery happens on registration, with no timed retry on an existing connection.
- Message IDs are globally unique only while pending. Reuse after ACK is permitted; a stale ACK can then remove a newer message with the same ID for that recipient.
- The executable lacks a shutdown hook, and the sample client does not demonstrate the complete exchange interactively.
- Docker packaging needs correction and verification. Durable storage and restart recovery are not implemented on `main`; the repository interface is unused.

These gaps and their proposed fixes are described in [APPROACH.md](APPROACH.md#remaining-work-in-priority-order). The next step is a small core-correctness pass, followed by interview preparation; optional persistence and extra infrastructure should wait.

## CI and Docker status

`.github/workflows/ci.yaml` defines Java 25 build, unit-test, integration-test, SonarQube, and packaging jobs for pushes/PRs to `main`. The packaging job uploads the executable JAR as the `message-relay` artifact after build and tests pass. It does not depend on SonarQube analysis. SonarQube uses repository configuration and `SONAR_TOKEN`; local build/test commands do not require those settings. Deployment and image publishing are not configured. The workflow definition was inspected; this is not a claim that the latest hosted run is green.

A two-stage Dockerfile exists, but its final `COPY --from=build /app/target/*.jar app.jar` matches both the shaded and original JARs. It needs an explicit executable-JAR path before being treated as a working bonus. It also skips tests and uses mutable image tags. Docker build/run has not been verified for this submission.

After correcting that copy and passing `clean verify`, the intended commands are:

```sh
docker build -f docker/Dockerfile -t message-relay:local .
docker run --rm --name message-relay -p 127.0.0.1:9000:9000 message-relay:local
```

## Verification record

During the documentation review of `main` at `697cc64`:

- Windows, JDK 25, Maven 3.9.16: `.\mvnw.cmd --batch-mode --no-transfer-progress clean verify` and the equivalent installed-Maven command passed, producing the executable JAR and coverage report.
- `.\mvnw.cmd --batch-mode --no-transfer-progress test` passed: **26 tests, zero failures/errors/skips**. The Windows wrapper initially failed inside the restricted agent environment and succeeded when rerun outside that restriction.
- Launching the packaged server reached socket binding but failed because local port 9000 was occupied. A successful packaged server/client smoke run remains to be repeated with that port free. Automated socket tests passed on temporary ports.

Before sharing the public repository, rerun the build from a clean checkout, complete that launch check, check the hosted CI result, and ensure the submitted revision contains these documents and no sensitive material.
