package com.messagerelay.integration.server;

import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.events.ErrorEvent;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.events.SendResultEvent;
import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;
import com.messagerelay.server.RegistrationResult;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.startSession;
import static com.messagerelay.support.TestUtils.writeCommand;
import static com.messagerelay.support.TestUtils.writeJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RelayProtocolFailureIntegrationTest {

    // Connection lifecycle: a connection cannot replace its identity with a second REGISTER.
    @Test
    @Timeout(3)
    void given_registered_connection_when_registering_again_then_already_registered_is_returned()
            throws Exception {
        ClientRegistry registry =
                new ClientRegistry();

        try (SessionHarness harness = open(registry)) {
            register(harness, "alice");

            writeCommand(
                    harness.output(),
                    new RegisterCommand(
                            MessageType.REGISTER,
                            "bob"
                    )
            );

            assertError(
                    harness.input(),
                    ErrorCode.ALREADY_REGISTERED
            );
        }
    }

    // Core requirement 1: a second live connection cannot claim an active identity.
    @Test
    @Timeout(3)
    void given_active_identity_when_second_connection_registers_then_identity_in_use_is_returned()
            throws Exception {
        ClientRegistry registry =
                new ClientRegistry();
        RelayService relayService =
                new RelayService(registry);
        ClientSession owner =
                new ClientSession(
                        null,
                        registry,
                        relayService
                );

        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register("alice", owner)
        );

        try (SessionHarness harness =
                     open(registry, relayService)) {
            writeCommand(
                    harness.output(),
                    new RegisterCommand(
                            MessageType.REGISTER,
                            "alice"
                    )
            );

            assertError(
                    harness.input(),
                    ErrorCode.IDENTITY_IN_USE
            );
        }
    }

    // Resource bound: a new identity receives an explicit error after registry capacity is reached.
    @Test
    @Timeout(3)
    void given_identity_capacity_reached_when_registering_new_identity_then_limit_error_is_returned()
            throws Exception {
        ClientRegistry registry =
                new ClientRegistry(1);

        registry.getOrCreateClient("alice");

        try (SessionHarness harness = open(registry)) {
            writeCommand(
                    harness.output(),
                    new RegisterCommand(
                            MessageType.REGISTER,
                            "bob"
                    )
            );

            assertError(
                    harness.input(),
                    ErrorCode.CLIENT_LIMIT_REACHED
            );
        }
    }

    // Error handling: an addressed send to an unknown identity is rejected explicitly.
    @Test
    @Timeout(3)
    void given_unknown_recipient_when_registered_client_sends_then_send_is_rejected()
            throws Exception {
        ClientRegistry registry =
                new ClientRegistry();

        try (SessionHarness harness = open(registry)) {
            register(harness, "alice");

            writeCommand(
                    harness.output(),
                    new SendCommand(
                            MessageType.SEND,
                            "msg-1",
                            "missing",
                            "hello"
                    )
            );

            SendResultEvent result =
                    readEvent(
                            harness.input(),
                            SendResultEvent.class
                    );

            assertFalse(result.accepted());
            assertEquals(
                    "Unknown recipient",
                    result.reason()
            );
        }
    }

    // Error handling: server-only event types cannot be used as client commands.
    @Test
    @Timeout(3)
    void given_server_event_type_when_client_sends_it_then_invalid_type_is_returned()
            throws Exception {
        ClientRegistry registry =
                new ClientRegistry();

        try (SessionHarness harness = open(registry)) {
            writeJson(
                    harness.output(),
                    "{\"type\":\"DELIVERY\"}"
            );

            assertError(
                    harness.input(),
                    ErrorCode.INVALID_MESSAGE_TYPE
            );
        }
    }

    // Protocol lifecycle: ACK ownership cannot be established before registration.
    @Test
    @Timeout(3)
    void given_unregistered_connection_when_acknowledging_then_invalid_message_is_returned()
            throws Exception {
        ClientRegistry registry =
                new ClientRegistry();

        try (SessionHarness harness = open(registry)) {
            writeCommand(
                    harness.output(),
                    new AckCommand(
                            MessageType.ACK,
                            "delivery-1"
                    )
            );

            assertError(
                    harness.input(),
                    ErrorCode.INVALID_MESSAGE
            );
        }
    }

    private SessionHarness open(
            ClientRegistry registry
    ) throws Exception {
        return open(
                registry,
                new RelayService(registry)
        );
    }

    private SessionHarness open(
            ClientRegistry registry,
            RelayService relayService
    ) throws Exception {
        try (ServerSocket listener =
                     new ServerSocket(0)) {
            Socket client =
                    new Socket(
                            "localhost",
                            listener.getLocalPort()
                    );
            Socket server = listener.accept();

            client.setSoTimeout(2_000);

            return new SessionHarness(
                    client,
                    new DataInputStream(
                            client.getInputStream()
                    ),
                    new DataOutputStream(
                            client.getOutputStream()
                    ),
                    startSession(
                            server,
                            registry,
                            relayService
                    )
            );
        }
    }

    private void register(
            SessionHarness harness,
            String clientId
    ) throws Exception {
        writeCommand(
                harness.output(),
                new RegisterCommand(
                        MessageType.REGISTER,
                        clientId
                )
        );

        RegisteredEvent event =
                readEvent(
                        harness.input(),
                        RegisteredEvent.class
                );

        assertEquals(clientId, event.clientId());
    }

    private void assertError(
            DataInputStream input,
            ErrorCode expectedCode
    ) throws Exception {
        ErrorEvent error =
                readEvent(
                        input,
                        ErrorEvent.class
                );

        assertEquals(MessageType.ERROR, error.type());
        assertEquals(expectedCode, error.code());
    }

    private record SessionHarness(
            Socket socket,
            DataInputStream input,
            DataOutputStream output,
            Thread sessionThread
    ) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            socket.close();
            sessionThread.join(2_000);
            assertFalse(sessionThread.isAlive());
        }
    }
}
