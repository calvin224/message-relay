package com.messagerelay;

import com.messagerelay.server.RelayServer;

import java.io.IOException;

public class Main {

    private static final int PORT = 9000;

    private static final System.Logger LOGGER =
            System.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {

        System.getProperties().putIfAbsent(
                "java.util.logging.SimpleFormatter.format",
                "%5$s%6$s%n"
        );

        RelayServer server =
                new RelayServer(
                        args.length > 0
                                ? Integer.parseInt(args[0])
                                : PORT
                );

        Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform()
                        .name("relay-shutdown")
                        .unstarted(
                                () -> shutdown(server)
                        )
        );

        server.start();
    }

    private static void shutdown(
            RelayServer server
    ) {

        System.out.println(
                "Shutting down message relay..."
        );

        try {
            server.stop();

        } catch (IOException e) {

            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "Error while shutting down relay",
                    e
            );
        }
    }
}
