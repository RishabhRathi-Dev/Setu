import io.setu.client.AsyncSetuClient;
import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.*;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.observability.BrokerMetrics;
import io.setu.server.observability.OperationsServer;
import io.setu.server.tcp.TcpServer;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real TCP, a deliberately lost successful reply, forced process termination, and checkpoint recovery. */
public class RecoveryDemo {
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[1]).toAbsolutePath();
        if (args[0].equals("broker")) { broker(run); return; }
        Process child = null;
        try {
            child = start(run);
            int port = awaitReady(child, run);
            var producer = new ProducerIdentity("enricher", 0, 0);
            ConsumerCommit commit;
            try (var client = connect(port)) {
                ok(client.createTopic("orders", 1)); ok(client.createTopic("enriched", 1));
                var assignment = ok(client.joinManagedGroup("enrichment", "orders", "worker")).assignment();
                ok(client.produce("orders", 0, "order-42".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var input = ok(client.fetch("enrichment", "worker", "orders", 0, 0, 1024, 0, assignment.generation()));
                require(input.records().size() == 1, "input count");
                commit = new ConsumerCommit("enrichment", "worker", "orders", 0, 1, assignment.generation());
            }
            try (var proxy = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                 var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                proxy.setSoTimeout(10_000);
                var dropped = executor.submit(() -> {
                    try (var socket = proxy.accept(); var actual = connect(port)) {
                        socket.setSoTimeout(10_000);
                        var request = ProtocolCodec.decodeRequest(FrameCodec.readFrame(new DataInputStream(socket.getInputStream()), 16 * 1024 * 1024));
                        ok(actual.request(request));
                        // Close without forwarding the successful response.
                    }
                    return true;
                });
                var attempts = new AtomicInteger();
                try (var async = new AsyncSetuClient(() -> connect(attempts.getAndIncrement() == 0 ? proxy.getLocalPort() : port), 1, 8, 1)) {
                    require(ok(async.transact("enriched", 0, List.of(new byte[]{42}), producer, commit)
                            .get(15, TimeUnit.SECONDS)).offset() == 0, "retried output offset");
                    require(dropped.get(10, TimeUnit.SECONDS), "lost reply");
                    require(attempts.get() == 2, "one reconnect");
                }
            }
            System.out.println("PASS: successful transaction reply lost; retry returned offset 0 without another append.");
            Files.writeString(run.resolve("compact.request"), "checkpoint");
            awaitFile(child, run.resolve("compact.done"));
            child.destroyForcibly();
            require(child.waitFor(10, TimeUnit.SECONDS), "broker termination");
            Files.delete(run.resolve("ready"));
            child = start(run);
            int recoveredPort = awaitReady(child, run);
            try (var client = connect(recoveredPort)) {
                require(ok(client.transact("enriched", 0, List.of(new byte[]{42}), producer, commit)).offset() == 0, "restart retry");
                require(ok(client.fetch("enriched", 0, 0, 1024)).records().size() == 1, "exactly one broker output");
                require(ok(client.groupAssignment("enrichment", "orders", "worker")).assignment().nextOffsets().get(0) == 1, "input progress");
            }
            System.out.println("PASS: forcibly terminated broker recovered checkpoint; output count=1, committed input offset=1.");
            System.out.println("Evidence and broker logs: " + run);
            System.out.println("DEMO PASSED (single-node broker transaction; external effects are a separate boundary)");
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
        }
    }

    private static void broker(Path run) throws Exception {
        try (var broker = new DefaultBroker(run.resolve("data"), 1024)) {
            var metrics = new BrokerMetrics(broker);
            try (var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0), new FramedConnectionHandler(new BrokerRequestHandler(metrics)))) {
                server.start();
                try (var operations = new OperationsServer(new InetSocketAddress("127.0.0.1", 0), metrics, server::isRunning)) {
                    Files.writeString(run.resolve("ready.tmp"), Integer.toString(server.localAddress().getPort()));
                    Files.move(run.resolve("ready.tmp"), run.resolve("ready"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    Files.writeString(run.resolve("operations"), Integer.toString(operations.localAddress().getPort()));
                    while (true) {
                        if (Files.exists(run.resolve("compact.request"))) {
                            broker.compactJournal();
                            Files.delete(run.resolve("compact.request"));
                            Files.writeString(run.resolve("metrics.txt"), metrics.scrape());
                            Files.writeString(run.resolve("compact.done"), "complete");
                        }
                        Thread.sleep(25);
                    }
                }
            }
        }
    }
    private static Process start(Path run) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "@config/jvm.options", "-Dsetu.log.level=WARN", "-cp", System.getProperty("java.class.path"),
                RecoveryDemo.class.getName(), "broker", run.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(run.resolve("broker.log").toFile())).start();
    }
    private static int awaitReady(Process child, Path run) throws Exception {
        awaitFile(child, run.resolve("ready"));
        return Integer.parseInt(Files.readString(run.resolve("ready")));
    }
    private static void awaitFile(Process child, Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(path) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
        require(Files.exists(path), "timed out waiting for " + path + "; inspect broker.log");
    }
    private static SetuClient connect(int port) throws java.io.IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 5000, 10000, 16 * 1024 * 1024);
    }
    private static BrokerResponse ok(BrokerResponse response) {
        require(response.errorCode() == ErrorCode.NONE, response.errorCode() + ": " + response.errorMessage());
        return response;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
