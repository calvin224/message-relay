package com.messagerelay.repository;

import com.messagerelay.domain.RelayMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class SqliteRelayMessageRepository
        implements RelayMessageRepository {

    private static final int BUSY_TIMEOUT_MILLISECONDS =
            2_000;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS pending_messages (
                sequence_id INTEGER PRIMARY KEY AUTOINCREMENT,
                delivery_id TEXT NOT NULL UNIQUE,
                message_id TEXT NOT NULL UNIQUE,
                sender_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                body TEXT NOT NULL
            )
            """;

    private static final String CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_pending_messages_recipient_sequence
            ON pending_messages (recipient_id, sequence_id)
            """;

    private static final String INSERT_MESSAGE = """
            INSERT INTO pending_messages (
                message_id,
                delivery_id,
                sender_id,
                recipient_id,
                body
            )
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String DELETE_MESSAGE = """
            DELETE FROM pending_messages
            WHERE recipient_id = ?
              AND delivery_id = ?
            """;

    private static final String FIND_PENDING = """
            SELECT
                message_id,
                delivery_id,
                sender_id,
                recipient_id,
                body
            FROM pending_messages
            ORDER BY sequence_id
            LIMIT ?
            """;

    private final String databaseUrl;

    private final ReentrantLock writeLock =
            new ReentrantLock();

    public SqliteRelayMessageRepository(
            Path databasePath
    ) {
        Path absolutePath =
                databasePath.toAbsolutePath()
                        .normalize();

        createParentDirectory(absolutePath);

        databaseUrl =
                "jdbc:sqlite:" + absolutePath;

        initializeDatabase();
    }

    @Override
    public void save(
            RelayMessage message
    ) {
        writeLock.lock();

        try (
                Connection connection =
                        openConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                INSERT_MESSAGE
                        )
        ) {
            statement.setString(1, message.messageId());
            statement.setString(2, message.deliveryId());
            statement.setString(3, message.senderId());
            statement.setString(4, message.recipientId());
            statement.setString(5, message.body());

            statement.executeUpdate();

        } catch (SQLException e) {
            throw new RepositoryException(
                    "Failed to save pending message",
                    e
            );

        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean delete(
            String recipientId,
            String deliveryId
    ) {
        writeLock.lock();

        try (
                Connection connection =
                        openConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                DELETE_MESSAGE
                        )
        ) {
            statement.setString(1, recipientId);
            statement.setString(2, deliveryId);

            return statement.executeUpdate() == 1;

        } catch (SQLException e) {
            throw new RepositoryException(
                    "Failed to delete pending message",
                    e
            );

        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<RelayMessage> findAllPending() {
        return findPending(Integer.MAX_VALUE);
    }

    @Override
    public List<RelayMessage> findPending(
            int limit
    ) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "limit must be positive"
            );
        }

        List<RelayMessage> messages =
                new ArrayList<>();

        try (
                Connection connection =
                        openConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                FIND_PENDING
                        )
        ) {
            statement.setInt(1, limit);

            try (ResultSet resultSet =
                         statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(
                            new RelayMessage(
                                    resultSet.getString("message_id"),
                                    resultSet.getString("delivery_id"),
                                    resultSet.getString("sender_id"),
                                    resultSet.getString("recipient_id"),
                                    resultSet.getString("body")
                            )
                    );
                }
            }

            return List.copyOf(messages);

        } catch (SQLException e) {
            throw new RepositoryException(
                    "Failed to load pending messages",
                    e
            );
        }
    }

    private void initializeDatabase() {
        try (
                Connection connection =
                        openConnection();
                Statement statement =
                        connection.createStatement()
        ) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);

        } catch (SQLException e) {
            throw new RepositoryException(
                    "Failed to initialize message database",
                    e
            );
        }
    }

    private Connection openConnection()
            throws SQLException {
        Connection connection =
                DriverManager.getConnection(
                        databaseUrl
                );

        try (Statement statement =
                     connection.createStatement()) {
            statement.execute(
                    "PRAGMA busy_timeout="
                            + BUSY_TIMEOUT_MILLISECONDS
            );
            statement.execute(
                    "PRAGMA synchronous=NORMAL"
            );
        } catch (SQLException e) {
            connection.close();
            throw e;
        }

        return connection;
    }

    private void createParentDirectory(
            Path databasePath
    ) {
        Path parent = databasePath.getParent();

        if (parent == null) {
            return;
        }

        try {
            Files.createDirectories(parent);

        } catch (IOException e) {
            throw new RepositoryException(
                    "Failed to create message database directory",
                    e
            );
        }
    }
}
