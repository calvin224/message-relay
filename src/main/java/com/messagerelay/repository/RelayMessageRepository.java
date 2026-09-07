package com.messagerelay.repository;

import com.messagerelay.domain.RelayMessage;

import java.util.List;

public interface RelayMessageRepository {

    void save(RelayMessage message);

    boolean delete(
            String recipientId,
            String deliveryId
    );

    List<RelayMessage> findAllPending();

    default List<RelayMessage> findPending(
            int limit
    ) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "limit must be positive"
            );
        }

        return findAllPending()
                .stream()
                .limit(limit)
                .toList();
    }
}
