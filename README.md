# Message Relay

A bounded TCP client/server message relay with explicit registration, addressed delivery, acknowledgements, offline mailboxes, and SQLite-backed recovery.

## Requirements

- Java 25
- Docker, only when building or running the optional container image

The Maven wrapper is included, so a separate Maven installation is not required.

## Build and Test

Run the complete deterministic test suite:

```bash
./mvnw --batch-mode --no-transfer-progress clean test
```

On Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean test
```

Create the executable shaded JAR:

```bash
./mvnw --batch-mode --no-transfer-progress clean package
```

The runnable artifact is:

```text
target/message-relay-1.0.0-SNAPSHOT.jar
```

## Run Locally

Start the relay with its default configuration:

```bash
java --enable-native-access=ALL-UNNAMED -jar target/message-relay-1.0.0-SNAPSHOT.jar
```

The server listens on TCP port `9000` and stores pending messages in `data/message-relay.db`.

Configuration is supplied through environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `RELAY_PORT` | `9000` | TCP listening port |
| `RELAY_DB_PATH` | `data/message-relay.db` | SQLite database file |

For example:

```bash
RELAY_PORT=9100 RELAY_DB_PATH=/tmp/relay.db java --enable-native-access=ALL-UNNAMED -jar target/message-relay-1.0.0-SNAPSHOT.jar
```

On Windows PowerShell:

```powershell
$env:RELAY_PORT = "9100"
$env:RELAY_DB_PATH = "C:\temp\relay.db"
java --enable-native-access=ALL-UNNAMED -jar target/message-relay-1.0.0-SNAPSHOT.jar
```

Press `Ctrl+C` to trigger the shutdown hook. The server closes the listening socket and active client sockets, then waits up to two seconds for session tasks to finish.

## Docker

Build the image:

```bash
docker build -f docker/Dockerfile -t message-relay .
```

Run it with a named volume so the SQLite database survives container replacement:

```bash
docker run --rm --name message-relay -p 9000:9000 -v message-relay-data:/data message-relay
```

Stop it with:

```bash
docker stop message-relay
```

## Protocol

Each TCP frame contains:

1. A four-byte signed big-endian payload length.
2. A UTF-8 JSON object of exactly that length.

Frames are limited to 64 KiB. The client must register before sending or acknowledging messages.

Register a connection:

```json
{"type":"REGISTER","clientId":"alice"}
```

Successful registration:

```json
{"type":"REGISTERED","clientId":"alice"}
```

Send a uniquely identified message:

```json
{"type":"SEND","messageId":"msg-1","recipientId":"bob","body":"hello bob"}
```

Send result:

```json
{"type":"SEND_RESULT","messageId":"msg-1","accepted":true,"reason":null}
```

Recipient delivery:

```json
{"type":"DELIVERY","messageId":"msg-1","senderId":"alice","body":"hello bob"}
```

Recipient acknowledgement:

```json
{"type":"ACK","messageId":"msg-1"}
```

Protocol failures are returned as `ERROR` events where the frame remains recoverable. Invalid frame lengths and incomplete frames close the connection.

```json
{"type":"ERROR","code":"INVALID_MESSAGE","message":"messageId is required"}
```

## Delivery and Persistence

- A message is accepted only after it is present in the recipient mailbox and committed to SQLite.
- Acknowledgement is valid only from the registered recipient connection.
- Durable state is deleted before the in-memory mailbox entry is removed.
- If a durable save fails, the send is rejected and its in-memory entry is rolled back.
- If durable deletion fails, the message remains pending for redelivery.
- Pending messages are loaded in insertion order before the server accepts connections.
- SQLite uses WAL mode, normal synchronous mode, a two-second busy timeout, and serialized writes inside this server process.

This provides at-least-once delivery. A client can receive a duplicate after disconnecting before its acknowledgement is processed.

## Resource Limits

| Resource | Limit | Behavior at limit |
| --- | ---: | --- |
| TCP frame | 64 KiB | Connection closes for an invalid frame |
| Active connections | 100 | New connection receives `CONNECTION_LIMIT_REACHED` and closes |
| Messages per mailbox | 100 | Send receives a rejected `SEND_RESULT` |
| Outbound events per connection | 128 | Slow client connection closes; pending deliveries remain queued |
| Executor shutdown wait | 2 seconds | Remaining session tasks are interrupted |

## Tests

The test suite covers:

- frame encoding, UTF-8, malformed lengths, and maximum size;
- protocol serialization and required fields;
- registration, sending, delivery, acknowledgement, and errors;
- offline delivery and unacknowledged redelivery;
- duplicate IDs, mailbox capacity, and recipient-scoped ACKs;
- active connection limits and server shutdown;
- SQLite save, ordered reload, recipient-scoped deletion, and restart recovery;
- durable save and delete failure behavior.

Tests use loopback sockets and temporary SQLite files. They do not require Docker or an external database.

## Project Structure

```text
src/main/java/com/messagerelay
├── client       Example registration client
├── domain       Relay message and send result
├── protocol     Framing, JSON commands, events, and error types
├── repository   Transient and SQLite persistence adapters
├── server       Connection, registration, mailbox, and lifecycle handling
└── service      Delivery state and persistence coordination
```

Design decisions, acceptance criteria, trade-offs, limitations, and AI-tool usage are documented in [APPROACH.md](APPROACH.md).
