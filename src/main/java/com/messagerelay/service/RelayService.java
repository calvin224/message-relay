package com.messagerelay.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;

public class RelayService {

    public SendResult send(
            RelayMessage message
    ) {
        return SendResult.accepted(
                message.messageId()
        );
    }
}