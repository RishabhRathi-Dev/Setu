package io.setu.server;

import io.setu.core.broker.DefaultBroker;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.SelectorTcpServer;
import io.setu.server.tcp.TcpServer;
import io.setu.server.tcp.TcpServerOptions;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** Standalone local-development broker. Secure embedding remains available through SecureServerFactory. */
public final class SetuServerMain {
    private SetuServerMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage: java @config/jvm.options -jar setu-server-1.0.0-SNAPSHOT.jar [--config <properties-file> | --aot-train]");
            System.out.println("Default: plaintext 127.0.0.1:9092, file storage in ./data, rolling logs in ./logs.");
            return;
        }
        if (args.length == 1 && args[0].equals("--aot-train")) {
            BrokerAotTraining.run();
            return;
        }
        if (args.length != 0 && (args.length != 2 || !args[0].equals("--config")))
            throw new IllegalArgumentException("Expected --config <properties-file>, --aot-train, or --help");
        Properties properties = new Properties();
        if (args.length == 2) {
            try (var input = Files.newInputStream(Path.of(args[1]))) { properties.load(input); }
        }
        Set<String> supported = Set.of("bind.host", "bind.port", "storage.mode", "data.dir",
                "record.max.bytes", "queue.block.bytes", "connections.max", "accept.backlog",
                "handshake.timeout.millis", "frame.timeout.millis", "consumers.max", "consumer.partitions.max",
                "socket.timeout.millis", "ack.timeout.millis", "consumer.timeout.millis", "transport", "memory.log.max.bytes",
                "requests.max.bytes", "responses.max.bytes", "memory.retention.millis", "file.retention.millis", "write.timeout.millis");
        for (String key : properties.stringPropertyNames())
            if (!supported.contains(key)) throw new IllegalArgumentException("Unknown configuration property: " + key);
        StorageMode mode = StorageMode.valueOf(properties.getProperty("storage.mode", "FILE"));
        InetSocketAddress address = new InetSocketAddress(properties.getProperty("bind.host", "127.0.0.1"),
                Math.toIntExact(number(properties, "bind.port", 9092)));
        TcpServerOptions options = new TcpServerOptions(Math.toIntExact(number(properties, "connections.max", 10_000)),
                Math.toIntExact(number(properties, "accept.backlog", 256)),
                Math.toIntExact(number(properties, "socket.timeout.millis", 60_000)),
                number(properties, "requests.max.bytes", 128L * 1024 * 1024),
                number(properties, "responses.max.bytes", 128L * 1024 * 1024),
                Math.toIntExact(number(properties, "write.timeout.millis", 30_000)),
                Math.toIntExact(number(properties, "handshake.timeout.millis", 10_000)),
                Math.toIntExact(number(properties, "frame.timeout.millis", 30_000)));
        String transport = properties.getProperty("transport", "blocking");
        if (!Set.of("blocking", "selector").contains(transport))
            throw new IllegalArgumentException("transport must be blocking or selector");
        var logger = LoggerFactory.getLogger(SetuServerMain.class);
        logger.info("JVM garbage collectors: {}", java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                .stream().map(java.lang.management.GarbageCollectorMXBean::getName).toList());
        logger.info("JVM string deduplication: {}", java.lang.management.ManagementFactory
                .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                .getVMOption("UseStringDeduplication").getValue());
        try (DefaultBroker broker = new DefaultBroker(mode, mode == StorageMode.FILE
                ? Path.of(properties.getProperty("data.dir", "data")) : null,
                Math.toIntExact(number(properties, "record.max.bytes", 1024 * 1024)),
                number(properties, "queue.block.bytes", MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES),
                number(properties, "ack.timeout.millis", DefaultBroker.DEFAULT_ACK_TIMEOUT_MILLIS),
                number(properties, "consumer.timeout.millis", DefaultBroker.DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS),
                number(properties, "memory.log.max.bytes", DefaultBroker.DEFAULT_MAX_RETAINED_MEMORY_BYTES),
                number(properties, "memory.retention.millis", 0),
                number(properties, "file.retention.millis", 0),
                new io.setu.core.broker.ConsumerMetadataLimits(
                        Math.toIntExact(number(properties, "consumers.max", 10_000)),
                        Math.toIntExact(number(properties, "consumer.partitions.max", 100_000))))) {
            BrokerRequestHandler handler = new BrokerRequestHandler(broker);
            TcpServer blocking = transport.equals("blocking")
                    ? new TcpServer(address, new FramedConnectionHandler(handler), options) : null;
            SelectorTcpServer selector = transport.equals("selector")
                    ? new SelectorTcpServer(address, 16 * 1024 * 1024, options, handler) : null;
            try (AutoCloseable server = blocking != null ? blocking : selector) {
                if (blocking != null) blocking.start(); else selector.start();
                CountDownLatch stopped = new CountDownLatch(1);
                Thread shutdown = new Thread(() -> {
                    try {
                        server.close();
                        broker.close();
                        logger.info("Setu stopped");
                    } catch (Exception failure) {
                        logger.error("Setu shutdown failed", failure);
                    } finally { stopped.countDown(); }
                }, "setu-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    logger.info("Setu listening on {} with {} storage and {} transport (plaintext)",
                            blocking != null ? blocking.localAddress() : selector.localAddress(), mode, transport);
                    stopped.await();
                } finally {
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException shuttingDown) { /* Hook is already executing. */ }
                }
            }
        }
    }

    private static long number(Properties properties, String key, long fallback) {
        return Long.parseLong(properties.getProperty(key, Long.toString(fallback)));
    }
}
