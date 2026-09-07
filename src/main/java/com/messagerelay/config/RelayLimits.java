package com.messagerelay.config;

public final class RelayLimits {

    public static final int MAX_FRAME_BYTES =
            64 * 1024;

    public static final int MAX_ACTIVE_CONNECTIONS =
            100;

    public static final int MAX_CLIENT_IDENTITIES =
            1_000;

    public static final int MAX_MAILBOX_MESSAGES =
            100;

    public static final int MAX_TOTAL_PENDING_MESSAGES =
            MAX_CLIENT_IDENTITIES
                    * MAX_MAILBOX_MESSAGES;

    public static final int MAX_OUTBOUND_EVENTS =
            128;

    public static final int REGISTRATION_TIMEOUT_MILLISECONDS =
            5_000;

    public static final int SHUTDOWN_TIMEOUT_SECONDS =
            2;

    private RelayLimits() {
    }
}
