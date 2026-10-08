package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;

import java.io.IOException;

public interface Broker extends AutoCloseable {
    BrokerResponse execute(BrokerRequest request) throws IOException;

    @Override
    void close() throws IOException;
}
