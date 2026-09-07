package com.messagerelay.protocol.events;

import com.messagerelay.protocol.types.MessageType;

public record SendResultEvent(
        MessageType type,
        String messageId,
        boolean accepted,
        String reason
) {
}