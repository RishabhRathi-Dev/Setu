package io.setu.client;

import io.setu.core.protocol.BrokerResponse;
import java.io.IOException;

@FunctionalInterface
interface ClientOperation {
    BrokerResponse exchange(SetuClient client) throws IOException;
}
