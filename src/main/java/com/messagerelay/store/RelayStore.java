package com.messagerelay.store;

import com.messagerelay.domain.RelayMessage;

public interface RelayStore {

    void save(RelayMessage message);
}