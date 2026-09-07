package com.messagerelay.repository;

import com.messagerelay.domain.RelayMessage;

import java.util.List;

public interface RelayMessageRepository {

    void save(
            RelayMessage message
    );

    boolean delete(
            String recipientId,
            String messageId
    );

    List<RelayMessage> findAllPending();
}