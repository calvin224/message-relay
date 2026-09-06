package com.messagerelay.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagerelay.protocol.commands.RegisterCommand;

/* "What does the JSON mean?" */
public class ProtocolCodec {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String encode(Object message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(message);
    }

    public RegisterCommand decodeRegister(
            String json
    ) throws JsonProcessingException {

        return objectMapper.readValue(
                json,
                RegisterCommand.class
        );
    }
}