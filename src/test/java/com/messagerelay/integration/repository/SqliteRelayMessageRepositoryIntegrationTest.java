package com.messagerelay.integration.repository;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.repository.SqliteRelayMessageRepository;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRelayMessageRepositoryIntegrationTest {

    @TempDir
    private Path temporaryDirectory;

    // Persistence bonus: queued messages survive repository reopening in insertion order.
    @Test
    @Timeout(5)
    void given_pending_messages_when_repository_is_reopened_then_messages_remain_in_insertion_order() {
        Path databasePath =
                temporaryDirectory.resolve(
                        "relay.db"
                );

        RelayMessage firstMessage =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "first"
                );

        RelayMessage secondMessage =
                new RelayMessage(
                        "msg-2",
                        "charlie",
                        "bob",
                        "second"
                );

        RelayMessageRepository firstRepository =
                new SqliteRelayMessageRepository(
                        databasePath
                );

        firstRepository.save(firstMessage);
        firstRepository.save(secondMessage);

        RelayMessageRepository reopenedRepository =
                new SqliteRelayMessageRepository(
                        databasePath
                );

        assertEquals(
                List.of(
                        firstMessage,
                        secondMessage
                ),
                reopenedRepository.findAllPending()
        );

        assertFalse(
                reopenedRepository.delete(
                        "charlie",
                        firstMessage.deliveryId()
                )
        );

        assertTrue(
                reopenedRepository.delete(
                        "bob",
                        firstMessage.deliveryId()
                )
        );

        assertEquals(
                List.of(secondMessage),
                reopenedRepository.findAllPending()
        );
    }

    // Persistence bonus: a fresh service restores unacknowledged state and can ACK it.
    @Test
    @Timeout(5)
    void given_accepted_unacknowledged_message_when_service_restarts_then_message_is_recovered_and_can_be_acknowledged() {
        Path databasePath =
                temporaryDirectory.resolve(
                        "relay.db"
                );

        ClientRegistry firstRegistry =
                new ClientRegistry();

        firstRegistry.getOrCreateClient("bob");

        RelayService firstService =
                new RelayService(
                        firstRegistry,
                        new SqliteRelayMessageRepository(
                                databasePath
                        )
                );

        RelayMessage message =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "hello bob"
                );

        SendResult sendResult =
                firstService.send(message);

        assertTrue(sendResult.accepted());

        ClientRegistry restartedRegistry =
                new ClientRegistry();

        RelayMessageRepository restartedRepository =
                new SqliteRelayMessageRepository(
                        databasePath
                );

        RelayService restartedService =
                new RelayService(
                        restartedRegistry,
                        restartedRepository
                );

        restartedService.recoverPendingMessages();

        ClientContext recoveredRecipient =
                restartedRegistry.getClient("bob");

        assertNotNull(recoveredRecipient);
        assertEquals(
                List.of(message),
                recoveredRecipient.getMailbox()
                        .getPendingMessages()
        );

        assertTrue(
                restartedService.acknowledge(
                        "bob",
                        message.deliveryId()
                )
        );
        assertTrue(
                restartedRepository.findAllPending()
                        .isEmpty()
        );
    }
}
