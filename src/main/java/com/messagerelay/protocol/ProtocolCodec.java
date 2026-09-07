package com.messagerelay.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagerelay.protocol.commands.RegisterCommand;
import com.messagerelay.protocol.commands.SendCommand;
import com.messagerelay.protocol.types.MessageType;

public class ProtocolCodec {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String encode(Object message)
            throws JsonProcessingException {

        return objectMapper.writeValueAsString(message);
    }

    public MessageType decodeType(String json)
            throws JsonProcessingException {

        JsonNode root = objectMapper.readTree(json);

        return MessageType.valueOf(
                root.get("type").asText()
        );
    }

    public RegisterCommand decodeRegister(String json)
            throws JsonProcessingException {

        return objectMapper.readValue(
                json,
                RegisterCommand.class
        );
    }

    public SendCommand decodeSend(String json)
            throws JsonProcessingException {

        return objectMapper.readValue(
                json,
                SendCommand.class
        );
    }
}