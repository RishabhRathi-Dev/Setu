package io.setu.integration;

import io.setu.client.SetuClient;
import io.setu.client.jdbc.PostgresOutbox;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.ErrorCode;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SETU_TEST_POSTGRES_URL", matches = ".+")
class PostgresOutboxIntegrationTests {
    @TempDir Path directory;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach void initialize() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getenv("SETU_TEST_POSTGRES_URL"));
        dataSource.setUser(System.getenv().getOrDefault("SETU_TEST_POSTGRES_USER", "postgres"));
        dataSource.setPassword(System.getenv().getOrDefault("SETU_TEST_POSTGRES_PASSWORD", ""));
        schema = "setu_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        dataSource.setCurrentSchema(schema);
        try (var input = PostgresOutbox.class.getResourceAsStream("postgres.sql");
             var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertNotNull(input);
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            statement.execute("CREATE TABLE effects (event_value integer NOT NULL)");
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (dataSource == null || schema == null) return;
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void applicationRollbackRemovesOutboxAlongsideBusinessWrite() throws Exception {
        try (var transaction = dataSource.getConnection()) {
            assertThrows(SQLException.class, () -> PostgresOutbox.enqueue(transaction, "orders", 0, new byte[]{1}));
            transaction.setAutoCommit(false);
            try (var statement = transaction.createStatement()) { statement.executeUpdate("INSERT INTO effects VALUES (1)"); }
            PostgresOutbox.enqueue(transaction, "orders", 0, new byte[]{1});
            transaction.rollback();
        }
        assertEquals(0, count("setu_outbox"));
        assertEquals(0, count("effects"));
    }

    @Test void relayRedeliveryAndConcurrentInboxProcessingApplyDatabaseEffectOnce() throws Exception {
        var outbox = new PostgresOutbox(dataSource);
        try (var broker = new DefaultBroker(directory, 1024);
             var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                     new FramedConnectionHandler(1024 * 1024, new BrokerRequestHandler(broker)))) {
            server.start();
            try (var client = SetuClient.connectPlain("127.0.0.1", server.localAddress().getPort(), 5000, 5000, 1024 * 1024)) {
                try (var transaction = dataSource.getConnection()) {
                    transaction.setAutoCommit(false);
                    PostgresOutbox.enqueue(transaction, "orders", 0, new byte[]{7});
                    transaction.commit();
                }
                assertThrows(java.io.IOException.class, () -> outbox.dispatchOne(client));
                assertEquals(ErrorCode.NONE, client.createTopic("orders", 1).errorCode());
                assertTrue(outbox.dispatchOne(client));
                assertFalse(outbox.dispatchOne(client));
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE setu_outbox SET published_at = NULL");
                }
                assertTrue(outbox.dispatchOne(client));
                var records = client.fetch("orders", 0, 0, 1024).records();
                assertEquals(2, records.size());
                assertArrayEquals(records.get(0).value(), records.get(1).value());
                var envelope = records.getFirst().value();
                assertThrows(SQLException.class, () -> outbox.process("billing", envelope, (transaction, payload) -> {
                    try (var statement = transaction.createStatement()) { statement.executeUpdate("INSERT INTO effects VALUES (7)"); }
                    throw new SQLException("rollback test");
                }));
                assertEquals(0, count("effects"));
                assertEquals(0, count("setu_inbox"));
                try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                    var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                    for (int attempt = 0; attempt < 8; attempt++) {
                        results.add(workers.submit(() -> outbox.process("billing", envelope, (transaction, payload) -> {
                            assertArrayEquals(new byte[]{7}, payload);
                            try (var statement = transaction.createStatement()) { statement.executeUpdate("INSERT INTO effects VALUES (7)"); }
                        })));
                    }
                    int applied = 0;
                    for (var result : results) if (result.get(10, TimeUnit.SECONDS)) applied++;
                    assertEquals(1, applied);
                }
                assertEquals(1, count("effects"));
                assertEquals(1, count("setu_inbox"));
            }
        }
    }

    private long count(String table) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
