package com.messagerelay.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RelayServer {

    private final int port;

    private final ClientRegistry clientRegistry =
            new ClientRegistry();

    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    public RelayServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket =
                     new ServerSocket(port)) {

            System.out.println(
                    "Message relay listening on port "
                            + port
            );

            while (true) {
                Socket socket =
                        serverSocket.accept();

                System.out.println(
                        "Client connected: "
                                + socket.getRemoteSocketAddress()
                );

                executor.submit(
                        new ClientSession(
                                socket,
                                clientRegistry
                        )
                );
            }
        }
    }
}