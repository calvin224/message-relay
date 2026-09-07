package com.messagerelay;

import com.messagerelay.repository.JdbcRelayMessageRepository;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.server.RelayServer;

public class Main {

    private static final int PORT = 9000;

    public static void main(String[] args)
            throws Exception {

        String databaseUrl =
                System.getenv("RELAY_DB_URL");

        RelayServer server;

        if (databaseUrl == null
                || databaseUrl.isBlank()) {

            System.out.println(
                    "Starting message relay in in-memory mode"
            );

            server =
                    new RelayServer(PORT);

        } else {

            String databaseUser =
                    requireEnvironmentVariable(
                            "RELAY_DB_USER"
                    );

            String databasePassword =
                    requireEnvironmentVariable(
                            "RELAY_DB_PASSWORD"
                    );

            RelayMessageRepository repository =
                    new JdbcRelayMessageRepository(
                            databaseUrl,
                            databaseUser,
                            databasePassword
                    );

            System.out.println(
                    "Starting message relay with PostgreSQL persistence"
            );

            server =
                    new RelayServer(
                            PORT,
                            repository
                    );
        }

        server.start();
    }

    private static String requireEnvironmentVariable(
            String name
    ) {

        String value =
                System.getenv(name);

        if (value == null
                || value.isBlank()) {

            throw new IllegalStateException(
                    "Required environment variable is missing: "
                            + name
            );
        }

        return value;
    }
}