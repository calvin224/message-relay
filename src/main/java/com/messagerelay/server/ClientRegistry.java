package com.messagerelay.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ClientRegistry {

    private final ConcurrentMap<String, ClientContext> clients =
            new ConcurrentHashMap<>();

    public boolean register(
            String clientId,
            ClientSession session
    ) {
        ClientContext context =
                clients.computeIfAbsent(
                        clientId,
                        ClientContext::new
                );

        context.getLock().lock();

        try {
            if (context.getActiveSession() != null) {
                return false;
            }

            context.setActiveSession(session);

            return true;

        } finally {
            context.getLock().unlock();
        }
    }

    public void disconnect(
            String clientId,
            ClientSession session
    ) {
        ClientContext context =
                clients.get(clientId);

        if (context == null) {
            return;
        }

        context.getLock().lock();

        try {
            if (context.getActiveSession() == session) {
                context.setActiveSession(null);
            }

        } finally {
            context.getLock().unlock();
        }
    }

    public ClientContext getClient(
            String clientId
    ) {
        return clients.get(clientId);
    }
}