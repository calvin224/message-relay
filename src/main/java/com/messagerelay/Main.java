package com.messagerelay;

import com.messagerelay.config.RelayLimits;
import com.messagerelay.repository.RelayMessageRepository;
import com.messagerelay.repository.SqliteRelayMessageRepository;
import com.messagerelay.server.RelayServer;

import java.io.IOException;
import java.nio.file.Path;

public class Main {

    private static final int DEFAULT_PORT = 9000;

    private static final String DEFAULT_DATABASE_PATH =
            "data/message-relay.db";

    public static void main(String[] args) throws Exception {
        int port = readPort();

        Path databasePath =
                Path.of(
                        readEnvironmentVariable(
                                "RELAY_DB_PATH",
                                DEFAULT_DATABASE_PATH
                        )
                );

        RelayMessageRepository repository =
                new SqliteRelayMessageRepository(
                        databasePath
                );

        RelayServer server =
                new RelayServer(
                        port,
                        repository,
                        readPositiveInteger(
                                "RELAY_REGISTRATION_TIMEOUT_MS",
                                RelayLimits.REGISTRATION_TIMEOUT_MILLISECONDS
                        )
                );

        Runtime.getRuntime()
                .addShutdownHook(
                        Thread.ofPlatform()
                                .unstarted(
                                        () -> stop(server)
                                )
                );

        System.out.println(
                "Message database: "
                        + databasePath.toAbsolutePath()
                        .normalize()
        );

        server.start();
    }

    private static int readPort() {
        String configuredPort =
                readEnvironmentVariable(
                        "RELAY_PORT",
                        Integer.toString(DEFAULT_PORT)
                );

        try {
            int port = Integer.parseInt(
                    configuredPort
            );

            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "RELAY_PORT must be between 1 and 65535"
                );
            }

            return port;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "RELAY_PORT must be an integer",
                    e
            );
        }
    }

    private static int readPositiveInteger(
            String name,
            int defaultValue
    ) {
        String configuredValue =
                readEnvironmentVariable(
                        name,
                        Integer.toString(defaultValue)
                );

        try {
            int value = Integer.parseInt(configuredValue);

            if (value < 1) {
                throw new IllegalArgumentException(
                        name + " must be positive"
                );
            }

            return value;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + " must be an integer",
                    e
            );
        }
    }

    private static String readEnvironmentVariable(
            String name,
            String defaultValue
    ) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }

    private static void stop(
            RelayServer server
    ) {
        try {
            server.stop();

        } catch (IOException e) {
            System.err.println(
                    "Failed to stop message relay cleanly: "
                            + e.getMessage()
            );
        }
    }
}
