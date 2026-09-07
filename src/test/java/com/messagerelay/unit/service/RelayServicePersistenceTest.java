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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RelayServicePersistenceTest {

    // Persistence bonus: a message is never accepted when its durable write fails.
    @Test
    void given_storage_failure_when_sending_message_then_message_is_rejected_and_removed_from_memory() {
        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry,
                        failingSaveRepository()
                );

        clientRegistry.getOrCreateClient("bob");

        SendResult result =
                relayService.send(
                        new RelayMessage(
                                "msg-1",
                                "alice",
                                "bob",
                                "hello bob"
                        )
                );

        assertFalse(result.accepted());
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

    // Persistence bonus: failed durable deletion leaves the message available for redelivery.
    @Test
    void given_storage_failure_when_acknowledging_then_message_remains_available() {
        ClientRegistry clientRegistry =
                new ClientRegistry();

        RelayService relayService =
                new RelayService(
                        clientRegistry,
                        failingDeleteRepository()
                );

        clientRegistry.getOrCreateClient("bob");

        RelayMessage message =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "hello bob"
                );

        relayService.send(
                message
        );

        boolean acknowledged =
                relayService.acknowledge(
                        "bob",
                        message.deliveryId()
                );

        assertFalse(acknowledged);
        assertEquals(
                1,
                getMailboxSize(
                        clientRegistry,
                        "bob"
                )
        );
    }

    private RelayMessageRepository failingSaveRepository() {
        return new RelayMessageRepository() {
            @Override
            public void save(
                    RelayMessage message
            ) {
                throw new RepositoryException(
                        "Storage unavailable",
                        new IllegalStateException()
                );
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
                return List.of();
            }
        };
    }

    private RelayMessageRepository failingDeleteRepository() {
        return new RelayMessageRepository() {
            @Override
            public void save(
                    RelayMessage message
            ) {
            }

            @Override
            public boolean delete(
                    String recipientId,
                    String messageId
            ) {
                throw new RepositoryException(
                        "Storage unavailable",
                        new IllegalStateException()
                );
            }

            @Override
            public List<RelayMessage> findAllPending() {
                return List.of();
            }
        };
    }
}
