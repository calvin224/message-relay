# Message Relay

A small Java TCP relay with an interactive client. Clients register an identity, send addressed messages and explicitly acknowledge deliveries. Offline and unacknowledged messages remain available when the recipient reconnects.

The relay owns the messaging behaviour; Jackson handles JSON. No broker, database or cloud service is used.

- [APPROACH.md](APPROACH.md): protocol, decisions, tests and limitations.
- [DESIGN.md](DESIGN.md): class diagram and command flow.

## Build and test

Requires JDK 25 with `JAVA_HOME` set. The Maven Wrapper is included; the first build needs internet access to download Maven and dependencies. Run from the repository root.

Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Linux/macOS:

```sh
chmod +x mvnw
./mvnw --batch-mode --no-transfer-progress clean verify
```

This compiles the code, runs all tests, generates coverage and packages:
`target/message-relay-1.0.0-SNAPSHOT.jar`. The JAR includes its runtime dependencies.

For tests only, run `.\mvnw.cmd test` on Windows or `./mvnw test` on Linux/macOS. Coverage is in `target/site/jacoco/index.html`.

Dependencies are pinned in `pom.xml`: Jackson Databind 2.19.2, JUnit Jupiter 5.13.4 and test-only Awaitility 4.3.0. JaCoCo measures coverage; Maven Shade packages the runnable JAR.

## Run and demonstrate

Open three terminals after building.

Server:

```sh
java -jar target/message-relay-1.0.0-SNAPSHOT.jar
```

Bob:

```sh
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient bob
```

Alice:

```sh
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient alice
```

Wait for both clients to receive `REGISTERED`.

1. Alice enters `send bob msg-1 hello bob`. Alice receives `SEND_RESULT`; Bob receives `DELIVERY`.
2. Bob enters `ack msg-1`. The server removes that pending message.
3. Bob enters `quit`. Once the server reports his disconnect, Alice enters `send bob msg-2 offline message`.
4. Restart Bob with the same command. He receives `msg-2`.
5. Quit Bob without ACKing, then restart him again. He receives `msg-2` again; enter `ack msg-2`.

The client also supports `help`. Use a new message ID for each new message.

The server defaults to port `9000`; an optional argument changes it. The client accepts `<clientId> [host] [port]`:

```sh
java -jar target/message-relay-1.0.0-SNAPSHOT.jar 9100
java -cp target/message-relay-1.0.0-SNAPSHOT.jar com.messagerelay.client.RelayClient bob localhost 9100
```

Port `0` asks the OS for an available server port, which is printed at startup.

## Limits and shutdown

| Resource | Limit |
| --- | --- |
| Frame payload | 65,536 UTF-8 bytes |
| Pending messages per identity | 100 |
| Retained identities, including offline clients | 100 |
| Active connections | 100 |
| Outbound events per connection | 128 |
| Executor shutdown wait | 2 seconds |

Limits are code constants. There are no runtime read, write, registration or ACK deadlines. New identities are rejected at capacity, but existing offline identities can reconnect when a TCP slot is available.

Stop the server with `Ctrl+C`. The shutdown hook closes the listener and active sockets; the server's cleanup waits up to two seconds for session tasks before interrupting remaining work. All in-memory state is lost on process exit.

## Docker

Requires Docker Engine and Compose. Build and start with:

```sh
docker compose up --build
```

This exposes the relay on `localhost:9000`. Stop with `Ctrl+C`; remove the container with `docker compose down`. Compose allows five seconds for shutdown.

Alternatively:

```sh
docker build -t message-relay:local .
docker run --rm --name message-relay -p 127.0.0.1:9000:9000 message-relay:local
```

The Docker build skips tests, so run `clean verify` separately.

## Submission status

The seven core scenarios are implemented: registration, addressed sends, acceptance/rejection, delivery and ACK, offline retention, reconnect, and unacknowledged redelivery. Docker is included; strict concurrent FIFO and persistence are not.

Known limits include no authentication/TLS, no automatic reconnect or timed redelivery, no identity expiry, and no separate heap/queued-byte budget. See [APPROACH.md](APPROACH.md) for the failure trade-offs.

GitHub Actions builds, tests, runs Sonar analysis and uploads the executable JAR. Packaging does not depend on the Sonar job, so check both results before sharing. Run the build and demo above against the submitted commit, and ensure the public repository contains no secrets or sensitive data.
