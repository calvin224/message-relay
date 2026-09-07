package com.messagerelay.unit.server;

import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;
import com.messagerelay.server.RegistrationResult;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientRegistryTest {

    // Core requirement 1: duplicate live identities are rejected without replacing the owner.
    @Test
    void given_connected_identity_when_second_session_registers_then_identity_in_use_is_returned() {
        ClientRegistry registry =
                new ClientRegistry(2);
        RelayService relayService =
                new RelayService(registry);
        ClientSession firstSession =
                session(registry, relayService);
        ClientSession secondSession =
                session(registry, relayService);

        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register("alice", firstSession)
        );
        assertEquals(
                RegistrationResult.IDENTITY_IN_USE,
                registry.register("alice", secondSession)
        );
        assertEquals(
                firstSession,
                registry.getClient("alice")
                        .getActiveSession()
        );
    }

    // Core requirement 6: disconnecting permits the same logical identity to reattach.
    @Test
    void given_disconnected_identity_when_new_session_registers_then_existing_context_is_reused() {
        ClientRegistry registry =
                new ClientRegistry(1);
        RelayService relayService =
                new RelayService(registry);
        ClientSession firstSession =
                session(registry, relayService);
        ClientSession reconnectedSession =
                session(registry, relayService);

        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register("alice", firstSession)
        );

        registry.disconnect("alice", firstSession);

        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(
                        "alice",
                        reconnectedSession
                )
        );
        assertEquals(1, registry.size());
    }

    // Resource bound: retained logical identities cannot grow beyond the configured limit.
    @Test
    void given_identity_limit_reached_when_new_identity_registers_then_capacity_is_reported() {
        ClientRegistry registry =
                new ClientRegistry(1);
        RelayService relayService =
                new RelayService(registry);

        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(
                        "alice",
                        session(registry, relayService)
                )
        );
        assertEquals(
                RegistrationResult.CAPACITY_REACHED,
                registry.register(
                        "bob",
                        session(registry, relayService)
                )
        );
        assertEquals(1, registry.size());
    }

    private ClientSession session(
            ClientRegistry registry,
            RelayService relayService
    ) {
        return new ClientSession(
                null,
                registry,
                relayService
        );
    }
}
