package com.messagerelay.repository;

import com.messagerelay.domain.RelayMessage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcRelayMessageRepository
        implements RelayMessageRepository {

    private static final String INSERT_MESSAGE = """
            INSERT INTO pending_messages (
                message_id,
                sender_id,
                recipient_id,
                body
            )
            VALUES (?, ?, ?, ?)
            """;

    private static final String DELETE_MESSAGE = """
            DELETE FROM pending_messages
            WHERE recipient_id = ?
              AND message_id = ?
            """;

    private static final String FIND_ALL_PENDING = """
            SELECT
                message_id,
                sender_id,
                recipient_id,
                body
            FROM pending_messages
            ORDER BY sequence_id
            """;

    private final String databaseUrl;
    private final String username;
    private final String password;

    public JdbcRelayMessageRepository(
            String databaseUrl,
            String username,
            String password
    ) {
        this.databaseUrl = databaseUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public void save(
            RelayMessage message
    ) {

        try (
                Connection connection =
                        openConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                INSERT_MESSAGE
                        )
        ) {

            statement.setString(
                    1,
                    message.messageId()
            );

            statement.setString(
                    2,
                    message.senderId()
            );

            statement.setString(
                    3,
                    message.recipientId()
            );

            statement.setString(
                    4,
                    message.body()
            );

            statement.executeUpdate();

        } catch (SQLException e) {

            throw new RepositoryException(
                    "Failed to save pending message",
                    e
            );
        }
    }

    @Override
    public boolean delete(
            String recipientId,
            String messageId
    ) {

        try (
                Connection connection =
                        openConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                DELETE_MESSAGE
                        )
        ) {

            statement.setString(
                    1,
                    recipientId
            );

            statement.setString(
                    2,
                    messageId
            );

            return statement.executeUpdate()
                    == 1;

        } catch (SQLException e) {

            throw new RepositoryException(
                    "Failed to delete pending message",
                    e
            );
        }
    }

    @Override
    public List<RelayMessage> findAllPending() {

        List<RelayMessage> messages =
                new ArrayList<>();

        try (
                Connection connection =
                        openConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                FIND_ALL_PENDING
                        );

                ResultSet resultSet =
                        statement.executeQuery()
        ) {

            while (resultSet.next()) {

                messages.add(
                        new RelayMessage(
                                resultSet.getString(
                                        "message_id"
                                ),
                                resultSet.getString(
                                        "sender_id"
                                ),
                                resultSet.getString(
                                        "recipient_id"
                                ),
                                resultSet.getString(
                                        "body"
                                )
                        )
                );
            }

            return List.copyOf(messages);

        } catch (SQLException e) {

            throw new RepositoryException(
                    "Failed to load pending messages",
                    e
            );
        }
    }

    private Connection openConnection()
            throws SQLException {

        return DriverManager.getConnection(
                databaseUrl,
                username,
                password
        );
    }
}