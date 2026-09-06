package com.messagerelay;

import com.messagerelay.server.RelayServer;

public class Main {

    public static void main(String[] args) throws Exception {
        RelayServer server = new RelayServer(9000);
        server.start();
    }
}