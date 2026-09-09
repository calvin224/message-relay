package com.messagerelay.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ClientRegistry {

    private static final int MAX_IDENTITIES = 100;

    public enum RegistrationResult {
        REGISTERED,
        IDENTITY_IN_USE,
        IDENTITY_LIMIT_REACHED
    }

    private final ConcurrentMap<String, ClientContext> clients =
            new ConcurrentHashMap<>();

    public synchronized RegistrationResult register(
            String clientId,
            ClientSession session
    ) {
        ClientContext context = clients.get(clientId);

        if (context == null) {
            if (clients.size() >= MAX_IDENTITIES) {
                return RegistrationResult.IDENTITY_LIMIT_REACHED;
            }

            context = new ClientContext(clientId);
            clients.put(clientId, context);
        }

        context.getLock().lock();

        try {
            if (context.getActiveSession() != null) {
                return RegistrationResult.IDENTITY_IN_USE;
            }

            context.setActiveSession(session);

            return RegistrationResult.REGISTERED;

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
