package com.messagerelay.domain;

public record RelayMessage(
        String messageId,
        String senderId,
        String recipientId,
        String body
) {
}