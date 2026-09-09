package com.messagerelay.integration.client;

import com.messagerelay.client.RelayClient;
import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.events.DeliveryEvent;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.events.SendResultEvent;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.support.JavaProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.writeCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class RelayClientIntegrationTest {

    @Test
    void given_no_identity_when_client_starts_then_usage_is_printed_and_process_exits() throws Exception {
        try (JavaProcess client = new JavaProcess(RelayClient.class)) {
            assertTrue(client.awaitOutput("Usage: RelayClient")
                    .endsWith("Usage: RelayClient <clientId> [host] [port]"));
            client.awaitSuccessfulExit();
        }
    }

    @Test
    void given_console_commands_when_client_runs_then_frames_and_output_match_and_ack_is_explicit() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             JavaProcess client = startClient(listener);
             Socket connection = listener.accept();
             DataInputStream input = new DataInputStream(connection.getInputStream());
             DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
            connection.setSoTimeout(5_000);
            assertEquals(new RegisterCommand(MessageType.REGISTER, "alice"), readEvent(input, RegisterCommand.class));
            writeCommand(output, new RegisteredEvent(MessageType.REGISTERED, "alice"));
            client.awaitOutput("\"type\":\"REGISTERED\"");

            client.sendLine("  ");
            client.sendLine("HeLp");
            client.awaitOutput("Commands:");
            client.sendLine("unknown");
            client.awaitOutput("Unknown command. Type 'help'.");
            client.sendLine("send bob");
            client.awaitOutput("Usage: send <recipientId> <messageId> <body>");
            client.sendLine("send");
            client.awaitOutput("Usage: send <recipientId> <messageId> <body>");
            client.sendLine("ack");
            client.awaitOutput("Usage: ack <messageId>");

            client.sendLine("send bob msg-1 hello bob");
            assertEquals(new SendCommand(MessageType.SEND, "msg-1", "bob", "hello bob"),
                    readEvent(input, SendCommand.class));
            writeCommand(output, new SendResultEvent(MessageType.SEND_RESULT, "msg-1", true, null));
            client.awaitOutput("\"accepted\":true");
            writeCommand(output, new DeliveryEvent(MessageType.DELIVERY, "reply-1", "bob", "hello alice"));
            String deliveryOutput = client.awaitOutput("\"type\":\"DELIVERY\"");
            assertTrue(deliveryOutput.contains("hello alice"));

            connection.setSoTimeout(200);
            assertThrows(SocketTimeoutException.class, input::read);
            connection.setSoTimeout(5_000);

            client.sendLine("ack reply-1");
            assertEquals(new AckCommand(MessageType.ACK, "reply-1"), readEvent(input, AckCommand.class));
            client.awaitOutput("ACK sent for: reply-1");
            client.sendLine(" QuIt ");
            assertEquals(-1, input.read());
            client.awaitSuccessfulExit();
        }
    }

    @Test
    void given_console_eof_when_client_is_connected_then_socket_and_process_close() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             JavaProcess client = startClient(listener);
             Socket connection = listener.accept();
             DataInputStream input = new DataInputStream(connection.getInputStream())) {
            connection.setSoTimeout(5_000);
            readEvent(input, RegisterCommand.class);
            client.endInput();
            assertEquals(-1, input.read());
            client.awaitSuccessfulExit();
        }
    }

    @Test
    void given_server_eof_when_client_reads_then_disconnect_is_reported() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             JavaProcess client = startClient(listener);
             Socket connection = listener.accept();
             DataInputStream input = new DataInputStream(connection.getInputStream())) {
            connection.setSoTimeout(5_000);
            readEvent(input, RegisterCommand.class);
            connection.shutdownOutput();
            assertTrue(client.awaitOutput("Server closed")
                    .endsWith("Server closed the connection."));
            client.sendLine("quit");
            client.awaitSuccessfulExit();
        }
    }

    @Test
    void given_invalid_server_frame_when_client_reads_then_connection_error_is_reported() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             JavaProcess client = startClient(listener);
             Socket connection = listener.accept();
             DataInputStream input = new DataInputStream(connection.getInputStream());
             DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
            connection.setSoTimeout(5_000);
            readEvent(input, RegisterCommand.class);
            output.writeInt(0);
            output.flush();
            assertTrue(client.awaitOutput("Connection closed:")
                    .endsWith("Connection closed: Invalid frame length: 0"));
            client.sendLine("quit");
            client.awaitSuccessfulExit();
        }
    }

    private JavaProcess startClient(ServerSocket listener) throws Exception {
        listener.setSoTimeout(5_000);
        return new JavaProcess(RelayClient.class, "alice", "localhost", Integer.toString(listener.getLocalPort()));
    }
}
