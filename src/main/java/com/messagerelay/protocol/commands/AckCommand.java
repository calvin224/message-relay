package com.messagerelay.protocol.commands;

import com.messagerelay.protocol.types.MessageType;

public record AckCommand(
        MessageType type,
        String messageId
) {
}