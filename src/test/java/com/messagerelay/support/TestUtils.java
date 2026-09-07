package com.messagerelay.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;
import com.messagerelay.server.RelayServer;
import com.messagerelay.service.RelayService;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TestUtils {

    private static final FrameCodec FRAME_CODEC = new FrameCodec();
    private static final ProtocolCodec PROTOCOL_CODEC = new ProtocolCodec();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TestUtils() {
    }

    public static String readResource(String resourcePath) throws IOException {
        try (InputStream input = TestUtils.class.getResourceAsStream("/" + resourcePath)) {
            if (input == null) {
                throw new IOException("Test resource not found: " + resourcePath);
            }

            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void writeCommand(
            DataOutputStream output,
            Object command
    ) throws IOException {
        writeJson(output, PROTOCOL_CODEC.encode(command));
    }

    public static void writeJson(
            DataOutputStream output,
            String json
    ) throws IOException {
        FRAME_CODEC.writeFrame(output, json);
    }

    public static <Event> Event readEvent(
            DataInputStream input,
            Class<Event> eventType
    ) throws IOException {
        return OBJECT_MAPPER.readValue(FRAME_CODEC.readFrame(input), eventType);
    }

    public static void registerRecipient(
            ClientRegistry clientRegistry,
            RelayService relayService,
            String clientId
    ) {
        ClientSession session = new ClientSession(null, clientRegistry, relayService);

        assertTrue(
                clientRegistry.register(clientId, session),
                "Could not register test recipient: " + clientId
        );
    }

    public static Thread startSession(
            Socket socket,
            ClientRegistry clientRegistry,
            RelayService relayService
    ) {
        return Thread.ofVirtual().start(
                new ClientSession(socket, clientRegistry, relayService)
        );
    }

    public static Thread startServer(
            RelayServer relayServer,
            AtomicReference<Throwable> serverFailure
    ) {
        return Thread.ofVirtual().start(() -> {
            try {
                relayServer.start();
            } catch (Throwable failure) {
                serverFailure.set(failure);
            }
        });
    }

    public static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static void waitUntilListening(int port) throws InterruptedException {
        awaitCondition(
                () -> {
                    try (Socket ignored = new Socket("localhost", port)) {
                        return true;
                    } catch (IOException exception) {
                        return false;
                    }
                },
                "Relay server did not start in time"
        );
    }

    public static int getMailboxSize(
            ClientRegistry clientRegistry,
            String clientId
    ) {
        ClientContext context = clientRegistry.getClient(clientId);
        context.getLock().lock();

        try {
            return context.getMailbox().size();
        } finally {
            context.getLock().unlock();
        }
    }

    public static void waitForMailboxSize(
            ClientRegistry clientRegistry,
            String clientId,
            int expectedSize
    ) throws InterruptedException {
        awaitCondition(
                () -> getMailboxSize(clientRegistry, clientId) == expectedSize,
                "Mailbox for client " + clientId + " did not reach size " + expectedSize
        );
    }

    public static void waitForClientDisconnected(
            ClientRegistry clientRegistry,
            String clientId
    ) throws InterruptedException {
        awaitCondition(
                () -> {
                    ClientContext context = clientRegistry.getClient(clientId);
                    context.getLock().lock();

                    try {
                        return context.getActiveSession() == null;
                    } finally {
                        context.getLock().unlock();
                    }
                },
                "Client " + clientId + " did not disconnect"
        );
    }

    private static void awaitCondition(
            BooleanSupplier condition,
            String failureMessage
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            Thread.sleep(10);
        }

        throw new AssertionError(failureMessage);
    }
}