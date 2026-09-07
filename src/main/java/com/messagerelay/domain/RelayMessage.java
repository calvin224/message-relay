package com.messagerelay.domain;

import java.util.UUID;

public record RelayMessage(
        String messageId,
        String deliveryId,
        String senderId,
        String recipientId,
        String body
) {

    public RelayMessage(
            String messageId,
            String senderId,
            String recipientId,
            String body
    ) {
        this(
                messageId,
                UUID.randomUUID().toString(),
                senderId,
                recipientId,
                body
        );
    }
}
