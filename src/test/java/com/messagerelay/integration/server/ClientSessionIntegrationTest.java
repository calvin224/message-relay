package com.messagerelay.integration.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.events.DeliveryEvent;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.events.SendResultEvent;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSessionIntegrationTest {

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Test
    @Timeout(3)
    void registeredClientCanSendMessage() throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            try (Socket clientSocket =
                         new Socket(
                                 "localhost",
                                 serverSocket.getLocalPort()
                         )) {

                Socket serverSocketConnection =
                        serverSocket.accept();

                ClientRegistry clientRegistry =
                        new ClientRegistry();

                RelayService relayService =
                        new RelayService(
                                clientRegistry
                        );

                /*
                 * Bob only needs to exist as a registered
                 * logical recipient for this test.
                 *
                 * The session is never run, so the null socket
                 * is not accessed.
                 */
                ClientSession bobSession =
                        new ClientSession(
                                null,
                                clientRegistry,
                                relayService
                        );

                clientRegistry.register(
                        "bob",
                        bobSession
                );

                Thread sessionThread =
                        Thread.ofVirtual().start(
                                new ClientSession(
                                        serverSocketConnection,
                                        clientRegistry,
                                        relayService
                                )
                        );

                clientSocket.setSoTimeout(2_000);

                DataInputStream input =
                        new DataInputStream(
                                clientSocket.getInputStream()
                        );

                DataOutputStream output =
                        new DataOutputStream(
                                clientSocket.getOutputStream()
                        );

                RegisterCommand register =
                        new RegisterCommand(
                                MessageType.REGISTER,
                                "alice"
                        );

                frameCodec.writeFrame(
                        output,
                        protocolCodec.encode(register)
                );

                String registerResponseJson =
                        frameCodec.readFrame(input);

                RegisteredEvent registered =
                        objectMapper.readValue(
                                registerResponseJson,
                                RegisteredEvent.class
                        );

                assertEquals(
                        MessageType.REGISTERED,
                        registered.type()
                );

                assertEquals(
                        "alice",
                        registered.clientId()
                );

                SendCommand send =
                        new SendCommand(
                                MessageType.SEND,
                                "msg-1",
                                "bob",
                                "hello"
                        );

                frameCodec.writeFrame(
                        output,
                        protocolCodec.encode(send)
                );

                String sendResponseJson =
                        frameCodec.readFrame(input);

                SendResultEvent result =
                        objectMapper.readValue(
                                sendResponseJson,
                                SendResultEvent.class
                        );

                assertEquals(
                        MessageType.SEND_RESULT,
                        result.type()
                );

                assertEquals(
                        "msg-1",
                        result.messageId()
                );

                assertTrue(
                        result.accepted()
                );

                clientSocket.close();

                sessionThread.join(2_000);

                assertFalse(
                        sessionThread.isAlive()
                );
            }
        }
    }

    @Test
    @Timeout(3)
    void unregisteredClientCannotSendMessage() throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            try (Socket clientSocket =
                         new Socket(
                                 "localhost",
                                 serverSocket.getLocalPort()
                         )) {

                Socket serverSocketConnection =
                        serverSocket.accept();

                ClientRegistry clientRegistry =
                        new ClientRegistry();

                RelayService relayService =
                        new RelayService(
                                clientRegistry
                        );

                Thread sessionThread =
                        Thread.ofVirtual().start(
                                new ClientSession(
                                        serverSocketConnection,
                                        clientRegistry,
                                        relayService
                                )
                        );

                clientSocket.setSoTimeout(2_000);

                DataInputStream input =
                        new DataInputStream(
                                clientSocket.getInputStream()
                        );

                DataOutputStream output =
                        new DataOutputStream(
                                clientSocket.getOutputStream()
                        );

                SendCommand send =
                        new SendCommand(
                                MessageType.SEND,
                                "msg-1",
                                "bob",
                                "hello"
                        );

                frameCodec.writeFrame(
                        output,
                        protocolCodec.encode(send)
                );

                String responseJson =
                        frameCodec.readFrame(input);

                SendResultEvent result =
                        objectMapper.readValue(
                                responseJson,
                                SendResultEvent.class
                        );

                assertEquals(
                        MessageType.SEND_RESULT,
                        result.type()
                );

                assertEquals(
                        "msg-1",
                        result.messageId()
                );

                assertFalse(
                        result.accepted()
                );

                assertEquals(
                        "Connection must register before sending",
                        result.reason()
                );

                clientSocket.close();

                sessionThread.join(2_000);

                assertFalse(
                        sessionThread.isAlive()
                );
            }
        }
    }

    @Test
    @Timeout(5)
    void messageIsDeliveredAndRemovedAfterAcknowledgement()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             Socket aliceSocket =
                     new Socket(
                             "localhost",
                             serverSocket.getLocalPort()
                     )) {

            Socket aliceServerSocket =
                    serverSocket.accept();

            Socket bobSocket =
                    new Socket(
                            "localhost",
                            serverSocket.getLocalPort()
                    );

            Socket bobServerSocket =
                    serverSocket.accept();

            try (
                    bobSocket;
                    aliceServerSocket;
                    bobServerSocket
            ) {

                ClientRegistry clientRegistry =
                        new ClientRegistry();

                RelayService relayService =
                        new RelayService(
                                clientRegistry
                        );

                Thread aliceSessionThread =
                        Thread.ofVirtual().start(
                                new ClientSession(
                                        aliceServerSocket,
                                        clientRegistry,
                                        relayService
                                )
                        );

                Thread bobSessionThread =
                        Thread.ofVirtual().start(
                                new ClientSession(
                                        bobServerSocket,
                                        clientRegistry,
                                        relayService
                                )
                        );

                aliceSocket.setSoTimeout(2_000);
                bobSocket.setSoTimeout(2_000);

                DataInputStream aliceInput =
                        new DataInputStream(
                                aliceSocket.getInputStream()
                        );

                DataOutputStream aliceOutput =
                        new DataOutputStream(
                                aliceSocket.getOutputStream()
                        );

                DataInputStream bobInput =
                        new DataInputStream(
                                bobSocket.getInputStream()
                        );

                DataOutputStream bobOutput =
                        new DataOutputStream(
                                bobSocket.getOutputStream()
                        );

                RegisterCommand aliceRegister =
                        new RegisterCommand(
                                MessageType.REGISTER,
                                "alice"
                        );

                frameCodec.writeFrame(
                        aliceOutput,
                        protocolCodec.encode(
                                aliceRegister
                        )
                );

                RegisteredEvent aliceRegistered =
                        objectMapper.readValue(
                                frameCodec.readFrame(
                                        aliceInput
                                ),
                                RegisteredEvent.class
                        );

                assertEquals(
                        MessageType.REGISTERED,
                        aliceRegistered.type()
                );

                assertEquals(
                        "alice",
                        aliceRegistered.clientId()
                );

                RegisterCommand bobRegister =
                        new RegisterCommand(
                                MessageType.REGISTER,
                                "bob"
                        );

                frameCodec.writeFrame(
                        bobOutput,
                        protocolCodec.encode(
                                bobRegister
                        )
                );

                RegisteredEvent bobRegistered =
                        objectMapper.readValue(
                                frameCodec.readFrame(
                                        bobInput
                                ),
                                RegisteredEvent.class
                        );

                assertEquals(
                        MessageType.REGISTERED,
                        bobRegistered.type()
                );

                assertEquals(
                        "bob",
                        bobRegistered.clientId()
                );

                SendCommand send =
                        new SendCommand(
                                MessageType.SEND,
                                "msg-1",
                                "bob",
                                "hello bob"
                        );

                frameCodec.writeFrame(
                        aliceOutput,
                        protocolCodec.encode(send)
                );

                SendResultEvent sendResult =
                        objectMapper.readValue(
                                frameCodec.readFrame(
                                        aliceInput
                                ),
                                SendResultEvent.class
                        );

                assertEquals(
                        MessageType.SEND_RESULT,
                        sendResult.type()
                );

                assertEquals(
                        "msg-1",
                        sendResult.messageId()
                );

                assertTrue(
                        sendResult.accepted()
                );

                DeliveryEvent delivery =
                        objectMapper.readValue(
                                frameCodec.readFrame(
                                        bobInput
                                ),
                                DeliveryEvent.class
                        );

                assertEquals(
                        MessageType.DELIVERY,
                        delivery.type()
                );

                assertEquals(
                        "msg-1",
                        delivery.messageId()
                );

                assertEquals(
                        "alice",
                        delivery.senderId()
                );

                assertEquals(
                        "hello bob",
                        delivery.body()
                );

                /*
                 * Delivery alone must NOT remove
                 * the message from the mailbox.
                 */
                assertEquals(
                        1,
                        getMailboxSize(
                                clientRegistry,
                                "bob"
                        )
                );

                AckCommand ack =
                        new AckCommand(
                                MessageType.ACK,
                                "msg-1"
                        );

                frameCodec.writeFrame(
                        bobOutput,
                        protocolCodec.encode(ack)
                );

                /*
                 * ACK processing happens on Bob's
                 * session thread, so wait briefly
                 * for the mailbox mutation.
                 */
                waitForMailboxSize(
                        clientRegistry,
                        "bob",
                        0
                );

                aliceSocket.close();
                bobSocket.close();

                aliceSessionThread.join(2_000);
                bobSessionThread.join(2_000);

                assertFalse(
                        aliceSessionThread.isAlive()
                );

                assertFalse(
                        bobSessionThread.isAlive()
                );
            }
        }
    }

    private int getMailboxSize(
            ClientRegistry clientRegistry,
            String clientId
    ) {

        ClientContext context =
                clientRegistry.getClient(clientId);

        context.getLock().lock();

        try {
            return context
                    .getMailbox()
                    .size();

        } finally {
            context.getLock().unlock();
        }
    }

    private void waitForMailboxSize(
            ClientRegistry clientRegistry,
            String clientId,
            int expectedSize
    ) throws Exception {

        long deadline =
                System.currentTimeMillis()
                        + 2_000;

        while (System.currentTimeMillis()
                < deadline) {

            if (getMailboxSize(
                    clientRegistry,
                    clientId
            ) == expectedSize) {

                return;
            }

            Thread.sleep(10);
        }

        throw new AssertionError(
                "Mailbox for client "
                        + clientId
                        + " did not reach size "
                        + expectedSize
        );
    }
}