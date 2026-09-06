package com.messagerelay.server;

import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.events.ErrorEvent;
import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.events.RegisteredEvent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

public class ClientSession implements Runnable {

    private final Socket socket;
    private final ClientRegistry clientRegistry;

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    private String registeredClientId;

    public ClientSession(
            Socket socket,
            ClientRegistry clientRegistry
    ) {
        this.socket = socket;
        this.clientRegistry = clientRegistry;
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

                RegisterCommand command =
                        protocolCodec.decodeRegister(json);

                handleRegister(
                        command,
                        output
                );
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