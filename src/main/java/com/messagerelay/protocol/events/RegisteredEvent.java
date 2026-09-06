package com.messagerelay.protocol.events;

import com.messagerelay.protocol.types.MessageType;

public record RegisteredEvent(
        MessageType type,
        String clientId
) {
}