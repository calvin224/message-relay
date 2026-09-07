package com.messagerelay.protocol.events;

import com.messagerelay.protocol.types.MessageType;

public record DeliveryEvent(
        MessageType type,
        String messageId,
        String senderId,
        String body
) {
}