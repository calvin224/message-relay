package com.messagerelay.unit.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayServiceTest {

    private final RelayService relayService =
            new RelayService();

    @Test
    void acceptsMessage() {
        RelayMessage message =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "hello"
                );

        SendResult result =
                relayService.send(message);

        assertTrue(result.accepted());

        assertEquals(
                "msg-1",
                result.messageId()
        );

        assertNull(result.reason());
    }
}