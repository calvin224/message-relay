package com.messagerelay.unit.domain;

import com.messagerelay.domain.SendResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SendResultTest {

    @Test
    void given_message_id_and_reason_when_creating_rejected_result_then_result_contains_rejection_details() {
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