package com.messagerelay.integration.repository;

import com.messagerelay.domain.RelayMessage;
import com.messagerelay.repository.JdbcRelayMessageRepository;
import com.messagerelay.repository.RelayMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JdbcRelayMessageRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("message_relay")
                    .withUsername("relay")
                    .withPassword("relay");

    private RelayMessageRepository repository;

    @BeforeEach
    void setUp() throws Exception {

        try (
                Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()
                        );

                Statement statement =
                        connection.createStatement()
        ) {

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pending_messages (
                        sequence_id BIGSERIAL PRIMARY KEY,
                        message_id VARCHAR(255) NOT NULL UNIQUE,
                        sender_id VARCHAR(255) NOT NULL,
                        recipient_id VARCHAR(255) NOT NULL,
                        body TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_pending_messages_recipient_sequence
                    ON pending_messages (recipient_id, sequence_id)
                    """);

            statement.execute(
                    "DELETE FROM pending_messages"
            );
        }

        repository =
                new JdbcRelayMessageRepository(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                );
    }

    @Test
    void given_pending_message_when_saved_loaded_and_deleted_then_repository_state_is_correct() {

        RelayMessage message =
                new RelayMessage(
                        "msg-1",
                        "alice",
                        "bob",
                        "hello bob"
                );

        repository.save(message);

        List<RelayMessage> pending =
                repository.findAllPending();

        assertEquals(
                1,
                pending.size()
        );

        assertEquals(
                message,
                pending.getFirst()
        );

        boolean deleted =
                repository.delete(
                        "bob",
                        "msg-1"
                );

        assertTrue(
                deleted
        );

        assertTrue(
                repository.findAllPending()
                        .isEmpty()
        );
    }
}