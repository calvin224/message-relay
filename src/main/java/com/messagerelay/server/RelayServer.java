package com.messagerelay.server;

import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.events.ErrorEvent;
import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.service.RelayService;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class RelayServer {

    private static final int MAX_ACTIVE_CONNECTIONS =
            100;

    private static final int SHUTDOWN_TIMEOUT_SECONDS =
            2;

    private final int port;

    private final ClientRegistry clientRegistry =
            new ClientRegistry();

    private final RelayService relayService;

    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    private final Semaphore connectionPermits =
            new Semaphore(
                    MAX_ACTIVE_CONNECTIONS
            );

    private final Set<Socket> activeSockets =
            ConcurrentHashMap.newKeySet();

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    private volatile boolean running;

    private ServerSocket serverSocket;

    public RelayServer(
            int port
    ) {

        this.port = port;

        this.relayService =
                new RelayService(
                        clientRegistry
                );
    }

    public RelayServer(
            int port,
            RelayMessageRepository messageRepository
    ) {

        this.port = port;

        this.relayService =
                new RelayService(
                        clientRegistry,
                        messageRepository
                );
    }

    public void start()
            throws IOException {

        /*
         * Restore queued and unacknowledged
         * messages before accepting clients.
         */
        relayService.recoverPendingMessages();

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

                    if (!connectionPermits
                            .tryAcquire()) {

                        rejectConnection(socket);
                        continue;
                    }

                    activeSockets.add(socket);

                    System.out.println(
                            "Client connected: "
                                    + socket
                                    .getRemoteSocketAddress()
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

        try (
                socket;
                DataOutputStream output =
                        new DataOutputStream(
                                socket.getOutputStream()
                        )
        ) {

            ErrorEvent error =
                    new ErrorEvent(
                            MessageType.ERROR,
                            ErrorCode.CONNECTION_LIMIT_REACHED,
                            "Server connection limit reached"
                    );

            frameCodec.writeFrame(
                    output,
                    protocolCodec.encode(error)
            );

        } catch (IOException e) {

            /*
             * The connection is already being rejected.
             * If the client disappears before receiving
             * the error there is nothing else to do.
             */
        }
    }

    public void stop()
            throws IOException {

        running = false;

        if (serverSocket != null
                && !serverSocket.isClosed()) {

            serverSocket.close();
        }

        closeActiveSockets();
    }

    private void closeActiveSockets() {

        for (Socket socket :
                activeSockets) {

            try {

                socket.close();

            } catch (IOException ignored) {

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

        } catch (InterruptedException e) {

            executor.shutdownNow();

            Thread.currentThread()
                    .interrupt();
        }
    }
}