package com.messagerelay.server.mailbox;

import com.messagerelay.config.RelayLimits;
import com.messagerelay.domain.RelayMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class Mailbox {

    private final Deque<RelayMessage> messages =
            new ArrayDeque<>();

    public boolean add(
            RelayMessage message
    ) {
        if (messages.size()
                >= RelayLimits.MAX_MAILBOX_MESSAGES) {
            return false;
        }

        messages.addLast(message);
        return true;
    }

    public List<RelayMessage> getPendingMessages() {
        return List.copyOf(messages);
    }

    public boolean acknowledge(
            String deliveryId
    ) {
        return messages.removeIf(
                message ->
                        message.deliveryId()
                                .equals(deliveryId)
        );
    }

    public int size() {
        return messages.size();
    }
}
