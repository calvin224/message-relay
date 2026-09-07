package com.messagerelay.unit.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;
import com.messagerelay.server.RegistrationResult;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayServiceDeliverySemanticsTest {

    // Stale-ACK behavior: an old delivery ID cannot remove a later reuse of a message ID.
    @Test
    void given_reused_message_id_when_stale_delivery_is_acknowledged_then_new_message_remains_pending() {
        ClientRegistry registry =
                new ClientRegistry();
        RelayService relayService =
                new RelayService(registry);

        registry.getOrCreateClient("bob");

        RelayMessage first =
                message("msg-1", "first");
        RelayMessage second =
                message("msg-1", "second");

        assertTrue(relayService.send(first).accepted());
        assertTrue(
                relayService.acknowledge(
                        "bob",
                        first.deliveryId()
                )
        );
        assertTrue(relayService.send(second).accepted());

        assertFalse(
                relayService.acknowledge(
                        "bob",
                        first.deliveryId()
                )
        );
        assertEquals(
                List.of(second),
                registry.getClient("bob")
                        .getMailbox()
                        .getPendingMessages()
        );
    }

    // FIFO bonus: concurrent accepted sends have the same order in the mailbox and live delivery queue.
    @Test
    @Timeout(5)
    void given_concurrent_sends_when_recipient_is_online_then_live_delivery_preserves_mailbox_order()
            throws Exception {
        ClientRegistry registry =
                new ClientRegistry();
        RelayService relayService =
                new RelayService(registry);
        RecordingSession recipientSession =
                new RecordingSession(
                        registry,
                        relayService
                );

        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(
                        "bob",
                        recipientSession
                )
        );

        finishReplay(registry, recipientSession);

        int messageCount = 40;
        CountDownLatch start =
                new CountDownLatch(1);
        List<Callable<SendResult>> sends =
                new ArrayList<>();

        for (int index = 0;
             index < messageCount;
             index++) {
            RelayMessage message =
                    message(
                            "msg-" + index,
                            "body-" + index
                    );

            sends.add(() -> {
                start.await();
                return relayService.send(message);
            });
        }

        try (var executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            var results = sends.stream()
                    .map(executor::submit)
                    .toList();

            start.countDown();

            for (var result : results) {
                assertTrue(result.get().accepted());
            }
        }

        List<String> mailboxOrder =
                registry.getClient("bob")
                        .getMailbox()
                        .getPendingMessages()
                        .stream()
                        .map(RelayMessage::deliveryId)
                        .toList();

        assertEquals(
                mailboxOrder,
                recipientSession.deliveredIds()
        );
    }

    // Resource bound: startup refuses more durable rows than it is configured to recover.
    @Test
    void given_excess_durable_messages_when_recovering_then_startup_fails_before_loading_all_rows() {
        List<RelayMessage> messages =
                List.of(
                        message("msg-1", "one"),
                        message("msg-2", "two"),
                        message("msg-3", "three")
                );
        RelayMessageRepository repository =
                new BoundedRecoveryRepository(messages);
        RelayService relayService =
                new RelayService(
                        new ClientRegistry(),
                        repository,
                        2
                );

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        relayService::recoverPendingMessages
                );

        assertEquals(
                "Recovered messages exceed the server limit",
                failure.getMessage()
        );
    }

    private RelayMessage message(
            String messageId,
            String body
    ) {
        return new RelayMessage(
                messageId,
                "alice",
                "bob",
                body
        );
    }

    private void finishReplay(
            ClientRegistry registry,
            ClientSession session
    ) {
        ClientContext context =
                registry.getClient("bob");

        context.getLock().lock();

        try {
            assertEquals(
                    session,
                    context.getActiveSession()
            );
            context.setReplayingPendingMessages(false);

        } finally {
            context.getLock().unlock();
        }
    }

    private static final class RecordingSession
            extends ClientSession {

        private final List<String> deliveredIds =
                new ArrayList<>();

        private RecordingSession(
                ClientRegistry registry,
                RelayService relayService
        ) {
            super(null, registry, relayService);
        }

        @Override
        public boolean deliver(
                RelayMessage message
        ) {
            deliveredIds.add(message.deliveryId());
            return true;
        }

        private List<String> deliveredIds() {
            return List.copyOf(deliveredIds);
        }
    }

    private record BoundedRecoveryRepository(
            List<RelayMessage> messages
    ) implements RelayMessageRepository {

        @Override
        public void save(
                RelayMessage message
        ) {
        }

        @Override
        public boolean delete(
                String recipientId,
                String deliveryId
        ) {
            return false;
        }

        @Override
        public List<RelayMessage> findAllPending() {
            return messages;
        }
    }
}
