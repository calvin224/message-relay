package com.messagerelay.service;

import com.messagerelay.config.RelayLimits;
import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.repository.RepositoryException;
import com.messagerelay.repository.TransientRelayMessageRepository;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RelayService {

    private final ClientRegistry clientRegistry;

    private final RelayMessageRepository messageRepository;

    private final int maxRecoveredMessages;

    private final Set<String> pendingMessageIds =
            ConcurrentHashMap.newKeySet();

    public RelayService(
            ClientRegistry clientRegistry
    ) {
        this(
                clientRegistry,
                new TransientRelayMessageRepository(),
                RelayLimits.MAX_TOTAL_PENDING_MESSAGES
        );
    }

    public RelayService(
            ClientRegistry clientRegistry,
            RelayMessageRepository messageRepository
    ) {
        this(
                clientRegistry,
                messageRepository,
                RelayLimits.MAX_TOTAL_PENDING_MESSAGES
        );
    }

    public RelayService(
            ClientRegistry clientRegistry,
            RelayMessageRepository messageRepository,
            int maxRecoveredMessages
    ) {
        if (maxRecoveredMessages < 1) {
            throw new IllegalArgumentException(
                    "maxRecoveredMessages must be positive"
            );
        }

        this.clientRegistry = clientRegistry;
        this.messageRepository = messageRepository;
        this.maxRecoveredMessages =
                maxRecoveredMessages;
    }

    public void recoverPendingMessages() {
        List<RelayMessage> pendingMessages =
                messageRepository.findPending(
                        maxRecoveredMessages + 1
                );

        if (pendingMessages.size()
                > maxRecoveredMessages) {
            throw new IllegalStateException(
                    "Recovered messages exceed the server limit"
            );
        }

        for (RelayMessage message : pendingMessages) {
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
                boolean stored =
                        recipient.getMailbox()
                                .add(message);

                if (!stored) {
                    throw new IllegalStateException(
                            "Recovered mailbox exceeds limit for recipient: "
                                    + message.recipientId()
                    );
                }

            } finally {
                recipient.getLock().unlock();
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
                messageRepository.save(message);

            } catch (RepositoryException e) {
                recipient.getMailbox()
                        .acknowledge(
                                message.deliveryId()
                        );

                pendingMessageIds.remove(
                        message.messageId()
                );

                return SendResult.rejected(
                        message.messageId(),
                        "Message storage unavailable"
                );
            }

            ClientSession activeSession =
                    recipient.getActiveSession();

            if (activeSession != null
                    && !recipient.isReplayingPendingMessages()) {
                activeSession.deliver(message);
            }

            return SendResult.accepted(
                    message.messageId()
            );

        } finally {
            recipient.getLock().unlock();
        }
    }

    public boolean acknowledge(
            String recipientId,
            String deliveryId
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
            RelayMessage pendingMessage =
                    findByDeliveryId(
                            recipient,
                            deliveryId
                    );

            if (pendingMessage == null) {
                return false;
            }

            try {
                boolean deleted =
                        messageRepository.delete(
                                recipientId,
                                deliveryId
                        );

                if (!deleted) {
                    return false;
                }

            } catch (RepositoryException e) {
                return false;
            }

            boolean acknowledged =
                    recipient
                            .getMailbox()
                            .acknowledge(deliveryId);

            if (acknowledged) {
                pendingMessageIds.remove(pendingMessage.messageId());
            }

            return acknowledged;

        } finally {
            recipient.getLock().unlock();
        }
    }

    private RelayMessage findByDeliveryId(
            ClientContext recipient,
            String deliveryId
    ) {
        for (RelayMessage message :
                recipient.getMailbox()
                        .getPendingMessages()) {

            if (message.deliveryId()
                    .equals(deliveryId)) {
                return message;
            }
        }

        return null;
    }
}
