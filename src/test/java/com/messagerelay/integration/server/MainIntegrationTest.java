package com.messagerelay.integration.server;

import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.events.RegisteredEvent;
import com.messagerelay.protocol.types.MessageType;
import com.messagerelay.support.JavaProcess;
import com.messagerelay.support.ShutdownServerProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

import static com.messagerelay.support.TestUtils.readEvent;
import static com.messagerelay.support.TestUtils.writeCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MainIntegrationTest {

    @Test
    @Timeout(15)
    void given_running_main_when_jvm_exits_then_shutdown_hook_disconnects_active_client() throws Exception {
        try (JavaProcess server = new JavaProcess(ShutdownServerProcess.class, "0")) {
            String startup = server.awaitOutput("Message relay listening on port ");
            int port = Integer.parseInt(startup.substring(startup.lastIndexOf(' ') + 1));
            assertEquals("Message relay listening on port " + port, startup);

            try (Socket socket = new Socket("localhost", port);
                 DataInputStream input = new DataInputStream(socket.getInputStream());
                 DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                socket.setSoTimeout(5_000);
                writeCommand(output, new RegisterCommand(MessageType.REGISTER, "alice"));
                assertEquals("alice", readEvent(input, RegisteredEvent.class).clientId());
                assertEquals("Registered client: alice", server.awaitOutput("Registered client: alice"));

                server.sendLine("shutdown");

                server.awaitOutput("Shutting down message relay...");
                assertEquals(-1, input.read());
                server.awaitSuccessfulExit();
            }
        }
    }
}
