package com.messagerelay.integration.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagerelay.protocol.FrameCodec;
import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.events.SendResultEvent;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSessionIntegrationTest {

    private final FrameCodec frameCodec =
            new FrameCodec();

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Test
    @Timeout(3)
    void registeredClientCanSendMessage() throws Exception {

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
                        new RelayService();

                Thread sessionThread =
                        Thread.ofVirtual().start(
                                new ClientSession(
                                        serverSocketConnection,
                                        clientRegistry,
                                        relayService
                                )
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

                RegisterCommand register =
                        new RegisterCommand(
                                MessageType.REGISTER,
                                "alice"
                        );

                frameCodec.writeFrame(
                        output,
                        protocolCodec.encode(register)
                );

                String registerResponseJson =
                        frameCodec.readFrame(input);

                RegisteredEvent registered =
                        objectMapper.readValue(
                                registerResponseJson,
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

                SendCommand send =
                        new SendCommand(
                                MessageType.SEND,
                                "msg-1",
                                "bob",
                                "hello"
                        );

                frameCodec.writeFrame(
                        output,
                        protocolCodec.encode(send)
                );

                String sendResponseJson =
                        frameCodec.readFrame(input);

                SendResultEvent result =
                        objectMapper.readValue(
                                sendResponseJson,
                                SendResultEvent.class
                        );

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
}