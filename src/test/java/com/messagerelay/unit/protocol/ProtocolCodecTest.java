package com.messagerelay.unit.protocol;

import com.messagerelay.protocol.ProtocolCodec;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.types.MessageType;
import org.junit.jupiter.api.Test;

import static com.messagerelay.support.TestUtils.readResource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolCodecTest {

    private final ProtocolCodec protocolCodec =
            new ProtocolCodec();

    // Protocol schema: registration commands serialize and deserialize symmetrically.
    @Test
    void given_register_command_when_encoding_and_decoding_then_original_command_is_returned() throws Exception {
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

    // Protocol schema: documented registration JSON maps to the command model.
    @Test
    void given_register_json_when_decoding_register_command_then_command_fields_match() throws Exception {
        String json = readResource("protocol/register.json");

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

    // Protocol dispatch: the type field selects the addressed-send operation.
    @Test
    void given_send_json_when_decoding_message_type_then_send_type_is_returned() throws Exception {
        String json = readResource("protocol/send.json");

        MessageType type =
                protocolCodec.decodeType(json);

        assertEquals(
                MessageType.SEND,
                type
        );
    }

    // Protocol schema: sender-provided ID, recipient, and body survive decoding.
    @Test
    void given_send_json_when_decoding_send_command_then_command_fields_match() throws Exception {
        String json = readResource("protocol/send.json");

        SendCommand command =
                protocolCodec.decodeSend(json);

        assertEquals(
                MessageType.SEND,
                command.type()
        );

        assertEquals(
                "msg-1",
                command.messageId()
        );

        assertEquals(
                "bob",
                command.recipientId()
        );

        assertEquals(
                "hello",
                command.body()
        );
    }
}
