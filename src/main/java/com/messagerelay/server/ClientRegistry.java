package com.messagerelay.server;

import com.messagerelay.config.RelayLimits;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ClientRegistry {

    private final int maxClientIdentities;

    private final ConcurrentMap<String, ClientContext> clients =
            new ConcurrentHashMap<>();

    private final Object creationLock =
            new Object();

    public ClientRegistry() {
        this(RelayLimits.MAX_CLIENT_IDENTITIES);
    }

    public ClientRegistry(
            int maxClientIdentities
    ) {
        if (maxClientIdentities < 1) {
            throw new IllegalArgumentException(
                    "maxClientIdentities must be positive"
            );
        }

        this.maxClientIdentities =
                maxClientIdentities;
    }

    public RegistrationResult register(
            String clientId,
            ClientSession session
    ) {
        ClientContext context =
                findOrCreate(clientId);

        if (context == null) {
            return RegistrationResult.CAPACITY_REACHED;
        }

        context.getLock().lock();

        try {
            if (context.getActiveSession() != null) {
                return RegistrationResult.IDENTITY_IN_USE;
            }

            context.setActiveSession(session);
            context.setReplayingPendingMessages(true);

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
                context.setReplayingPendingMessages(false);
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

    public ClientContext getOrCreateClient(
            String clientId
    ) {
        ClientContext context =
                findOrCreate(clientId);

        if (context == null) {
            throw new IllegalStateException(
                    "Client identity limit reached"
            );
        }

        return context;
    }

    public int size() {
        return clients.size();
    }

    private ClientContext findOrCreate(
            String clientId
    ) {
        ClientContext existing =
                clients.get(clientId);

        if (existing != null) {
            return existing;
        }

        synchronized (creationLock) {
            existing = clients.get(clientId);

            if (existing != null) {
                return existing;
            }

            if (clients.size()
                    >= maxClientIdentities) {
                return null;
            }

            ClientContext created =
                    new ClientContext(clientId);

            clients.put(clientId, created);
            return created;
        }
    }
}
