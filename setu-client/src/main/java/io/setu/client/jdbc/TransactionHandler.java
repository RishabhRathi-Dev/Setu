package io.setu.client.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface TransactionHandler {
    void apply(Connection transaction, byte[] payload) throws SQLException;
}
