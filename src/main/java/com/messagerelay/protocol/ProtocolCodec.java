package com.messagerelay.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagerelay.protocol.commands.AckCommand;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.types.MessageType;

public class ProtocolCodec {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    public String encode(
            Object message
    ) throws JsonProcessingException {

        return objectMapper.writeValueAsString(
                message
        );
    }

    public MessageType decodeType(
            String json
    ) throws JsonProcessingException {

        JsonNode root =
                objectMapper.readTree(json);

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    "Message must be a JSON object"
            );
        }

        JsonNode typeNode =
                root.get("type");

        if (typeNode == null
                || !typeNode.isTextual()
                || typeNode.asText().isBlank()) {

            throw new IllegalArgumentException(
                    "Message type is required"
            );
        }

        try {
            return MessageType.valueOf(
                    typeNode.asText()
            );

        } catch (IllegalArgumentException e) {

            throw new IllegalArgumentException(
                    "Unknown message type: "
                            + typeNode.asText()
            );
        }
    }

    public RegisterCommand decodeRegister(
            String json
    ) throws JsonProcessingException {

        return objectMapper.readValue(
                json,
                RegisterCommand.class
        );
    }

    public SendCommand decodeSend(
            String json
    ) throws JsonProcessingException {

        return objectMapper.readValue(
                json,
                SendCommand.class
        );
    }

    public AckCommand decodeAck(
            String json
    ) throws JsonProcessingException {

        return objectMapper.readValue(
                json,
                AckCommand.class
        );
    }
}