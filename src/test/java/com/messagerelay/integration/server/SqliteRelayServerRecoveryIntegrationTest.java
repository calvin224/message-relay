package com.messagerelay.integration.server;

import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.events.DeliveryEvent;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.events.SendResultEvent;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.repository.SqliteRelayMessageRepository;
import com.messagerelay.server.RelayServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.messagerelay.support.TestUtils.findFreePort;
import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.startServer;
import static com.messagerelay.support.TestUtils.writeCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRelayServerRecoveryIntegrationTest {

    @TempDir
    private Path temporaryDirectory;

    // Persistence bonus: offline delivery survives a real server restart and durable ACK.
    @Test
    @Timeout(10)
    void given_offline_pending_message_when_server_restarts_then_recipient_receives_and_acknowledges_it()
            throws Exception {
        Path databasePath =
                temporaryDirectory.resolve(
                        "relay.db"
                );

        int firstPort = findFreePort();

        RelayServer firstServer =
                new RelayServer(
                        firstPort,
                        new SqliteRelayMessageRepository(
                                databasePath
                        )
                );

        AtomicReference<Throwable> firstFailure =
                new AtomicReference<>();
        Thread firstThread =
                startServer(
                        firstServer,
                        firstFailure
                );

        try {
            registerAndDisconnect(
                    firstPort,
                    "bob"
            );

            sendMessage(
                    firstPort,
                    "alice",
                    "bob"
            );

        } finally {
            firstServer.stop();
            firstThread.join(2_000);
        }

        assertFalse(firstThread.isAlive());
        assertNull(firstFailure.get());

        int restartedPort = findFreePort();
        RelayServer restartedServer =
                new RelayServer(
                        restartedPort,
                        new SqliteRelayMessageRepository(
                                databasePath
                        )
                );

        AtomicReference<Throwable> restartedFailure =
                new AtomicReference<>();
        Thread restartedThread =
                startServer(
                        restartedServer,
                        restartedFailure
                );

        try (Socket bob =
                     connectWhenAvailable(restartedPort)) {
            DataInputStream input =
                    new DataInputStream(
                            bob.getInputStream()
                    );
            DataOutputStream output =
                    new DataOutputStream(
                            bob.getOutputStream()
                    );

            register(
                    input,
                    output,
                    "bob"
            );

            DeliveryEvent delivery =
                    readEvent(
                            input,
                            DeliveryEvent.class
                    );

            assertEquals(
                    MessageType.DELIVERY,
                    delivery.type()
            );
            assertEquals(
                    "msg-1",
                    delivery.messageId()
            );
            assertEquals(
                    "alice",
                    delivery.senderId()
            );
            assertEquals(
                    "hello bob",
                    delivery.body()
            );

            writeCommand(
                    output,
                    new AckCommand(
                            MessageType.ACK,
                            delivery.deliveryId()
                    )
            );

            awaitRepositoryEmpty(databasePath);

        } finally {
            restartedServer.stop();
            restartedThread.join(2_000);
        }

        assertFalse(restartedThread.isAlive());
        assertNull(restartedFailure.get());
        assertTrue(
                new SqliteRelayMessageRepository(
                        databasePath
                ).findAllPending()
                        .isEmpty()
        );
    }

    private void registerAndDisconnect(
            int port,
            String clientId
    ) throws Exception {
        try (Socket socket =
                     connectWhenAvailable(port)) {
            register(
                    new DataInputStream(
                            socket.getInputStream()
                    ),
                    new DataOutputStream(
                            socket.getOutputStream()
                    ),
                    clientId
            );
        }
    }

    private void sendMessage(
            int port,
            String senderId,
            String recipientId
    ) throws Exception {
        try (Socket socket =
                     connectWhenAvailable(port)) {
            DataInputStream input =
                    new DataInputStream(
                            socket.getInputStream()
                    );
            DataOutputStream output =
                    new DataOutputStream(
                            socket.getOutputStream()
                    );

            register(
                    input,
                    output,
                    senderId
            );

            writeCommand(
                    output,
                    new SendCommand(
                            MessageType.SEND,
                            "msg-1",
                            recipientId,
                            "hello bob"
                    )
            );

            SendResultEvent result =
                    readEvent(
                            input,
                            SendResultEvent.class
                    );

            assertTrue(result.accepted());
            assertEquals(
                    "msg-1",
                    result.messageId()
            );
        }
    }

    private void register(
            DataInputStream input,
            DataOutputStream output,
            String clientId
    ) throws Exception {
        writeCommand(
                output,
                new RegisterCommand(
                        MessageType.REGISTER,
                        clientId
                )
        );

        RegisteredEvent registered =
                readEvent(
                        input,
                        RegisteredEvent.class
                );

        assertEquals(
                MessageType.REGISTERED,
                registered.type()
        );
        assertEquals(
                clientId,
                registered.clientId()
        );
    }

    private Socket connectWhenAvailable(
            int port
    ) throws Exception {
        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS
                        .toNanos(2);

        IOException lastFailure = null;

        while (System.nanoTime() < deadline) {
            try {
                Socket socket =
                        new Socket(
                                "localhost",
                                port
                        );

                socket.setSoTimeout(2_000);
                return socket;

            } catch (IOException exception) {
                lastFailure = exception;
                Thread.sleep(20);
            }
        }

        throw new IllegalStateException(
                "Relay server did not start in time",
                lastFailure
        );
    }

    private void awaitRepositoryEmpty(
            Path databasePath
    ) throws Exception {
        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS
                        .toNanos(2);

        while (System.nanoTime() < deadline) {
            if (new SqliteRelayMessageRepository(
                    databasePath
            ).findAllPending().isEmpty()) {
                return;
            }

            Thread.sleep(20);
        }

        throw new AssertionError(
                "Acknowledged message remained durable"
        );
    }
}
