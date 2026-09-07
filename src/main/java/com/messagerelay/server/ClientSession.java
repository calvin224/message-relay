package com.messagerelay.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.messagerelay.config.RelayLimits;
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
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class ClientSession implements Runnable {

    private final Socket socket;
    private final ClientRegistry clientRegistry;
    private final RelayService relayService;
    private final int registrationTimeoutMilliseconds;

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    private final BlockingQueue<Object> outboundMessages =
            new ArrayBlockingQueue<>(
                    RelayLimits.MAX_OUTBOUND_EVENTS
            );

    private String registeredClientId;

    public ClientSession(
            Socket socket,
            ClientRegistry clientRegistry,
            RelayService relayService
    ) {
        this(
                socket,
                clientRegistry,
                relayService,
                RelayLimits.REGISTRATION_TIMEOUT_MILLISECONDS
        );
    }

    public ClientSession(
            Socket socket,
            ClientRegistry clientRegistry,
            RelayService relayService,
            int registrationTimeoutMilliseconds
    ) {
        if (registrationTimeoutMilliseconds < 1) {
            throw new IllegalArgumentException(
                    "registrationTimeoutMilliseconds must be positive"
            );
        }

        this.socket = socket;
        this.clientRegistry = clientRegistry;
        this.relayService = relayService;
        this.registrationTimeoutMilliseconds =
                registrationTimeoutMilliseconds;
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
            socket.setSoTimeout(
                    registrationTimeoutMilliseconds
            );

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

        } catch (SocketTimeoutException e) {

            System.out.println(
                    "Client registration timed out"
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

        DeliveryEvent delivery =
                new DeliveryEvent(
                        MessageType.DELIVERY,
                        message.messageId(),
                        message.deliveryId(),
                        message.senderId(),
                        message.body()
                );

        return enqueueOutbound(delivery);
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

        RegistrationResult registrationResult =
                clientRegistry.register(
                        command.clientId(),
                        this
                );

        if (registrationResult
                == RegistrationResult.IDENTITY_IN_USE) {

            sendError(
                    ErrorCode.IDENTITY_IN_USE,
                    "Client identity is already connected"
            );

            return;
        }

        if (registrationResult
                == RegistrationResult.CAPACITY_REACHED) {

            sendError(
                    ErrorCode.CLIENT_LIMIT_REACHED,
                    "Server client identity limit reached"
            );

            return;
        }

        registeredClientId =
                command.clientId();

        clearRegistrationTimeout();

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
    ) {

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

    }

    private void handleAck(
            AckCommand command
    ) {

        if (isBlank(command.deliveryId())) {

            sendError(
                    ErrorCode.INVALID_MESSAGE,
                    "deliveryId is required"
            );

            return;
        }

        if (registeredClientId == null) {
            sendError(
                    ErrorCode.INVALID_MESSAGE,
                    "Connection must register before acknowledging"
            );

            return;
        }

        relayService.acknowledge(
                registeredClientId,
                command.deliveryId()
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
            if (context.getActiveSession() != this) {
                return;
            }

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
            if (context.getActiveSession() == this) {
                context.setReplayingPendingMessages(false);
            }

            context.getLock().unlock();
        }
    }

    private void clearRegistrationTimeout() {
        if (socket == null) {
            return;
        }

        try {
            socket.setSoTimeout(0);

        } catch (IOException e) {
            closeSocket();
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
