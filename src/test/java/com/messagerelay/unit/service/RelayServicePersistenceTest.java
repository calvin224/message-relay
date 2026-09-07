package com.messagerelay.unit.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.repository.RepositoryException;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.messagerelay.support.TestUtils.getMailboxSize;
import static com.messagerelay.support.TestUtils.registerRecipient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RelayServicePersistenceTest {

    @Test
    void given_storage_failure_when_sending_message_then_message_is_rejected_and_not_kept_in_mailbox() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayMessageRepository repository =
                new RelayMessageRepository() {

                    @Override
                    public void save(
                            RelayMessage message
                    ) {
                        throw new RepositoryException(
                                "Database unavailable",
                                new RuntimeException()
                        );
                    }

                    @Override
                    public boolean delete(
                            String recipientId,
                            String messageId
                    ) {
                        return false;
                    }

                    @Override
                    public List<RelayMessage> findAllPending() {
                        return List.of();
                    }
                };

        RelayService relayService =
                new RelayService(
                        clientRegistry,
                        repository
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

        assertFalse(
                result.accepted()
        );

        assertEquals(
                "Message storage unavailable",
                result.reason()
        );

        assertEquals(
                0,
                getMailboxSize(
                        clientRegistry,
                        "bob"
                )
        );
    }

    @Test
    void given_delete_failure_when_acknowledging_message_then_message_remains_pending() {

        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayMessageRepository repository =
                new RelayMessageRepository() {

                    @Override
                    public void save(
                            RelayMessage message
                    ) {
                        // Simulate successful persistence.
                    }

                    @Override
                    public boolean delete(
                            String recipientId,
                            String messageId
                    ) {
                        throw new RepositoryException(
                                "Database unavailable",
                                new RuntimeException()
                        );
                    }

                    @Override
                    public List<RelayMessage> findAllPending() {
                        return List.of();
                    }
                };

        RelayService relayService =
                new RelayService(
                        clientRegistry,
                        repository
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

        relayService.send(message);

        boolean acknowledged =
                relayService.acknowledge(
                        "bob",
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
}