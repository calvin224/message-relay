package com.messagerelay.domain;

public record SendResult(
        String messageId,
        boolean accepted,
        String reason
) {

    public static SendResult accepted(
            String messageId
    ) {
        return new SendResult(
                messageId,
                true,
                null
        );
    }

    public static SendResult rejected(
            String messageId,
            String reason
    ) {
        return new SendResult(
                messageId,
                false,
                reason
        );
    }
}