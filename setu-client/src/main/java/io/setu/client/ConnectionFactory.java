package io.setu.client;

import java.io.IOException;

@FunctionalInterface
public interface ConnectionFactory {
    SetuClient connect() throws IOException;
}
