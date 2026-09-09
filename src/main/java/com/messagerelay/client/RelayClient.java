package com.messagerelay.client;

import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.types.MessageType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

public class RelayClient {

    private static final String HOST =
            "localhost";

    private static final int PORT =
            9000;

    private static final System.Logger LOGGER =
            System.getLogger(RelayClient.class.getName());

    public static void main(String[] args)
            throws Exception {

        System.getProperties().putIfAbsent(
                "java.util.logging.SimpleFormatter.format",
                "%5$s%6$s%n"
        );

        if (args.length == 0) {

            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Usage: RelayClient <clientId> [host] [port]"
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
                                args.length > 1 ? args[1] : HOST,
                                args.length > 2 ? Integer.parseInt(args[2]) : PORT
                        );

                DataInputStream input =
                        new DataInputStream(
                                socket.getInputStream()
                        );

                DataOutputStream output =
                        new DataOutputStream(
                                socket.getOutputStream()
                        )
        ) {

            register(
                    clientId,
                    output,
                    frameCodec,
                    protocolCodec
            );

            printHelp();

            Thread readerThread =
                    Thread.ofVirtual()
                            .start(
                                    () -> readServerMessages(
                                            input,
                                            frameCodec
                                    )
                            );

            try {
                runConsole(socket, output, frameCodec, protocolCodec);
            } finally {
                readerThread.interrupt();
            }
        }
    }

    private static void runConsole(
            Socket socket,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws IOException {
        while (!socket.isClosed()) {
            String line = IO.readln("> ");

            if (line == null || !handleCommand(line.trim(), output, frameCodec, protocolCodec)) {
                break;
            }
        }
    }

    private static boolean handleCommand(
            String line,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws IOException {
        if (line.isEmpty()) {
            return true;
        }

        if (line.equalsIgnoreCase("quit")) {
            return false;
        }

        if (line.equalsIgnoreCase("help")) {
            printHelp();
        } else if (line.equals("send") || line.startsWith("send ")) {
            handleSend(line, output, frameCodec, protocolCodec);
        } else if (line.equals("ack") || line.startsWith("ack ")) {
            handleAck(line, output, frameCodec, protocolCodec);
        } else {
            LOGGER.log(System.Logger.Level.INFO, "Unknown command. Type 'help'.");
        }

        return true;
    }

    private static void register(
            String clientId,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws IOException {

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

        LOGGER.log(
                System.Logger.Level.INFO,
                "Connecting as: "
                        + clientId
        );
    }

    private static void handleSend(
            String line,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws IOException {

        String[] parts =
                line.split(
                        "\\s+",
                        4
                );

        if (parts.length < 4) {

            LOGGER.log(
                    System.Logger.Level.INFO,
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
    ) throws IOException {

        String[] parts =
                line.split(
                        "\\s+",
                        2
                );

        if (parts.length < 2) {

            LOGGER.log(
                    System.Logger.Level.INFO,
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

        LOGGER.log(
                System.Logger.Level.INFO,
                "ACK sent for: "
                        + parts[1]
        );
    }

    private static void send(
            Object command,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws IOException {

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

                LOGGER.log(
                        System.Logger.Level.INFO,
                        "< " + message
                );
            }

        } catch (EOFException _) {

            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Server closed the connection."
            );

        } catch (IOException e) {

            if (!Thread.currentThread()
                    .isInterrupted()) {

                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Connection closed: "
                                + e.getMessage()
                );
            }
        }
    }

    private static void printHelp() {

        LOGGER.log(
                System.Logger.Level.INFO,
                String.join(
                        System.lineSeparator(),
                        "Commands:",
                        "  send <recipientId> <messageId> <body>",
                        "  ack <messageId>",
                        "  help",
                        "  quit"
                )
        );

    }
}
