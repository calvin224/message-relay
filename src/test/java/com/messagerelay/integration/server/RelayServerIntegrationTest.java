package com.messagerelay.integration.server;

import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.server.RelayServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.startServer;
import static com.messagerelay.support.TestUtils.writeCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RelayServerIntegrationTest {

    @Test
    @Timeout(5)
    void given_active_client_when_server_stops_then_client_is_disconnected_and_server_shuts_down_cleanly()
            throws Exception {

        RelayServer relayServer =
                new RelayServer(0);

        AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();

        Thread serverThread =
                startServer(
                        relayServer,
                        serverFailure
                );

        try (Socket client =
                     new Socket("localhost", relayServer.awaitListeningPort(2, TimeUnit.SECONDS))) {

            /*
             * Registering proves the connection has
             * been accepted and a ClientSession is
             * actively running on the server.
             */
            registerClient(
                    client,
                    "alice"
            );

            relayServer.stop();

            /*
             * The server closes active client sockets
             * during shutdown, so the client sees EOF.
             */
            assertEquals(
                    -1,
                    client.getInputStream().read()
            );
        } finally {
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

    @Test
    @Timeout(10)
    void given_active_connection_limit_reached_when_client_connects_then_connection_is_rejected()
            throws Exception {

        RelayServer relayServer =
                new RelayServer(0);

        AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();

        Thread serverThread =
                startServer(
                        relayServer,
                        serverFailure
                );

        try (ClientConnections clients = new ClientConnections()) {

            int port = relayServer.awaitListeningPort(2, TimeUnit.SECONDS);

            Socket firstClient =
                    clients.add(new Socket("localhost", port));

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
                        clients.add(new Socket(
                                "localhost",
                                port
                        ));

                socket.setSoTimeout(
                        2_000
                );

                registerClient(
                        socket,
                        "client-" + i
                );
            }

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

                try (Socket anotherOverflowClient = new Socket("localhost", port)) {
                    anotherOverflowClient.setSoTimeout(2_000);
                    assertEquals(-1, anotherOverflowClient.getInputStream().read());
                }

                assertEquals(
                        -1,
                        input.read()
                );
            }


        } finally {

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

    private static final class ClientConnections implements Closeable {

        private final List<Socket> clients = new ArrayList<>();

        private Socket add(Socket client) {
            clients.add(client);
            return client;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;

            for (Socket client : clients) {
                try {
                    client.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }

            if (failure != null) {
                throw failure;
            }
        }
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

}
