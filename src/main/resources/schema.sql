CREATE TABLE IF NOT EXISTS pending_messages (
                                                sequence_id BIGSERIAL PRIMARY KEY,
                                                message_id VARCHAR(255) NOT NULL UNIQUE,
    sender_id VARCHAR(255) NOT NULL,
    recipient_id VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_pending_messages_recipient_sequence
    ON pending_messages (recipient_id, sequence_id);