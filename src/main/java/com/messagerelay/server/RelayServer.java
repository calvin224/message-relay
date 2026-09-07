package com.messagerelay.server;

import com.messagerelay.service.RelayService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RelayServer {

    private final int port;

    private final ClientRegistry clientRegistry =
            new ClientRegistry();

    private final RelayService relayService =
            new RelayService();

    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    private volatile boolean running;

    private ServerSocket serverSocket;

    public RelayServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket =
                new ServerSocket(port);

        running = true;

        System.out.println(
                "Message relay listening on port "
                        + port
        );

        try {
            while (running) {

                try {
                    Socket socket =
                            serverSocket.accept();

                    System.out.println(
                            "Client connected: "
                                    + socket.getRemoteSocketAddress()
                    );

                    executor.submit(
                            new ClientSession(
                                    socket,
                                    clientRegistry,
                                    relayService
                            )
                    );

                } catch (IOException e) {

                    if (running) {
                        throw e;
                    }
                }
            }

        } finally {
            running = false;

            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }

            executor.shutdown();
        }
    }

    public void stop() throws IOException {
        running = false;

        if (serverSocket != null
                && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}