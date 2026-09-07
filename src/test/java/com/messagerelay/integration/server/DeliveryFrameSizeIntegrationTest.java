package com.messagerelay.integration.server;

import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.events.DeliveryEvent;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.events.SendResultEvent;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static com.messagerelay.support.TestUtils.getMailboxSize;
import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.startSession;
import static com.messagerelay.support.TestUtils.waitForMailboxSize;
import static com.messagerelay.support.TestUtils.writeCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryFrameSizeIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "\u00e9", "\""})
    @Timeout(5)
    void given_oversized_delivery_when_sending_then_rejected_without_reserving_id_and_boundary_retry_is_delivered(
            String bodyCharacter
    ) throws Exception {
        int maximumFrameBytes = 64 * 1024;
        String senderId = "sender-" + "s".repeat(128);
        String messageId = "size-check";
        ProtocolCodec protocolCodec = new ProtocolCodec();

        int emptyDeliveryBytes = protocolCodec.encode(new DeliveryEvent(
                MessageType.DELIVERY, messageId, senderId, ""
        )).getBytes(StandardCharsets.UTF_8).length;
        int encodedCharacterBytes = protocolCodec.encode(new DeliveryEvent(
                MessageType.DELIVERY, messageId, senderId, bodyCharacter
        )).getBytes(StandardCharsets.UTF_8).length - emptyDeliveryBytes;
        int availableBodyBytes = maximumFrameBytes - emptyDeliveryBytes;
        String boundaryBody = bodyCharacter.repeat(availableBodyBytes / encodedCharacterBytes)
                + "x".repeat(availableBodyBytes % encodedCharacterBytes);

        SendCommand oversizedSend = new SendCommand(
                MessageType.SEND, messageId, "bob", boundaryBody + "x"
        );
        assertTrue(protocolCodec.encode(oversizedSend)
                .getBytes(StandardCharsets.UTF_8).length <= maximumFrameBytes);
        assertEquals(maximumFrameBytes + 1, protocolCodec.encode(new DeliveryEvent(
                MessageType.DELIVERY, messageId, senderId, oversizedSend.body()
        )).getBytes(StandardCharsets.UTF_8).length);

        ClientRegistry clientRegistry = new ClientRegistry();
        RelayService relayService = new RelayService(clientRegistry);

        try (ServerSocket listener = new ServerSocket(0);
             Socket senderSocket = new Socket("localhost", listener.getLocalPort());
             Socket senderConnection = listener.accept();
             Socket recipientSocket = new Socket("localhost", listener.getLocalPort());
             Socket recipientConnection = listener.accept()) {
            senderSocket.setSoTimeout(2_000);
            recipientSocket.setSoTimeout(2_000);

            Thread senderThread = startSession(senderConnection, clientRegistry, relayService);
            Thread recipientThread = startSession(recipientConnection, clientRegistry, relayService);

            try (DataInputStream senderInput = new DataInputStream(senderSocket.getInputStream());
                 DataOutputStream senderOutput = new DataOutputStream(senderSocket.getOutputStream());
                 DataInputStream recipientInput = new DataInputStream(recipientSocket.getInputStream());
                 DataOutputStream recipientOutput = new DataOutputStream(recipientSocket.getOutputStream())) {
                writeCommand(senderOutput, new RegisterCommand(MessageType.REGISTER, senderId));
                assertEquals(senderId, readEvent(senderInput, RegisteredEvent.class).clientId());
                writeCommand(recipientOutput, new RegisterCommand(MessageType.REGISTER, "bob"));
                assertEquals("bob", readEvent(recipientInput, RegisteredEvent.class).clientId());

                writeCommand(senderOutput, oversizedSend);

                SendResultEvent rejected = readEvent(senderInput, SendResultEvent.class);
                assertEquals(MessageType.SEND_RESULT, rejected.type());
                assertEquals(messageId, rejected.messageId());
                assertFalse(rejected.accepted());
                assertEquals("Delivery frame exceeds maximum size", rejected.reason());
                assertEquals(0, getMailboxSize(clientRegistry, "bob"));

                writeCommand(senderOutput, new SendCommand(
                        MessageType.SEND, messageId, "bob", boundaryBody
                ));

                SendResultEvent accepted = readEvent(senderInput, SendResultEvent.class);
                assertTrue(accepted.accepted());
                assertEquals(messageId, accepted.messageId());

                DeliveryEvent delivery = readEvent(recipientInput, DeliveryEvent.class);
                assertEquals(MessageType.DELIVERY, delivery.type());
                assertEquals(messageId, delivery.messageId());
                assertEquals(senderId, delivery.senderId());
                assertEquals(boundaryBody, delivery.body());
                assertEquals(maximumFrameBytes, protocolCodec.encode(delivery)
                        .getBytes(StandardCharsets.UTF_8).length);
                assertEquals(1, getMailboxSize(clientRegistry, "bob"));

                writeCommand(recipientOutput, new AckCommand(MessageType.ACK, messageId));
                waitForMailboxSize(clientRegistry, "bob", 0);
            } finally {
                senderConnection.close();
                recipientConnection.close();
                senderThread.join(2_000);
                recipientThread.join(2_000);
                assertFalse(senderThread.isAlive());
                assertFalse(recipientThread.isAlive());
            }
        }
    }
}
