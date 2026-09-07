package com.messagerelay.server;

import java.util.concurrent.locks.ReentrantLock;
import com.messagerelay.server.mailbox.Mailbox;

public class ClientContext {

    private final String clientId;

    private final ReentrantLock lock =
            new ReentrantLock();

    private final Mailbox mailbox =
            new Mailbox();

    private ClientSession activeSession;

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
}