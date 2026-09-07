package com.messagerelay.server;

import com.messagerelay.server.mailbox.Mailbox;

import java.util.concurrent.locks.ReentrantLock;

public class ClientContext {

    private final String clientId;

    private final ReentrantLock lock =
            new ReentrantLock();

    private final Mailbox mailbox =
            new Mailbox();

    private ClientSession activeSession;

    private boolean replayingPendingMessages;

    public ClientContext(String clientId) {
        this.clientId = clientId;
    }

    public String getClientId() {
        return clientId;
    }

    public ReentrantLock getLock() {
        return lock;
    }

    public Mailbox getMailbox() {
        return mailbox;
    }

    public ClientSession getActiveSession() {
        return activeSession;
    }

    public void setActiveSession(
            ClientSession activeSession
    ) {
        this.activeSession = activeSession;
    }

    public boolean isReplayingPendingMessages() {
        return replayingPendingMessages;
    }

    public void setReplayingPendingMessages(
            boolean replayingPendingMessages
    ) {
        this.replayingPendingMessages =
                replayingPendingMessages;
    }
}
