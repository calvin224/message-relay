package com.messagerelay.protocol.commands;

import com.messagerelay.protocol.types.MessageType;

public record RegisterCommand(
        MessageType type,
        String clientId
) {
}