package com.messagerelay.protocol.events;

import com.messagerelay.protocol.types.ErrorCode;
import com.messagerelay.protocol.types.MessageType;

public record ErrorEvent(
        MessageType type,
        ErrorCode code,
        String message
) {
}