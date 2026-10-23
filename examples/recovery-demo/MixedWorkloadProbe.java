import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.*;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

/** Diagnostic only: closed-loop latency, not offered-load or sustained-capacity qualification. */
public class MixedWorkloadProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int operations = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        if (operations < 1 || operations > 100_000) throw new IllegalArgumentException("operations must be 1..100000");
        if (warmup < 0 || warmup > 10_000) throw new IllegalArgumentException("warmup must be 0..10000");
        for (String mode : List.of("ordinary", "idempotent", "mixed")) {
            try (var broker = new DefaultBroker(Files.createDirectory(root.resolve(mode)), 1024);
                 var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0), new FramedConnectionHandler(new BrokerRequestHandler(broker)));
                 var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                broker.configureJournalCompaction(0);
                server.start();
                int port = server.localAddress().getPort();
                try (var setup = connect(port)) { ok(setup.createTopic("probe", 2)); }
                var ready = new CountDownLatch(2);
                var go = new CountDownLatch(1);
                var first = workers.submit(() -> run(port, 0, !mode.equals("ordinary"), operations, warmup, ready, go));
                var second = workers.submit(() -> run(port, 1, mode.equals("idempotent"), operations, warmup, ready, go));
                try {
                    if (!ready.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("warmup did not complete");
                    long waitBefore = broker.gateWaitNanos(), start = System.nanoTime();
                    go.countDown();
                    long[] a = first.get(90, TimeUnit.SECONDS), b = second.get(90, TimeUnit.SECONDS);
                    double seconds = (System.nanoTime() - start) / 1e9;
                    Arrays.sort(a); Arrays.sort(b);
                    System.out.printf(java.util.Locale.ROOT,
                            "{\"mode\":\"%s\",\"qualified\":false,\"warmup_per_writer\":%d,\"writers\":2,\"record_bytes\":1024,\"batch_records\":1,\"operations_per_writer\":%d,\"elapsed_seconds\":%.6f,\"records_per_second\":%.3f,\"writer0_p99_ms\":%.6f,\"writer1_p99_ms\":%.6f,\"gate_wait_seconds\":%.6f}%n",
                            mode, warmup, operations, seconds, operations * 2 / seconds, a[(int) Math.ceil(a.length * .99) - 1] / 1e6,
                            b[(int) Math.ceil(b.length * .99) - 1] / 1e6, (broker.gateWaitNanos() - waitBefore) / 1e9);
                } finally {
                    first.cancel(true);
                    second.cancel(true);
                    workers.shutdownNow();
                }
            }
        }
    }
    private static long[] run(int port, int partition, boolean identified, int operations, int warmup,
                              CountDownLatch ready, CountDownLatch go) throws Exception {
        try (var client = connect(port)) {
            byte[] value = new byte[1024];
            for (int i = 0; i < warmup; i++) write(client, partition, identified, i, value);
            ready.countDown();
            if (!go.await(35, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
            long[] samples = new long[operations];
            for (int i = 0; i < operations; i++) {
                long start = System.nanoTime();
                write(client, partition, identified, i + warmup, value);
                samples[i] = System.nanoTime() - start;
            }
            return samples;
        }
    }
    private static void write(SetuClient client, int partition, boolean identified, long sequence, byte[] value) throws Exception {
        ok(identified ? client.produceIdempotent("probe", partition, List.of(value), new ProducerIdentity("probe-" + partition, 0, sequence))
                : client.produce("probe", partition, value));
    }
    private static void ok(BrokerResponse response) {
        if (response.errorCode() != ErrorCode.NONE) throw new IllegalStateException(response.errorCode() + ": " + response.errorMessage());
    }
    private static SetuClient connect(int port) throws java.io.IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 5000, 10000, 16 * 1024 * 1024);
    }
}
