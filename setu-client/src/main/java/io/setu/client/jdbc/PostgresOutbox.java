package io.setu.client.jdbc;

import io.setu.client.SetuClient;
import io.setu.core.protocol.ErrorCode;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class PostgresOutbox {
    private static final int MAGIC = 0x534F4231;
    private static final int ENVELOPE_BYTES = 20;
    private final DataSource dataSource;

    @FunctionalInterface
    public interface TransactionHandler {
        void apply(Connection transaction, byte[] payload) throws SQLException;
    }

    public PostgresOutbox(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public static UUID enqueue(Connection transaction, String topic, int partition, byte[] payload) throws SQLException {
        UUID eventId = UUID.randomUUID();
        enqueue(transaction, eventId, topic, partition, payload);
        return eventId;
    }

    public static void enqueue(Connection transaction, UUID eventId, String topic, int partition, byte[] payload)
            throws SQLException {
        if (transaction.getAutoCommit()) throw new SQLException("Outbox enqueue requires an application transaction");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(payload, "payload");
        if (topic == null || topic.equals(".") || topic.equals("..") || !topic.matches("[A-Za-z0-9._-]{1,249}")
                || partition < 0 || payload.length > 14 * 1024 * 1024 - ENVELOPE_BYTES)
            throw new IllegalArgumentException("Invalid topic, partition or payload size");
        try (var statement = transaction.prepareStatement(
                "INSERT INTO setu_outbox (event_id, topic, partition_id, payload) VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, eventId);
            statement.setString(2, topic);
            statement.setInt(3, partition);
            statement.setBytes(4, payload);
            statement.executeUpdate();
        }
    }

    public boolean dispatchOne(SetuClient client) throws SQLException, IOException {
        Objects.requireNonNull(client, "client");
        try (var transaction = dataSource.getConnection()) {
            transaction.setAutoCommit(false);
            try {
                try (var select = transaction.prepareStatement(
                        "SELECT event_id, topic, partition_id, payload FROM setu_outbox "
                                + "WHERE published_at IS NULL ORDER BY sequence_id LIMIT 1 FOR UPDATE SKIP LOCKED");
                     var rows = select.executeQuery()) {
                    if (!rows.next()) {
                        transaction.commit();
                        return false;
                    }
                    UUID eventId = rows.getObject(1, UUID.class);
                    byte[] payload = rows.getBytes(4);
                    byte[] envelope = ByteBuffer.allocate(Math.addExact(ENVELOPE_BYTES, payload.length)).putInt(MAGIC)
                            .putLong(eventId.getMostSignificantBits()).putLong(eventId.getLeastSignificantBits())
                            .put(payload).array();
                    var response = client.produce(rows.getString(2), rows.getInt(3), envelope);
                    if (response.errorCode() != ErrorCode.NONE)
                        throw new IOException("Outbox publish rejected: " + response.errorCode() + ": " + response.errorMessage());
                    try (var update = transaction.prepareStatement(
                            "UPDATE setu_outbox SET published_at = clock_timestamp() WHERE event_id = ?")) {
                        update.setObject(1, eventId);
                        if (update.executeUpdate() != 1) throw new SQLException("Outbox row disappeared while locked");
                    }
                }
                transaction.commit();
                return true;
            } catch (SQLException | IOException | RuntimeException | Error failure) {
                rollback(transaction, failure);
                throw failure;
            }
        }
    }

    public boolean process(String consumerId, byte[] envelope, TransactionHandler handler) throws SQLException {
        Objects.requireNonNull(handler, "handler");
        if (consumerId == null || consumerId.isBlank() || consumerId.length() > 1024)
            throw new IllegalArgumentException("consumerId must contain 1..1024 characters");
        Objects.requireNonNull(envelope, "envelope");
        var input = ByteBuffer.wrap(envelope);
        if (input.remaining() < ENVELOPE_BYTES || input.getInt() != MAGIC)
            throw new IllegalArgumentException("Invalid Setu outbox envelope");
        UUID eventId = new UUID(input.getLong(), input.getLong());
        byte[] payload = new byte[input.remaining()];
        input.get(payload);
        try (var transaction = dataSource.getConnection()) {
            transaction.setAutoCommit(false);
            try {
                int inserted;
                try (var statement = transaction.prepareStatement(
                        "INSERT INTO setu_inbox (consumer_id, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                    statement.setString(1, consumerId);
                    statement.setObject(2, eventId);
                    inserted = statement.executeUpdate();
                }
                if (inserted == 1) handler.apply(transaction, payload);
                transaction.commit();
                return inserted == 1;
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(transaction, failure);
                throw failure;
            }
        }
    }

    private static void rollback(Connection transaction, Throwable failure) {
        try { transaction.rollback(); }
        catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
    }
}
