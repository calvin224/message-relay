package com.messagerelay.repository;

import com.messagerelay.domain.RelayMessage;

public interface RelayMessageRepository {

    void save(RelayMessage message);
}