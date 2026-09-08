package com.messagerelay;

import com.messagerelay.server.RelayServer;

import java.io.IOException;

public class Main {

    private static final int PORT = 9000;

    public static void main(String[] args) throws Exception {

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

            System.err.println(
                    "Error while shutting down relay: "
                            + e.getMessage()
            );
        }
    }
}
