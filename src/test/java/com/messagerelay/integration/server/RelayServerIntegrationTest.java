package com.messagerelay.integration.server;

import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.events.ErrorEvent;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.server.RelayServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.messagerelay.support.TestUtils.findFreePort;
import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.startServer;
import static com.messagerelay.support.TestUtils.writeCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RelayServerIntegrationTest {

    @Test
    @Timeout(5)
    void given_running_server_when_stopped_then_server_shuts_down_cleanly()
            throws Exception {

        int port =
                findFreePort();

        RelayServer relayServer =
                new RelayServer(port);

        AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();

        Thread serverThread =
                startServer(
                        relayServer,
                        serverFailure
                );

        /*
         * Establishing a real TCP connection proves
         * that the server has bound the port and is
         * accepting clients.
         */
        try (Socket ignored =
                     connectWhenAvailable(port)) {
            // Connection is only used as a readiness check.
        }

        relayServer.stop();

        serverThread.join(2_000);

        assertFalse(
                serverThread.isAlive()
        );

        assertNull(
                serverFailure.get()
        );
    }

    @Test
    @Timeout(10)
    void given_active_connection_limit_reached_when_client_connects_then_connection_is_rejected()
            throws Exception {

        int port =
                findFreePort();

        RelayServer relayServer =
                new RelayServer(port);

        AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();

        Thread serverThread =
                startServer(
                        relayServer,
                        serverFailure
                );

        List<Socket> clients =
                new ArrayList<>();

        try {

            /*
             * Rather than opening and closing a separate
             * readiness-probe connection, the first
             * successful connection becomes client 0.
             *
             * This avoids racing with the semaphore
             * permit being released by a probe session.
             */
            Socket firstClient =
                    connectWhenAvailable(port);

            clients.add(firstClient);

            registerClient(
                    firstClient,
                    "client-0"
            );

            /*
             * Client 0 already occupies one of the
             * 100 active connection slots.
             */
            for (int i = 1; i < 100; i++) {

                Socket socket =
                        new Socket(
                                "localhost",
                                port
                        );

                socket.setSoTimeout(
                        2_000
                );

                clients.add(socket);

                registerClient(
                        socket,
                        "client-" + i
                );
            }

            /*
             * All 100 permits are now occupied.
             * Connection 101 should receive a clear
             * protocol error and then be closed.
             */
            try (Socket overflowClient =
                         new Socket(
                                 "localhost",
                                 port
                         )) {

                overflowClient.setSoTimeout(
                        2_000
                );

                DataInputStream input =
                        new DataInputStream(
                                overflowClient
                                        .getInputStream()
                        );

                ErrorEvent error =
                        readEvent(
                                input,
                                ErrorEvent.class
                        );

                assertEquals(
                        MessageType.ERROR,
                        error.type()
                );

                assertEquals(
                        ErrorCode.CONNECTION_LIMIT_REACHED,
                        error.code()
                );
            }

        } finally {

            for (Socket client : clients) {

                try {
                    client.close();

                } catch (IOException ignored) {
                    // Best-effort test cleanup.
                }
            }

            relayServer.stop();

            serverThread.join(2_000);
        }

        assertFalse(
                serverThread.isAlive()
        );

        assertNull(
                serverFailure.get()
        );
    }

    private void registerClient(
            Socket socket,
            String clientId
    ) throws Exception {

        socket.setSoTimeout(
                2_000
        );

        DataInputStream input =
                new DataInputStream(
                        socket.getInputStream()
                );

        DataOutputStream output =
                new DataOutputStream(
                        socket.getOutputStream()
                );

        RegisterCommand register =
                new RegisterCommand(
                        MessageType.REGISTER,
                        clientId
                );

        writeCommand(
                output,
                register
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

        IOException lastFailure =
                null;

        while (System.nanoTime()
                < deadline) {

            try {

                Socket socket =
                        new Socket(
                                "localhost",
                                port
                        );

                socket.setSoTimeout(
                        2_000
                );

                return socket;

            } catch (IOException exception) {

                lastFailure =
                        exception;

                Thread.sleep(20);
            }
        }

        throw new IllegalStateException(
                "Relay server did not start in time",
                lastFailure
        );
    }
}