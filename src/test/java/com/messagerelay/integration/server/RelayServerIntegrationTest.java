package com.messagerelay.integration.server;

import com.messagerelay.server.RelayServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RelayServerIntegrationTest {

    @Test
    @Timeout(5)
    void serverStopsCleanly() throws Exception {

        int port = findFreePort();

        RelayServer relayServer =
                new RelayServer(port);

        AtomicReference<Throwable> serverFailure =
                new AtomicReference<>();

        Thread serverThread =
                Thread.ofVirtual().start(() -> {
                    try {
                        relayServer.start();
                    } catch (Throwable e) {
                        serverFailure.set(e);
                    }
                });

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

    private int findFreePort() throws IOException {
        try (ServerSocket socket =
                     new ServerSocket(0)) {

            return socket.getLocalPort();
        }
    }

    private void waitUntilListening(
            int port
    ) throws Exception {

        long deadline =
                System.currentTimeMillis() + 2_000;

        while (System.currentTimeMillis() < deadline) {

            try (Socket ignored =
                         new Socket(
                                 "localhost",
                                 port
                         )) {

                return;

            } catch (IOException e) {
                Thread.sleep(20);
            }
        }

        throw new IllegalStateException(
                "Relay server did not start in time"
        );
    }
}