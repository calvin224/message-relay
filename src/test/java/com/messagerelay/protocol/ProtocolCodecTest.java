package com.messagerelay.protocol;

import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.types.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolCodecTest {

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    @Test
    void encodesRegisterCommand() throws Exception {
        RegisterCommand command =
                new RegisterCommand(
                        MessageType.REGISTER,
                        "alice"
                );

        String json = protocolCodec.encode(command);

        RegisterCommand decoded =
                protocolCodec.decodeRegister(json);

        assertEquals(command, decoded);
    }

    @Test
    void decodesRegisterCommand() throws Exception {
        String json = """
                {
                  "type": "REGISTER",
                  "clientId": "alice"
                }
                """;

        RegisterCommand command =
                protocolCodec.decodeRegister(json);

        assertEquals(
                MessageType.REGISTER,
                command.type()
        );

        assertEquals(
                "alice",
                command.clientId()
        );
    }

    @Test
    void decodesMessageType() throws Exception {
        String json = """
            {
              "type": "SEND",
              "messageId": "msg-1",
              "recipientId": "bob",
              "body": "hello"
            }
            """;

        MessageType type =
                protocolCodec.decodeType(json);

        assertEquals(
                MessageType.SEND,
                type
        );
    }
}