package com.messagerelay.unit.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayServiceTest {

    @Test
    void acceptsMessageForKnownRecipient() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry
                );

        ClientSession bobSession =
                new ClientSession(
                        null,
                        clientRegistry,
                        relayService
                );

        clientRegistry.register(
                "bob",
                bobSession
        );

        RelayMessage message =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "hello"
                );

        SendResult result =
                relayService.send(message);

        assertTrue(
                result.accepted()
        );

        assertEquals(
                "msg-1",
                result.messageId()
        );

        assertNull(
                result.reason()
        );
    }

    @Test
    void rejectsDuplicatePendingMessageId() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry
                );

        ClientSession bobSession =
                new ClientSession(
                        null,
                        clientRegistry,
                        relayService
                );

        clientRegistry.register(
                "bob",
                bobSession
        );

        RelayMessage firstMessage =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "first"
                );

        RelayMessage duplicateMessage =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "duplicate"
                );

        SendResult firstResult =
                relayService.send(
                        firstMessage
                );

        SendResult duplicateResult =
                relayService.send(
                        duplicateMessage
                );

        assertTrue(
                firstResult.accepted()
        );

        assertFalse(
                duplicateResult.accepted()
        );

        assertEquals(
                "msg-1",
                duplicateResult.messageId()
        );

        assertEquals(
                "Duplicate message ID",
                duplicateResult.reason()
        );

        assertEquals(
                1,
                clientRegistry
                        .getClient("bob")
                        .getMailbox()
                        .size()
        );
    }
}