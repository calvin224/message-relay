package com.messagerelay.client;

import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.RegisterCommand;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

public class RelayClient {

    public static void main(String[] args) throws Exception {

        String clientId =
                args.length > 0
                        ? args[0]
                        : "alice";

        FrameCodec frameCodec =
                new FrameCodec();

        ProtocolCodec protocolCodec =
                new ProtocolCodec();

        try (
                Socket socket =
                        new Socket(
                                "localhost",
                                9000
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
            RegisterCommand command =
                    new RegisterCommand(
                            MessageType.REGISTER,
                            clientId
                    );

            frameCodec.writeFrame(
                    output,
                    protocolCodec.encode(command)
            );

            System.out.println(
                    frameCodec.readFrame(input)
            );

            /*
             * Keep the connection alive temporarily
             * so we can test multiple active clients.
             */
            Thread.sleep(60_000);
        }
    }
}