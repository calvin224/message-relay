package com.messagerelay.support;

import com.messagerelay.Main;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class ShutdownServerProcess {

    public static void main(String[] args) throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                System.in.read();
                System.exit(0);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        Main.main(args);
    }
}
