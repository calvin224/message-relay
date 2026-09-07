package com.messagerelay.service;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.domain.SendResult;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;

public class RelayService {

    private final ClientRegistry clientRegistry;

    public RelayService(
            ClientRegistry clientRegistry
    ) {
        this.clientRegistry = clientRegistry;
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

        recipient.getLock().lock();

        try {
            boolean stored =
                    recipient.getMailbox()
                            .add(message);

            if (!stored) {
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
            return recipient
                    .getMailbox()
                    .acknowledge(messageId);

        } finally {
            recipient.getLock().unlock();
        }
    }
}