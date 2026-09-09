package com.messagerelay.server;

import com.messagerelay.service.RelayService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RelayServer {

    private static final System.Logger LOGGER =
            System.getLogger(RelayServer.class.getName());

    private static final int MAX_ACTIVE_CONNECTIONS =
            100;

    private static final int SHUTDOWN_TIMEOUT_SECONDS =
            2;

    private final int port;

    private final ClientRegistry clientRegistry =
            new ClientRegistry();

    private final RelayService relayService =
            new RelayService(clientRegistry);

    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    private final Semaphore connectionPermits =
            new Semaphore(
                    MAX_ACTIVE_CONNECTIONS
            );

    private final Set<Socket> activeSockets =
            ConcurrentHashMap.newKeySet();

    private volatile boolean running;

    private final CompletableFuture<Integer> listeningPort =
            new CompletableFuture<>();

    private ServerSocket serverSocket;

    public RelayServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {

        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException failure) {
            listeningPort.completeExceptionally(failure);
            throw failure;
        }

        running = true;
        listeningPort.complete(serverSocket.getLocalPort());

        LOGGER.log(
                System.Logger.Level.INFO,
                "Message relay listening on port {0}",
                Integer.toString(serverSocket.getLocalPort())
        );

        try {

            while (running) {

                try {

                    Socket socket =
                            serverSocket.accept();

                    if (!connectionPermits
                            .tryAcquire()) {

                        rejectConnection(socket);
                        continue;
                    }

                    activeSockets.add(socket);

                    LOGGER.log(
                            System.Logger.Level.INFO,
                            "Client connected: {0}",
                            socket.getRemoteSocketAddress()
                    );

                    executor.submit(
                            () -> runSession(socket)
                    );

                } catch (IOException e) {

                    if (running) {
                        throw e;
                    }
                }
            }

        } finally {

            running = false;

            if (serverSocket != null
                    && !serverSocket.isClosed()) {

                serverSocket.close();
            }

            closeActiveSockets();

            shutdownExecutor();
        }
    }

    public int awaitListeningPort(
            long timeout,
            TimeUnit unit
    ) throws InterruptedException, ExecutionException, TimeoutException {
        return listeningPort.get(timeout, unit);
    }

    private void runSession(
            Socket socket
    ) {

        try {

            new ClientSession(
                    socket,
                    clientRegistry,
                    relayService
            ).run();

        } finally {

            activeSockets.remove(socket);
            connectionPermits.release();
        }
    }

    private void rejectConnection(
            Socket socket
    ) {

        try {
            socket.close();
        } catch (IOException failure) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Could not close rejected connection",
                    failure
            );
        }
    }

    public void stop() throws IOException {

        running = false;

        if (serverSocket != null
                && !serverSocket.isClosed()) {

            serverSocket.close();
        }

        closeActiveSockets();
    }

    private void closeActiveSockets() {

        for (Socket socket : activeSockets) {

            try {
                socket.close();

            } catch (IOException _) {
                // Socket is already being closed.
            }
        }
    }

    private void shutdownExecutor() {

        executor.shutdown();

        try {

            if (!executor.awaitTermination(
                    SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {

                executor.shutdownNow();
            }

        } catch (InterruptedException _) {

            executor.shutdownNow();

            Thread.currentThread()
                    .interrupt();
        }
    }
}
