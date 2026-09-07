package com.messagerelay.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.repository.RelayMessageRepository;
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
                    public void save(RelayMessage message) {
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

            return SendResult.accepted(
                    message.messageId()
            );

        } finally {
            recipient.getLock().unlock();
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

            boolean acknowledged =
                    recipient
                            .getMailbox()
                            .acknowledge(messageId);

            if (acknowledged) {

                pendingMessageIds.remove(
                        messageId
                );
            }

            return acknowledged;

        } finally {
            recipient.getLock().unlock();
        }
    }
}