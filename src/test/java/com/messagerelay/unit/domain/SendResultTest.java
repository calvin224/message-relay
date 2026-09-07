package com.messagerelay.unit.domain;

import com.messagerelay.domain.SendResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SendResultTest {

    @Test
    void createsRejectedResult() {
        SendResult result =
                SendResult.rejected(
                        "msg-1",
                        "Mailbox full"
                );

        assertEquals(
                "msg-1",
                result.messageId()
        );

        assertFalse(
                result.accepted()
        );

        assertEquals(
                "Mailbox full",
                result.reason()
        );
    }
}