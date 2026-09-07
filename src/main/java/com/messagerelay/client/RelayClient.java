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
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class RelayClient {

    private static final String DEFAULT_HOST =
            "localhost";

    private static final int DEFAULT_PORT =
            9_000;

    private RelayClient() {
    }

    public static void main(
            String[] args
    ) throws Exception {
        if (args.length < 1 || args.length > 3) {
            printUsage();
            return;
        }

        String clientId = args[0];
        String host = args.length > 1
                ? args[1]
                : DEFAULT_HOST;
        int port = args.length > 2
                ? Integer.parseInt(args[2])
                : DEFAULT_PORT;

        FrameCodec frameCodec =
                new FrameCodec();
        ProtocolCodec protocolCodec =
                new ProtocolCodec();

        try (
                Socket socket = new Socket(host, port);
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
                                        System.in,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {
            write(
                    output,
                    frameCodec,
                    protocolCodec,
                    new RegisterCommand(
                            MessageType.REGISTER,
                            clientId
                    )
            );

            Thread reader = Thread.ofVirtual().start(
                    () -> readEvents(
                            socket,
                            input,
                            frameCodec
                    )
            );

            printCommands();
            runConsole(
                    console,
                    output,
                    frameCodec,
                    protocolCodec
            );

            socket.close();
            reader.join();
        }
    }

    private static void runConsole(
            BufferedReader console,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws IOException {
        String line;

        while ((line = console.readLine()) != null) {
            String trimmed = line.trim();

            if (trimmed.equalsIgnoreCase("quit")) {
                return;
            }

            if (trimmed.startsWith("send ")) {
                sendMessage(
                        trimmed,
                        output,
                        frameCodec,
                        protocolCodec
                );
            } else if (trimmed.startsWith("ack ")) {
                acknowledge(
                        trimmed,
                        output,
                        frameCodec,
                        protocolCodec
                );
            } else if (!trimmed.isEmpty()) {
                printCommands();
            }
        }
    }

    private static void sendMessage(
            String line,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws IOException {
        String[] parts = line.split("\\s+", 4);

        if (parts.length != 4) {
            System.out.println(
                    "Usage: send <recipientId> <messageId> <body>"
            );
            return;
        }

        write(
                output,
                frameCodec,
                protocolCodec,
                new SendCommand(
                        MessageType.SEND,
                        parts[2],
                        parts[1],
                        parts[3]
                )
        );
    }

    private static void acknowledge(
            String line,
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec
    ) throws IOException {
        String[] parts = line.split("\\s+", 2);

        if (parts.length != 2) {
            System.out.println(
                    "Usage: ack <deliveryId>"
            );
            return;
        }

        write(
                output,
                frameCodec,
                protocolCodec,
                new AckCommand(
                        MessageType.ACK,
                        parts[1]
                )
        );
    }

    private static void write(
            DataOutputStream output,
            FrameCodec frameCodec,
            ProtocolCodec protocolCodec,
            Object command
    ) throws IOException {
        frameCodec.writeFrame(
                output,
                protocolCodec.encode(command)
        );
    }

    private static void readEvents(
            Socket socket,
            DataInputStream input,
            FrameCodec frameCodec
    ) {
        try {
            while (!socket.isClosed()) {
                System.out.println(
                        frameCodec.readFrame(input)
                );
            }

        } catch (EOFException e) {
            System.out.println("Server closed the connection");

        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.err.println(
                        "Connection error: " + e.getMessage()
                );
            }
        }
    }

    private static void printUsage() {
        System.out.println(
                "Usage: RelayClient <clientId> [host] [port]"
        );
    }

    private static void printCommands() {
        System.out.println(
                "Commands: send <recipientId> <messageId> <body> | "
                        + "ack <deliveryId> | quit"
        );
    }
}
