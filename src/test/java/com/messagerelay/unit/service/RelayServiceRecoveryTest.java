package com.messagerelay.unit.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RelayServiceRecoveryTest {

    @Test
    void given_persisted_message_when_recovering_then_recipient_mailbox_is_restored() {

        RelayMessage persistedMessage =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "hello bob"
                );

        RelayMessageRepository repository =
                new RelayMessageRepository() {

                    @Override
                    public void save(
                            RelayMessage message
                    ) {
                        // Not used by this test.
                    }

                    @Override
                    public boolean delete(
                            String recipientId,
                            String messageId
                    ) {
                        return true;
                    }

                    @Override
                    public List<RelayMessage> findAllPending() {
                        return List.of(
                                persistedMessage
                        );
                    }
                };

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry,
                        repository
                );

        relayService.recoverPendingMessages();

        ClientContext bob =
                clientRegistry.getClient(
                        "bob"
                );

        assertNotNull(
                bob
        );

        assertEquals(
                1,
                bob.getMailbox()
                        .size()
        );

        assertEquals(
                persistedMessage,
                bob.getMailbox()
                        .getPendingMessages()
                        .getFirst()
        );
    }
}