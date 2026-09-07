package com.messagerelay.protocol.commands;

import com.messagerelay.protocol.types.MessageType;

public record SendCommand(
        MessageType type,
        String messageId,
        String recipientId,
        String body
) {
}