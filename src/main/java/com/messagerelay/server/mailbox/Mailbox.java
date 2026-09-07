package com.messagerelay.server.mailbox;

import com.messagerelay.domain.RelayMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class Mailbox {

    private static final int MAX_MESSAGES = 100;

    private final Deque<RelayMessage> messages =
            new ArrayDeque<>();

    public boolean add(
            RelayMessage message
    ) {
        if (messages.size() >= MAX_MESSAGES) {
            return false;
        }

        messages.addLast(message);
        return true;
    }

    public List<RelayMessage> getPendingMessages() {
        return List.copyOf(messages);
    }

    public boolean acknowledge(
            String messageId
    ) {
        return messages.removeIf(
                message ->
                        message.messageId()
                                .equals(messageId)
        );
    }

    public boolean isFull() {
        return messages.size() >= MAX_MESSAGES;
    }

    public int size() {
        return messages.size();
    }
}