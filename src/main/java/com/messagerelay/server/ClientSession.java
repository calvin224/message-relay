package com.messagerelay.server;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
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

public class ClientSession implements Runnable {

    private final Socket socket;
    private final ClientRegistry clientRegistry;
    private final RelayService relayService;

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

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
            while (!socket.isClosed()) {

                String json =
                        frameCodec.readFrame(input);

                MessageType type =
                        protocolCodec.decodeType(json);

                switch (type) {

                    case REGISTER -> {
                        RegisterCommand command =
                                protocolCodec.decodeRegister(json);

                        handleRegister(
                                command,
                                output
                        );
                    }

                    case SEND -> {
                        SendCommand command =
                                protocolCodec.decodeSend(json);

                        handleSend(
                                command,
                                output
                        );
                    }

                    default -> {
                        sendError(
                                output,
                                ErrorCode.INVALID_MESSAGE_TYPE,
                                "Unsupported message type: " + type
                        );
                    }
                }
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

            if (registeredClientId != null) {
                clientRegistry.disconnect(
                        registeredClientId,
                        this
                );
            }
        }
    }

    private void handleRegister(
            RegisterCommand command,
            DataOutputStream output
    ) throws IOException {

        if (registeredClientId != null) {

            sendError(
                    output,
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
                    output,
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

        frameCodec.writeFrame(
                output,
                protocolCodec.encode(response)
        );
    }

    private void handleSend(
            SendCommand command,
            DataOutputStream output
    ) throws IOException {

        if (registeredClientId == null) {

            SendResultEvent response =
                    new SendResultEvent(
                            MessageType.SEND_RESULT,
                            command.messageId(),
                            false,
                            "Connection must register before sending"
                    );

            frameCodec.writeFrame(
                    output,
                    protocolCodec.encode(response)
            );

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

        frameCodec.writeFrame(
                output,
                protocolCodec.encode(response)
        );
    }

    private void sendError(
            DataOutputStream output,
            ErrorCode code,
            String message
    ) throws IOException {

        ErrorEvent error =
                new ErrorEvent(
                        MessageType.ERROR,
                        code,
                        message
                );

        frameCodec.writeFrame(
                output,
                protocolCodec.encode(error)
        );
    }
}