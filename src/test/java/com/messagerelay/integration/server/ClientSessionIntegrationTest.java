package com.messagerelay.integration.server;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.events.DeliveryEvent;
import com.messagerelay.protocol.events.ErrorEvent;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.events.SendResultEvent;
import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static com.messagerelay.support.TestUtils.getMailboxSize;
import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.registerRecipient;
import static com.messagerelay.support.TestUtils.startSession;
import static com.messagerelay.support.TestUtils.waitForClientDisconnected;
import static com.messagerelay.support.TestUtils.waitForMailboxSize;
import static com.messagerelay.support.TestUtils.writeCommand;
import static com.messagerelay.support.TestUtils.writeJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSessionIntegrationTest {

    @Test
    @Timeout(5)
    void given_full_registry_when_new_identity_is_rejected_then_existing_identity_can_reconnect()
            throws Exception {
        ClientRegistry registry = new ClientRegistry();
        RelayService service = new RelayService(registry);

        for (int index = 0; index < 100; index++) {
            registerRecipient(registry, service, "client-" + index);
        }

        ClientSession originalSession = registry.getClient("client-0").getActiveSession();
        registry.disconnect("client-0", originalSession);
        assertTrue(service.send(new RelayMessage("msg-1", "client-1", "client-0", "offline")).accepted());

        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("localhost", listener.getLocalPort());
             Socket serverPeer = listener.accept()) {
            client.setSoTimeout(2_000);
            Thread sessionThread = startSession(serverPeer, registry, service);

            try {
                DataInputStream input = new DataInputStream(client.getInputStream());
                DataOutputStream output = new DataOutputStream(client.getOutputStream());

                writeCommand(output, new RegisterCommand(MessageType.REGISTER, "overflow"));
                ErrorEvent error = readEvent(input, ErrorEvent.class);
                assertEquals(MessageType.ERROR, error.type());
                assertEquals(ErrorCode.IDENTITY_LIMIT_REACHED, error.code());
                assertNull(registry.getClient("overflow"));

                writeCommand(output, new RegisterCommand(MessageType.REGISTER, "client-1"));
                assertEquals(ErrorCode.IDENTITY_IN_USE, readEvent(input, ErrorEvent.class).code());

                writeCommand(output, new RegisterCommand(MessageType.REGISTER, "client-0"));
                assertEquals("client-0", readEvent(input, RegisteredEvent.class).clientId());
                DeliveryEvent delivery = readEvent(input, DeliveryEvent.class);
                assertEquals("msg-1", delivery.messageId());
                assertEquals("offline", delivery.body());
                assertEquals(1, getMailboxSize(registry, "client-0"));

                writeCommand(output, new AckCommand(MessageType.ACK, "msg-1"));
                waitForMailboxSize(registry, "client-0", 0);
                assertEquals(0, getMailboxSize(registry, "client-0"));
            } finally {
                sessionThread.interrupt();
                sessionThread.join(2_000);
                assertFalse(sessionThread.isAlive());
            }
        }
    }

    @Test
    @Timeout(3)
    void given_registered_client_when_sending_message_then_message_is_accepted() throws Exception {

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

                registerRecipient(clientRegistry, relayService, "bob");

                Thread sessionThread =
                        startSession(serverSocketConnection, clientRegistry, relayService);

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

                writeCommand(output, register);

                RegisteredEvent registered =
                        readEvent(input, RegisteredEvent.class);

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

                writeCommand(output, send);

                SendResultEvent result =
                        readEvent(input, SendResultEvent.class);

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
    void given_unregistered_client_when_sending_message_then_message_is_rejected() throws Exception {

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
                        startSession(serverSocketConnection, clientRegistry, relayService);

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

                writeCommand(output, send);

                SendResultEvent result =
                        readEvent(input, SendResultEvent.class);

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
    void given_registered_clients_when_message_is_sent_and_acknowledged_then_message_is_delivered_and_removed()
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
                        startSession(aliceServerSocket, clientRegistry, relayService);

                Thread bobSessionThread =
                        startSession(bobServerSocket, clientRegistry, relayService);

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

                writeCommand(aliceOutput, aliceRegister);

                RegisteredEvent aliceRegistered =
                        readEvent(aliceInput, RegisteredEvent.class);

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

                writeCommand(bobOutput, bobRegister);

                RegisteredEvent bobRegistered =
                        readEvent(bobInput, RegisteredEvent.class);

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

                writeCommand(aliceOutput, send);

                SendResultEvent sendResult =
                        readEvent(aliceInput, SendResultEvent.class);

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
                        readEvent(bobInput, DeliveryEvent.class);

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

                writeCommand(bobOutput, ack);

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

    @Test
    @Timeout(7)
    void given_message_queued_for_offline_recipient_when_recipient_reconnects_then_message_is_delivered()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            ClientRegistry clientRegistry =
                    new ClientRegistry();

            RelayService relayService =
                    new RelayService(
                            clientRegistry
                    );

            /*
             * Bob connects once so the logical identity
             * and mailbox exist.
             */
            Socket bobSocket =
                    new Socket(
                            "localhost",
                            serverSocket.getLocalPort()
                    );

            Socket bobServerSocket =
                    serverSocket.accept();

            Thread firstBobSession =
                    startSession(bobServerSocket, clientRegistry, relayService);

            bobSocket.setSoTimeout(2_000);

            DataInputStream bobInput =
                    new DataInputStream(
                            bobSocket.getInputStream()
                    );

            DataOutputStream bobOutput =
                    new DataOutputStream(
                            bobSocket.getOutputStream()
                    );

            writeCommand(
                    bobOutput,
                    new RegisterCommand(MessageType.REGISTER, "bob")
            );

            RegisteredEvent bobRegistered =
                    readEvent(bobInput, RegisteredEvent.class);

            assertEquals(
                    "bob",
                    bobRegistered.clientId()
            );

            /*
             * Bob goes offline.
             */
            bobSocket.close();

            firstBobSession.join(2_000);

            assertFalse(
                    firstBobSession.isAlive()
            );

            waitForClientDisconnected(
                    clientRegistry,
                    "bob"
            );

            /*
             * Alice connects while Bob is offline.
             */
            Socket aliceSocket =
                    new Socket(
                            "localhost",
                            serverSocket.getLocalPort()
                    );

            Socket aliceServerSocket =
                    serverSocket.accept();

            Thread aliceSession =
                    startSession(aliceServerSocket, clientRegistry, relayService);

            aliceSocket.setSoTimeout(2_000);

            DataInputStream aliceInput =
                    new DataInputStream(
                            aliceSocket.getInputStream()
                    );

            DataOutputStream aliceOutput =
                    new DataOutputStream(
                            aliceSocket.getOutputStream()
                    );

            writeCommand(
                    aliceOutput,
                    new RegisterCommand(MessageType.REGISTER, "alice")
            );

            readEvent(aliceInput, RegisteredEvent.class);

            /*
             * Send while Bob has no active TCP session.
             */
            writeCommand(
                    aliceOutput,
                    new SendCommand(MessageType.SEND, "msg-offline-1", "bob", "message while offline")
            );

            SendResultEvent sendResult =
                    readEvent(aliceInput, SendResultEvent.class);

            assertTrue(
                    sendResult.accepted()
            );

            assertEquals(
                    1,
                    getMailboxSize(
                            clientRegistry,
                            "bob"
                    )
            );

            /*
             * Bob reconnects using the same logical ID.
             */
            Socket reconnectedBobSocket =
                    new Socket(
                            "localhost",
                            serverSocket.getLocalPort()
                    );

            Socket reconnectedBobServerSocket =
                    serverSocket.accept();

            Thread secondBobSession =
                    startSession(reconnectedBobServerSocket, clientRegistry, relayService);

            reconnectedBobSocket.setSoTimeout(
                    2_000
            );

            DataInputStream reconnectedBobInput =
                    new DataInputStream(
                            reconnectedBobSocket
                                    .getInputStream()
                    );

            DataOutputStream reconnectedBobOutput =
                    new DataOutputStream(
                            reconnectedBobSocket
                                    .getOutputStream()
                    );

            writeCommand(
                    reconnectedBobOutput,
                    new RegisterCommand(MessageType.REGISTER, "bob")
            );

            RegisteredEvent reconnected =
                    readEvent(reconnectedBobInput, RegisteredEvent.class);

            assertEquals(
                    "bob",
                    reconnected.clientId()
            );

            DeliveryEvent delivery =
                    readEvent(reconnectedBobInput, DeliveryEvent.class);

            assertEquals(
                    "msg-offline-1",
                    delivery.messageId()
            );

            assertEquals(
                    "alice",
                    delivery.senderId()
            );

            assertEquals(
                    "message while offline",
                    delivery.body()
            );

            /*
             * Receiving it still does not remove it.
             * Bob has not ACKed yet.
             */
            assertEquals(
                    1,
                    getMailboxSize(
                            clientRegistry,
                            "bob"
                    )
            );

            aliceSocket.close();
            reconnectedBobSocket.close();

            aliceSession.join(2_000);
            secondBobSession.join(2_000);

            assertFalse(
                    aliceSession.isAlive()
            );

            assertFalse(
                    secondBobSession.isAlive()
            );
        }
    }

    @Test
    @Timeout(7)
    void given_unacknowledged_message_when_recipient_reconnects_then_message_is_redelivered()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            ClientRegistry clientRegistry =
                    new ClientRegistry();

            RelayService relayService =
                    new RelayService(
                            clientRegistry
                    );

            /*
             * Connect Alice.
             */
            Socket aliceSocket =
                    new Socket(
                            "localhost",
                            serverSocket.getLocalPort()
                    );

            Socket aliceServerSocket =
                    serverSocket.accept();

            Thread aliceSession =
                    startSession(aliceServerSocket, clientRegistry, relayService);

            aliceSocket.setSoTimeout(2_000);

            DataInputStream aliceInput =
                    new DataInputStream(
                            aliceSocket.getInputStream()
                    );

            DataOutputStream aliceOutput =
                    new DataOutputStream(
                            aliceSocket.getOutputStream()
                    );

            writeCommand(
                    aliceOutput,
                    new RegisterCommand(MessageType.REGISTER, "alice")
            );

            readEvent(aliceInput, RegisteredEvent.class);

            /*
             * Connect Bob.
             */
            Socket bobSocket =
                    new Socket(
                            "localhost",
                            serverSocket.getLocalPort()
                    );

            Socket bobServerSocket =
                    serverSocket.accept();

            Thread firstBobSession =
                    startSession(bobServerSocket, clientRegistry, relayService);

            bobSocket.setSoTimeout(2_000);

            DataInputStream bobInput =
                    new DataInputStream(
                            bobSocket.getInputStream()
                    );

            DataOutputStream bobOutput =
                    new DataOutputStream(
                            bobSocket.getOutputStream()
                    );

            writeCommand(
                    bobOutput,
                    new RegisterCommand(MessageType.REGISTER, "bob")
            );

            readEvent(bobInput, RegisteredEvent.class);

            /*
             * Alice sends while Bob is online.
             */
            writeCommand(
                    aliceOutput,
                    new SendCommand(MessageType.SEND, "msg-redelivery-1", "bob", "deliver me again")
            );

            SendResultEvent sendResult =
                    readEvent(aliceInput, SendResultEvent.class);

            assertTrue(
                    sendResult.accepted()
            );

            DeliveryEvent firstDelivery =
                    readEvent(bobInput, DeliveryEvent.class);

            assertEquals(
                    "msg-redelivery-1",
                    firstDelivery.messageId()
            );

            /*
             * Bob received it but has NOT ACKed it.
             */
            assertEquals(
                    1,
                    getMailboxSize(
                            clientRegistry,
                            "bob"
                    )
            );

            bobSocket.close();

            firstBobSession.join(2_000);

            assertFalse(
                    firstBobSession.isAlive()
            );

            waitForClientDisconnected(
                    clientRegistry,
                    "bob"
            );

            /*
             * Bob reconnects.
             */
            Socket reconnectedBobSocket =
                    new Socket(
                            "localhost",
                            serverSocket.getLocalPort()
                    );

            Socket reconnectedBobServerSocket =
                    serverSocket.accept();

            Thread secondBobSession =
                    startSession(reconnectedBobServerSocket, clientRegistry, relayService);

            reconnectedBobSocket.setSoTimeout(
                    2_000
            );

            DataInputStream reconnectedBobInput =
                    new DataInputStream(
                            reconnectedBobSocket
                                    .getInputStream()
                    );

            DataOutputStream reconnectedBobOutput =
                    new DataOutputStream(
                            reconnectedBobSocket
                                    .getOutputStream()
                    );

            writeCommand(
                    reconnectedBobOutput,
                    new RegisterCommand(MessageType.REGISTER, "bob")
            );

            readEvent(reconnectedBobInput, RegisteredEvent.class);

            DeliveryEvent secondDelivery =
                    readEvent(reconnectedBobInput, DeliveryEvent.class);

            assertEquals(
                    firstDelivery.messageId(),
                    secondDelivery.messageId()
            );

            assertEquals(
                    "deliver me again",
                    secondDelivery.body()
            );

            /*
             * Still pending because Bob still
             * has not ACKed it.
             */
            assertEquals(
                    1,
                    getMailboxSize(
                            clientRegistry,
                            "bob"
                    )
            );

            aliceSocket.close();
            reconnectedBobSocket.close();

            aliceSession.join(2_000);
            secondBobSession.join(2_000);

            assertFalse(
                    aliceSession.isAlive()
            );

            assertFalse(
                    secondBobSession.isAlive()
            );
        }
    }

    @Test
    @Timeout(5)
    void given_connected_client_when_sending_malformed_message_then_error_is_returned_and_connection_remains_usable()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             Socket clientSocket =
                     new Socket(
                             "localhost",
                             serverSocket.getLocalPort()
                     )) {

            Socket serverConnection =
                    serverSocket.accept();

            ClientRegistry clientRegistry =
                    new ClientRegistry();

            RelayService relayService =
                    new RelayService(
                            clientRegistry
                    );

            Thread sessionThread =
                    startSession(serverConnection, clientRegistry, relayService);

            clientSocket.setSoTimeout(2_000);

            DataInputStream input =
                    new DataInputStream(
                            clientSocket.getInputStream()
                    );

            DataOutputStream output =
                    new DataOutputStream(
                            clientSocket.getOutputStream()
                    );

            /*
             * Send syntactically invalid JSON.
             */
            writeJson(output, "{broken-json");

            ErrorEvent error =
                    readEvent(input, ErrorEvent.class);

            assertEquals(
                    MessageType.ERROR,
                    error.type()
            );

            assertEquals(
                    ErrorCode.MALFORMED_MESSAGE,
                    error.code()
            );

            /*
             * Now send a valid REGISTER on the
             * exact same TCP connection.
             */
            RegisterCommand register =
                    new RegisterCommand(
                            MessageType.REGISTER,
                            "alice"
                    );

            writeCommand(output, register);

            RegisteredEvent registered =
                    readEvent(input, RegisteredEvent.class);

            assertEquals(
                    MessageType.REGISTERED,
                    registered.type()
            );

            assertEquals(
                    "alice",
                    registered.clientId()
            );

            clientSocket.close();

            sessionThread.join(2_000);

            assertFalse(
                    sessionThread.isAlive()
            );
        }
    }

    @Test
    @Timeout(5)
    void given_valid_json_with_missing_required_field_when_sent_then_invalid_message_error_is_returned_and_connection_remains_usable()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             Socket clientSocket =
                     new Socket(
                             "localhost",
                             serverSocket.getLocalPort()
                     )) {

            Socket serverConnection =
                    serverSocket.accept();

            ClientRegistry clientRegistry =
                    new ClientRegistry();

            RelayService relayService =
                    new RelayService(
                            clientRegistry
                    );

            Thread sessionThread =
                    startSession(
                            serverConnection,
                            clientRegistry,
                            relayService
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

            /*
             * Valid JSON, but clientId is missing.
             */
            writeJson(
                    output,
                    """
                    {
                      "type": "REGISTER"
                    }
                    """
            );

            ErrorEvent error =
                    readEvent(
                            input,
                            ErrorEvent.class
                    );

            assertEquals(
                    MessageType.ERROR,
                    error.type()
            );

            assertEquals(
                    ErrorCode.INVALID_MESSAGE,
                    error.code()
            );

            /*
             * Same TCP connection should still work.
             */
            writeCommand(
                    output,
                    new RegisterCommand(
                            MessageType.REGISTER,
                            "alice"
                    )
            );

            RegisteredEvent registered =
                    readEvent(
                            input,
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

            clientSocket.close();

            sessionThread.join(2_000);

            assertFalse(
                    sessionThread.isAlive()
            );
        }
    }

    @Test
    @Timeout(5)
    void given_registered_client_when_commands_have_invalid_required_fields_then_each_is_rejected()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             Socket clientSocket =
                     new Socket(
                             "localhost",
                             serverSocket.getLocalPort()
                     )) {

            Socket serverConnection =
                    serverSocket.accept();

            ClientRegistry clientRegistry =
                    new ClientRegistry();

            RelayService relayService =
                    new RelayService(
                            clientRegistry
                    );

            Thread sessionThread =
                    startSession(
                            serverConnection,
                            clientRegistry,
                            relayService
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

            /*
             * Register normally first so SEND and ACK
             * validation is exercised on an authenticated
             * logical client.
             */
            writeCommand(
                    output,
                    new RegisterCommand(
                            MessageType.REGISTER,
                            "alice"
                    )
            );

            readEvent(
                    input,
                    RegisteredEvent.class
            );

            /*
             * Missing messageId.
             */
            writeJson(
                    output,
                    """
                    {
                      "type": "SEND",
                      "recipientId": "bob",
                      "body": "hello"
                    }
                    """
            );

            ErrorEvent missingMessageId =
                    readEvent(
                            input,
                            ErrorEvent.class
                    );

            assertEquals(
                    ErrorCode.INVALID_MESSAGE,
                    missingMessageId.code()
            );

            /*
             * Blank messageId.
             *
             * This also covers the String.isBlank()
             * validation path rather than only null.
             */
            writeJson(
                    output,
                    """
                    {
                      "type": "SEND",
                      "messageId": "   ",
                      "recipientId": "bob",
                      "body": "hello"
                    }
                    """
            );

            ErrorEvent blankMessageId =
                    readEvent(
                            input,
                            ErrorEvent.class
                    );

            assertEquals(
                    ErrorCode.INVALID_MESSAGE,
                    blankMessageId.code()
            );

            /*
             * Missing recipientId.
             */
            writeJson(
                    output,
                    """
                    {
                      "type": "SEND",
                      "messageId": "msg-1",
                      "body": "hello"
                    }
                    """
            );

            ErrorEvent missingRecipientId =
                    readEvent(
                            input,
                            ErrorEvent.class
                    );

            assertEquals(
                    ErrorCode.INVALID_MESSAGE,
                    missingRecipientId.code()
            );

            /*
             * Missing body.
             */
            writeJson(
                    output,
                    """
                    {
                      "type": "SEND",
                      "messageId": "msg-1",
                      "recipientId": "bob"
                    }
                    """
            );

            ErrorEvent missingBody =
                    readEvent(
                            input,
                            ErrorEvent.class
                    );

            assertEquals(
                    ErrorCode.INVALID_MESSAGE,
                    missingBody.code()
            );

            /*
             * Missing ACK messageId.
             */
            writeJson(
                    output,
                    """
                    {
                      "type": "ACK"
                    }
                    """
            );

            ErrorEvent missingAckMessageId =
                    readEvent(
                            input,
                            ErrorEvent.class
                    );

            assertEquals(
                    ErrorCode.INVALID_MESSAGE,
                    missingAckMessageId.code()
            );

            clientSocket.close();

            sessionThread.join(2_000);

            assertFalse(
                    sessionThread.isAlive()
            );
        }
    }

}
