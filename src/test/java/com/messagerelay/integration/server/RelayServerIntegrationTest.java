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
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.messagerelay.support.TestUtils.findFreePort;
import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.startServer;
import static com.messagerelay.support.TestUtils.waitUntilListening;
import static com.messagerelay.support.TestUtils.writeCommand;
import static org.junit.jupiter.api.Assertions.*;

class RelayServerIntegrationTest {

    @Test
    @Timeout(5)
    void given_running_server_when_stopped_then_server_shuts_down_cleanly() throws Exception {

        int port = findFreePort();

        RelayServer relayServer =
                new RelayServer(port);

        AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();

        Thread serverThread =
                startServer(relayServer, serverFailure);

        waitUntilListening(port);

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

        int port = findFreePort();

        RelayServer relayServer =
                new RelayServer(port);

        AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();

        Thread serverThread =
                startServer(relayServer, serverFailure);

        waitUntilListening(port);

        List<Socket> clients =
                new ArrayList<>();

        try {

            /*
             * Fill all 100 active connection slots.
             *
             * Register each client and wait for REGISTERED
             * so we know the server has actually accepted
             * and started every session.
             */
            for (int i = 0; i < 100; i++) {

                Socket socket =
                        new Socket(
                                "localhost",
                                port
                        );

                socket.setSoTimeout(2_000);

                clients.add(socket);

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
                                "client-" + i
                        );

                writeCommand(output, register);

                /*
                 * Wait for REGISTERED before creating
                 * the next connection.
                 */
                readEvent(input, RegisteredEvent.class);
            }

            /*
             * Connection 101 should be accepted at TCP
             * level only long enough for the server to
             * send a clear rejection.
             */
            try (Socket overflowClient =
                         new Socket(
                                 "localhost",
                                 port
                         )) {

                overflowClient.setSoTimeout(
                        2_000
                );

                DataInputStream overflowInput =
                        new DataInputStream(
                                overflowClient
                                        .getInputStream()
                        );

                ErrorEvent error =
                        readEvent(overflowInput, ErrorEvent.class);

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
                client.close();
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
}