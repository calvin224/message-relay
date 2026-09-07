package com.messagerelay.client;

import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.types.MessageType;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.net.Socket;

public class RelayClient {

    private static final String HOST =
            "localhost";

    private static final int PORT =
            9000;

    public static void main(String[] args)
            throws Exception {

        if (args.length == 0) {

            System.out.println(
                    "Usage: RelayClient <clientId>"
            );

            return;
        }

        String clientId =
                args[0];

        FrameCodec frameCodec =
                new FrameCodec();

        ProtocolCodec protocolCodec =
                new ProtocolCodec();

        try (
                Socket socket =
                        new Socket(
                                HOST,
                                PORT
                        );

                DataInputStream input =
                        new DataInputStream(
                                socket.getInputStream()
                        );

                DataOutputStream output =
                        new DataOutputStream(
                                socket.getOutputStream()
                        );

                BufferedReader console =
                        new BufferedReader(
                                new InputStreamReader(
                                        System.in
                                )
                        )
        ) {

            register(
                    clientId,
                    output,
                    frameCodec,
                    protocolCodec
            );

            Thread readerThread =
                    Thread.ofVirtual()
                            .start(
                                    () -> readServerMessages(
                                            input,
                                            frameCodec
                                    )
                            );

            printHelp();

            while (!socket.isClosed()) {

                System.out.print("> ");

                String line =
                        console.readLine();

                if (line == null) {
                    break;
                }

                line =
                        line.trim();

                if (line.isEmpty()) {
                    continue;
                }

                if (line.equalsIgnoreCase(
                        "quit"
                )) {
                    break;
                }

                if (line.equalsIgnoreCase(
                        "help"
                )) {

                    printHelp();
                    continue;
                }

                if (line.startsWith(
                        "send "
                )) {

                    handleSend(
                            line,
                            output,
                            frameCodec,
                            protocolCodec
                    );

                    continue;
                }

                if (line.startsWith(
                        "ack "
                )) {

                    handleAck(
                            line,
                            output,
                            frameCodec,
                            protocolCodec
                    );

                    continue;
                }

                System.out.println(
                        "Unknown command. Type 'help'."
                );
            }

            socket.close();

            readerThread.interrupt();
        }
    }

    private static void register(
            String clientId,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws Exception {

        RegisterCommand command =
                new RegisterCommand(
                        MessageType.REGISTER,
                        clientId
                );

        send(
                command,
                output,
                frameCodec,
                protocolCodec
        );

        System.out.println(
                "Connecting as: "
                        + clientId
        );
    }

    private static void handleSend(
            String line,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws Exception {

        String[] parts =
                line.split(
                        "\\s+",
                        4
                );

        if (parts.length < 4) {

            System.out.println(
                    "Usage: send <recipientId> <messageId> <body>"
            );

            return;
        }

        String recipientId =
                parts[1];

        String messageId =
                parts[2];

        String body =
                parts[3];

        SendCommand command =
                new SendCommand(
                        MessageType.SEND,
                        messageId,
                        recipientId,
                        body
                );

        send(
                command,
                output,
                frameCodec,
                protocolCodec
        );
    }

    private static void handleAck(
            String line,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws Exception {

        String[] parts =
                line.split(
                        "\\s+",
                        2
                );

        if (parts.length < 2
                || parts[1].isBlank()) {

            System.out.println(
                    "Usage: ack <messageId>"
            );

            return;
        }

        AckCommand command =
                new AckCommand(
                        MessageType.ACK,
                        parts[1]
                );

        send(
                command,
                output,
                frameCodec,
                protocolCodec
        );

        System.out.println(
                "ACK sent for: "
                        + parts[1]
        );
    }

    private static void send(
            Object command,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws Exception {

        String json =
                protocolCodec.encode(
                        command
                );

        frameCodec.writeFrame(
                output,
                json
        );
    }

    private static void readServerMessages(
            DataInputStream input,
            FrameCodec frameCodec
    ) {

        try {

            while (!Thread.currentThread()
                    .isInterrupted()) {

                String message =
                        frameCodec.readFrame(
                                input
                        );

                System.out.println();

                System.out.println(
                        "< " + message
                );

                System.out.print("> ");
            }

        } catch (EOFException e) {

            System.out.println();

            System.out.println(
                    "Server closed the connection."
            );

        } catch (Exception e) {

            if (!Thread.currentThread()
                    .isInterrupted()) {

                System.out.println();

                System.out.println(
                        "Connection closed: "
                                + e.getMessage()
                );
            }
        }
    }

    private static void printHelp() {

        System.out.println();

        System.out.println(
                "Commands:"
        );

        System.out.println(
                "  send <recipientId> <messageId> <body>"
        );

        System.out.println(
                "  ack <messageId>"
        );

        System.out.println(
                "  help"
        );

        System.out.println(
                "  quit"
        );

        System.out.println();
    }
}