package com.messagerelay.repository;

import com.messagerelay.domain.RelayMessage;

import java.util.List;

public class TransientRelayMessageRepository
        implements RelayMessageRepository {

    @Override
    public void save(
            RelayMessage message
    ) {
    }

    @Override
    public boolean delete(
            String recipientId,
            String deliveryId
    ) {
        return true;
    }

    @Override
    public List<RelayMessage> findAllPending() {
        return List.of();
    }
}
