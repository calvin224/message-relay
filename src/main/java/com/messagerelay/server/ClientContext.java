package com.messagerelay.server;

import java.util.concurrent.locks.ReentrantLock;

public class ClientContext {

    private final String clientId;
    private final ReentrantLock lock = new ReentrantLock();

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

    public ClientSession getActiveSession() {
        return activeSession;
    }

    public void setActiveSession(ClientSession activeSession) {
        this.activeSession = activeSession;
    }
}