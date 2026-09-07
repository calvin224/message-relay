package com.messagerelay.unit.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;

import static com.messagerelay.support.TestUtils.getMailboxSize;
import static com.messagerelay.support.TestUtils.registerRecipient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayServiceTest {

    @Test
    void given_known_recipient_when_sending_message_then_message_is_accepted() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry
                );

        registerRecipient(clientRegistry, relayService, "bob");

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
    void given_pending_message_when_sending_duplicate_message_id_then_duplicate_is_rejected() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry
                );

        registerRecipient(clientRegistry, relayService, "bob");

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
                getMailboxSize(clientRegistry, "bob")
        );
    }

    @Test
    void given_full_recipient_mailbox_when_sending_message_then_message_is_rejected() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry
                );

        registerRecipient(clientRegistry, relayService, "bob");

        /*
         * Fill Bob's bounded mailbox.
         */
        for (int i = 0; i < 100; i++) {

            RelayMessage message =
                    new RelayMessage(
                            "msg-" + i,
                            "alice",
                            "bob",
                            "message-" + i
                    );

            SendResult result =
                    relayService.send(message);

            assertTrue(
                    result.accepted()
            );
        }

        assertEquals(
                100,
                getMailboxSize(clientRegistry, "bob")
        );

        /*
         * The next message exceeds the mailbox limit.
         */
        RelayMessage overflowMessage =
                new RelayMessage(
                        "msg-overflow",
                        "alice",
                        "bob",
                        "too many messages"
                );

        SendResult result =
                relayService.send(
                        overflowMessage
                );

        assertFalse(
                result.accepted()
        );

        assertEquals(
                "msg-overflow",
                result.messageId()
        );

        assertEquals(
                "Recipient mailbox is full",
                result.reason()
        );

        /*
         * The rejected message was not stored.
         */
        assertEquals(
                100,
                getMailboxSize(clientRegistry, "bob")
        );
    }

    @Test
    void given_message_for_bob_when_different_recipient_acknowledges_then_message_remains_pending() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry
                );

        registerRecipient(
                clientRegistry,
                relayService,
                "bob"
        );

        registerRecipient(
                clientRegistry,
                relayService,
                "charlie"
        );

        RelayMessage message =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "hello bob"
                );

        SendResult result =
                relayService.send(message);

        assertTrue(
                result.accepted()
        );

        boolean acknowledged =
                relayService.acknowledge(
                        "charlie",
                        "msg-1"
                );

        assertFalse(
                acknowledged
        );

        assertEquals(
                1,
                getMailboxSize(
                        clientRegistry,
                        "bob"
                )
        );
    }

    @Test
    void given_message_already_acknowledged_when_acknowledged_again_then_second_ack_is_ignored() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry
                );

        registerRecipient(
                clientRegistry,
                relayService,
                "bob"
        );

        RelayMessage message =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "hello bob"
                );

        SendResult result =
                relayService.send(message);

        assertTrue(
                result.accepted()
        );

        boolean firstAck =
                relayService.acknowledge(
                        "bob",
                        "msg-1"
                );

        boolean secondAck =
                relayService.acknowledge(
                        "bob",
                        "msg-1"
                );

        assertTrue(
                firstAck
        );

        assertFalse(
                secondAck
        );

        assertEquals(
                0,
                getMailboxSize(
                        clientRegistry,
                        "bob"
                )
        );
    }
}