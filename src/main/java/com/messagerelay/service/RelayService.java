package com.messagerelay.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.repository.RepositoryException;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RelayService {

    private final ClientRegistry clientRegistry;

    private final RelayMessageRepository messageRepository;

    private final Set<String> pendingMessageIds =
            ConcurrentHashMap.newKeySet();

    public RelayService(
            ClientRegistry clientRegistry
    ) {
        this(
                clientRegistry,
                new RelayMessageRepository() {

                    @Override
                    public void save(
                            RelayMessage message
                    ) {
                        // In-memory core mode.
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
                }
        );
    }

    public RelayService(
            ClientRegistry clientRegistry,
            RelayMessageRepository messageRepository
    ) {

        this.clientRegistry =
                clientRegistry;

        this.messageRepository =
                messageRepository;
    }

    public void recoverPendingMessages() {

        List<RelayMessage> pendingMessages =
                messageRepository.findAllPending();

        for (RelayMessage message :
                pendingMessages) {

            boolean reserved =
                    pendingMessageIds.add(
                            message.messageId()
                    );

            if (!reserved) {

                throw new IllegalStateException(
                        "Duplicate pending message ID during recovery: "
                                + message.messageId()
                );
            }

            ClientContext recipient =
                    clientRegistry.getOrCreateClient(
                            message.recipientId()
                    );

            recipient.getLock().lock();

            try {

                if (recipient.getMailbox()
                        .isFull()) {

                    throw new IllegalStateException(
                            "Recovered mailbox exceeds limit for recipient: "
                                    + message.recipientId()
                    );
                }

                boolean stored =
                        recipient.getMailbox()
                                .add(message);

                if (!stored) {

                    throw new IllegalStateException(
                            "Failed to recover message: "
                                    + message.messageId()
                    );
                }

            } finally {

                recipient.getLock()
                        .unlock();
            }
        }
    }

    public SendResult send(
            RelayMessage message
    ) {

        ClientContext recipient =
                clientRegistry.getClient(
                        message.recipientId()
                );

        if (recipient == null) {

            return SendResult.rejected(
                    message.messageId(),
                    "Unknown recipient"
            );
        }

        boolean reserved =
                pendingMessageIds.add(
                        message.messageId()
                );

        if (!reserved) {

            return SendResult.rejected(
                    message.messageId(),
                    "Duplicate message ID"
            );
        }

        recipient.getLock().lock();

        try {

            if (recipient.getMailbox()
                    .isFull()) {

                pendingMessageIds.remove(
                        message.messageId()
                );

                return SendResult.rejected(
                        message.messageId(),
                        "Recipient mailbox is full"
                );
            }

            boolean stored =
                    recipient.getMailbox()
                            .add(message);

            if (!stored) {

                pendingMessageIds.remove(
                        message.messageId()
                );

                return SendResult.rejected(
                        message.messageId(),
                        "Recipient mailbox is full"
                );
            }

            try {

                /*
                 * ACCEPTED is only returned after
                 * durable persistence succeeds.
                 */
                messageRepository.save(
                        message
                );

            } catch (RepositoryException e) {

                /*
                 * Persistence failed, so roll back
                 * the in-memory message.
                 */
                recipient.getMailbox()
                        .acknowledge(
                                message.messageId()
                        );

                pendingMessageIds.remove(
                        message.messageId()
                );

                return SendResult.rejected(
                        message.messageId(),
                        "Message storage unavailable"
                );
            }

            return SendResult.accepted(
                    message.messageId()
            );

        } finally {

            recipient.getLock()
                    .unlock();
        }
    }

    public boolean acknowledge(
            String recipientId,
            String messageId
    ) {

        ClientContext recipient =
                clientRegistry.getClient(
                        recipientId
                );

        if (recipient == null) {
            return false;
        }

        recipient.getLock().lock();

        try {

            boolean pending =
                    recipient.getMailbox()
                            .getPendingMessages()
                            .stream()
                            .anyMatch(
                                    message ->
                                            message.messageId()
                                                    .equals(messageId)
                            );

            /*
             * Wrong recipient, stale ACK or
             * repeated ACK.
             */
            if (!pending) {
                return false;
            }

            try {

                /*
                 * Remove durable state first.
                 *
                 * If storage fails, leave the
                 * in-memory copy available for
                 * redelivery.
                 */
                boolean deleted =
                        messageRepository.delete(
                                recipientId,
                                messageId
                        );

                if (!deleted) {
                    return false;
                }

            } catch (RepositoryException e) {

                return false;
            }

            boolean acknowledged =
                    recipient.getMailbox()
                            .acknowledge(
                                    messageId
                            );

            if (acknowledged) {

                pendingMessageIds.remove(
                        messageId
                );
            }

            return acknowledged;

        } finally {

            recipient.getLock()
                    .unlock();
        }
    }
}