package com.messagerelay.server;

import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.events.ErrorEvent;
import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.service.RelayService;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class RelayServer {

    private static final int MAX_ACTIVE_CONNECTIONS =
            100;

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

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

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

                    if (!connectionPermits
                            .tryAcquire()) {

                        rejectConnection(socket);
                        continue;
                    }

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

            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }

            executor.shutdown();
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
    }
}