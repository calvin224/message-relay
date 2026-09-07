package com.messagerelay.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.events.DeliveryEvent;
import com.messagerelay.protocol.events.ErrorEvent;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.events.SendResultEvent;
import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.service.RelayService;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class ClientSession implements Runnable {

    private static final int MAX_OUTBOUND_MESSAGES = 128;

    private final Socket socket;
    private final ClientRegistry clientRegistry;
    private final RelayService relayService;

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    private final BlockingQueue<Object> outboundMessages =
            new ArrayBlockingQueue<>(
                    MAX_OUTBOUND_MESSAGES
            );

    private String registeredClientId;

    public ClientSession(
            Socket socket,
            ClientRegistry clientRegistry,
            RelayService relayService
    ) {
        this.socket = socket;
        this.clientRegistry = clientRegistry;
        this.relayService = relayService;
    }

    @Override
    public void run() {

        Thread writerThread = null;

        try (
                socket;
                DataInputStream input =
                        new DataInputStream(
                                socket.getInputStream()
                        );
                DataOutputStream output =
                        new DataOutputStream(
                                socket.getOutputStream()
                        )
        ) {

            writerThread =
                    Thread.ofVirtual().start(
                            () -> writeLoop(output)
                    );

            while (!socket.isClosed()) {

                String json =
                        frameCodec.readFrame(input);

                handleFrame(json);
            }

        } catch (EOFException e) {

            System.out.println(
                    "Client disconnected: "
                            + registeredClientId
            );

        } catch (IOException e) {

            System.out.println(
                    "Client connection error: "
                            + e.getMessage()
            );

        } finally {

            if (writerThread != null) {
                writerThread.interrupt();
            }

            if (registeredClientId != null) {
                clientRegistry.disconnect(
                        registeredClientId,
                        this
                );
            }
        }
    }

    private void handleFrame(
            String json
    ) {

        MessageType type;

        try {
            type =
                    protocolCodec.decodeType(
                            json
                    );

        } catch (JsonProcessingException e) {

            sendError(
                    ErrorCode.MALFORMED_MESSAGE,
                    "Malformed JSON message"
            );

            return;

        } catch (IllegalArgumentException e) {

            sendError(
                    ErrorCode.INVALID_MESSAGE_TYPE,
                    e.getMessage()
            );

            return;
        }

        try {

            switch (type) {

                case REGISTER -> {
                    RegisterCommand command =
                            protocolCodec.decodeRegister(
                                    json
                            );

                    handleRegister(command);
                }

                case SEND -> {
                    SendCommand command =
                            protocolCodec.decodeSend(
                                    json
                            );

                    handleSend(command);
                }

                case ACK -> {
                    AckCommand command =
                            protocolCodec.decodeAck(
                                    json
                            );

                    handleAck(command);
                }

                default -> sendError(
                        ErrorCode.INVALID_MESSAGE_TYPE,
                        "Unsupported message type: "
                                + type
                );
            }

        } catch (JsonProcessingException e) {

            sendError(
                    ErrorCode.MALFORMED_MESSAGE,
                    "Malformed "
                            + type
                            + " message"
            );
        }
    }

    public boolean enqueueOutbound(
            Object message
    ) {

        boolean queued =
                outboundMessages.offer(message);

        if (!queued) {
            closeSocket();
        }

        return queued;
    }

    public boolean deliver(
            RelayMessage message
    ) {
        return enqueueOutbound(createDelivery(message));
    }

    private DeliveryEvent createDelivery(
            RelayMessage message
    ) {
        return new DeliveryEvent(
                MessageType.DELIVERY,
                message.messageId(),
                message.senderId(),
                message.body()
        );
    }

    private void writeLoop(
            DataOutputStream output
    ) {

        try {

            while (!Thread.currentThread()
                    .isInterrupted()) {

                Object message =
                        outboundMessages.take();

                frameCodec.writeFrame(
                        output,
                        protocolCodec.encode(message)
                );
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

        } catch (IOException e) {

            closeSocket();
        }
    }

    private void handleRegister(
            RegisterCommand command
    ) {

        if (isBlank(command.clientId())) {

            sendError(
                    ErrorCode.INVALID_MESSAGE,
                    "clientId is required"
            );

            return;
        }

        if (registeredClientId != null) {

            sendError(
                    ErrorCode.ALREADY_REGISTERED,
                    "This connection is already registered"
            );

            return;
        }

        boolean registered =
                clientRegistry.register(
                        command.clientId(),
                        this
                );

        if (!registered) {

            sendError(
                    ErrorCode.IDENTITY_IN_USE,
                    "Client identity is already connected"
            );

            return;
        }

        registeredClientId =
                command.clientId();

        System.out.println(
                "Registered client: "
                        + registeredClientId
        );

        RegisteredEvent response =
                new RegisteredEvent(
                        MessageType.REGISTERED,
                        registeredClientId
                );

        enqueueOutbound(response);

        deliverPendingMessages();
    }

    private void handleSend(
            SendCommand command
    ) throws JsonProcessingException {

        if (isBlank(command.messageId())) {

            sendError(
                    ErrorCode.INVALID_MESSAGE,
                    "messageId is required"
            );

            return;
        }

        if (isBlank(command.recipientId())) {

            sendError(
                    ErrorCode.INVALID_MESSAGE,
                    "recipientId is required"
            );

            return;
        }

        if (command.body() == null) {

            sendError(
                    ErrorCode.INVALID_MESSAGE,
                    "body is required"
            );

            return;
        }

        if (registeredClientId == null) {

            SendResultEvent response =
                    new SendResultEvent(
                            MessageType.SEND_RESULT,
                            command.messageId(),
                            false,
                            "Connection must register before sending"
                    );

            enqueueOutbound(response);

            return;
        }

        RelayMessage message =
                new RelayMessage(
                        command.messageId(),
                        registeredClientId,
                        command.recipientId(),
                        command.body()
                );

        String deliveryJson =
                protocolCodec.encode(createDelivery(message));

        try {
            frameCodec.validateFrame(deliveryJson);
        } catch (IOException e) {
            enqueueOutbound(new SendResultEvent(
                    MessageType.SEND_RESULT,
                    command.messageId(),
                    false,
                    "Delivery frame exceeds maximum size"
            ));
            return;
        }

        SendResult result =
                relayService.send(message);

        SendResultEvent response =
                new SendResultEvent(
                        MessageType.SEND_RESULT,
                        result.messageId(),
                        result.accepted(),
                        result.reason()
                );

        enqueueOutbound(response);

        if (result.accepted()) {
            deliverToOnlineRecipient(message);
        }
    }

    private void handleAck(
            AckCommand command
    ) {

        if (isBlank(command.messageId())) {

            sendError(
                    ErrorCode.INVALID_MESSAGE,
                    "messageId is required"
            );

            return;
        }

        if (registeredClientId == null) {
            return;
        }

        relayService.acknowledge(
                registeredClientId,
                command.messageId()
        );
    }

    private void deliverPendingMessages() {

        if (registeredClientId == null) {
            return;
        }

        ClientContext context =
                clientRegistry.getClient(
                        registeredClientId
                );

        if (context == null) {
            return;
        }

        context.getLock().lock();

        try {

            for (RelayMessage message :
                    context.getMailbox()
                            .getPendingMessages()) {

                boolean queued =
                        deliver(message);

                if (!queued) {
                    return;
                }
            }

        } finally {
            context.getLock().unlock();
        }
    }

    private void deliverToOnlineRecipient(
            RelayMessage message
    ) {

        ClientContext recipient =
                clientRegistry.getClient(
                        message.recipientId()
                );

        if (recipient == null) {
            return;
        }

        recipient.getLock().lock();

        try {

            ClientSession recipientSession =
                    recipient.getActiveSession();

            if (recipientSession != null) {
                recipientSession.deliver(
                        message
                );
            }

        } finally {
            recipient.getLock().unlock();
        }
    }

    private void sendError(
            ErrorCode code,
            String message
    ) {

        ErrorEvent error =
                new ErrorEvent(
                        MessageType.ERROR,
                        code,
                        message
                );

        enqueueOutbound(error);
    }

    private boolean isBlank(
            String value
    ) {
        return value == null
                || value.isBlank();
    }

    private void closeSocket() {

        if (socket == null
                || socket.isClosed()) {

            return;
        }

        try {
            socket.close();

        } catch (IOException ignored) {
            // Socket is already being closed.
        }
    }
}
