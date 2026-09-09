package com.messagerelay.unit.server;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.server.ClientContext;
import com.messagerelay.server.ClientRegistry;
import com.messagerelay.server.ClientSession;
import com.messagerelay.service.RelayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.messagerelay.server.ClientRegistry.RegistrationResult.IDENTITY_IN_USE;
import static com.messagerelay.server.ClientRegistry.RegistrationResult.IDENTITY_LIMIT_REACHED;
import static com.messagerelay.server.ClientRegistry.RegistrationResult.REGISTERED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRegistryTest {

    @Test
    void given_full_registry_when_reconnecting_then_identity_and_pending_messages_are_retained() {
        ClientRegistry registry = new ClientRegistry();
        RelayService service = new RelayService(registry);
        ClientSession originalSession = new ClientSession(null, registry, service);

        for (int index = 0; index < 100; index++) {
            assertEquals(REGISTERED, registry.register("client-" + index, originalSession));
        }

        ClientContext originalContext = registry.getClient("client-0");
        registry.disconnect("client-0", originalSession);
        RelayMessage message = new RelayMessage("msg-1", "client-1", "client-0", "offline");
        assertTrue(service.send(message).accepted());

        assertEquals(IDENTITY_LIMIT_REACHED, registry.register("overflow", originalSession));
        assertNull(registry.getClient("overflow"));

        ClientSession replacementSession = new ClientSession(null, registry, service);
        assertEquals(REGISTERED, registry.register("client-0", replacementSession));
        assertSame(originalContext, registry.getClient("client-0"));
        assertEquals(List.of(message), originalContext.getMailbox().getPendingMessages());

        registry.disconnect("client-0", originalSession);
        assertSame(replacementSession, originalContext.getActiveSession());
    }

    @Test
    void given_active_identity_when_registered_again_then_original_session_is_kept() {
        ClientRegistry registry = new ClientRegistry();
        RelayService service = new RelayService(registry);
        ClientSession originalSession = new ClientSession(null, registry, service);
        ClientSession otherSession = new ClientSession(null, registry, service);

        assertEquals(REGISTERED, registry.register("alice", originalSession));
        assertEquals(IDENTITY_IN_USE, registry.register("alice", otherSession));
        assertSame(originalSession, registry.getClient("alice").getActiveSession());
    }

    @Test
    @Timeout(5)
    void given_one_identity_slot_when_two_clients_register_concurrently_then_only_one_is_admitted()
            throws Exception {
        ClientRegistry registry = new ClientRegistry();
        RelayService service = new RelayService(registry);
        ClientSession session = new ClientSession(null, registry, service);

        for (int index = 0; index < 99; index++) {
            assertEquals(REGISTERED, registry.register("client-" + index, session));
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(2, TimeUnit.SECONDS));
                return registry.register("first", session);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(2, TimeUnit.SECONDS));
                return registry.register("second", session);
            });

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            List<ClientRegistry.RegistrationResult> results = List.of(
                    first.get(2, TimeUnit.SECONDS),
                    second.get(2, TimeUnit.SECONDS)
            );
            assertEquals(1, results.stream().filter(REGISTERED::equals).count());
            assertEquals(1, results.stream().filter(IDENTITY_LIMIT_REACHED::equals).count());
            assertEquals(IDENTITY_LIMIT_REACHED, registry.register("third", session));
        }
    }
}
