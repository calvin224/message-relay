package com.messagerelay.server;

import com.messagerelay.config.RelayLimits;
import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.events.ErrorEvent;
import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.repository.TransientRelayMessageRepository;
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

    private final int port;

    private final int registrationTimeoutMilliseconds;

    private final ClientRegistry clientRegistry =
            new ClientRegistry();

    private final RelayService relayService;

    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    private final Semaphore connectionPermits =
            new Semaphore(
                    RelayLimits.MAX_ACTIVE_CONNECTIONS
            );

    private final Set<Socket> activeSockets =
            ConcurrentHashMap.newKeySet();

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    private volatile boolean running;

    private ServerSocket serverSocket;

    public RelayServer(int port) {
        this(
                port,
                new TransientRelayMessageRepository(),
                RelayLimits.REGISTRATION_TIMEOUT_MILLISECONDS
        );
    }

    public RelayServer(
            int port,
            int registrationTimeoutMilliseconds
    ) {
        this(
                port,
                new TransientRelayMessageRepository(),
                registrationTimeoutMilliseconds
        );
    }

    public RelayServer(
            int port,
            RelayMessageRepository messageRepository
    ) {
        this(
                port,
                messageRepository,
                RelayLimits.REGISTRATION_TIMEOUT_MILLISECONDS
        );
    }

    public RelayServer(
            int port,
            RelayMessageRepository messageRepository,
            int registrationTimeoutMilliseconds
    ) {
        if (registrationTimeoutMilliseconds < 1) {
            throw new IllegalArgumentException(
                    "registrationTimeoutMilliseconds must be positive"
            );
        }

        this.port = port;
        this.registrationTimeoutMilliseconds =
                registrationTimeoutMilliseconds;
        this.relayService = new RelayService(
                clientRegistry,
                messageRepository
        );
    }

    public void start() throws IOException {

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
                    relayService,
                    registrationTimeoutMilliseconds
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

            } catch (IOException ignored) {
                // Socket is already being closed.
            }
        }
    }

    private void shutdownExecutor() {

        executor.shutdown();

        try {

            if (!executor.awaitTermination(
                    RelayLimits.SHUTDOWN_TIMEOUT_SECONDS,
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
