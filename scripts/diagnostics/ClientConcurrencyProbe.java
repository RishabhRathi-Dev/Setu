import io.setu.client.AsyncSetuClient;
import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Opt-in, closed-loop client comparison. No sustained-capacity qualification. */
public class ClientConcurrencyProbe {
    private static final int RECORD_BYTES = 1024;
    private static final int BATCH_RECORDS = 32;
    private static final int WARMUP_BATCHES = 25;
    private static final int FRAME_BYTES = 16 * 1024 * 1024;
    private enum Variant { SHARED_SYNC, PER_WORKER_SYNC, ASYNC_POOL }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "IN_MEMORY";
        int writers = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int batches = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int repeats = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        if (args.length > 4 || !(mode.equals("FILE") || mode.equals("IN_MEMORY"))
                || writers < 1 || writers > 32 || batches < 1 || repeats < 1 || repeats > 12
                || (long) writers * Math.max(batches, WARMUP_BATCHES) * BATCH_RECORDS * RECORD_BYTES > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Usage: ClientConcurrencyProbe.java [IN_MEMORY|FILE] [writers 1..32]"
                    + " [batches per writer] [repeats 1..12]; at most 64 MiB of payload per phase");

        Path output = Files.createDirectories(Path.of("logs", "client-concurrency", UUID.randomUUID().toString()));
        String revision = System.getProperty("setu.probe.revision", "unspecified");
        System.out.printf("CLIENT_COMPARISON_CONFIG mode=%s writers=%d batches=%d batch_records=%d record_bytes=%d"
                        + " warmup_batches=%d repeats=%d revision=%s qualified=false%n",
                mode, writers, batches, BATCH_RECORDS, RECORD_BYTES, WARMUP_BATCHES, repeats, revision);
        System.out.printf("Environment: JDK=%s OS=%s %s arch=%s processors=%d maxHeapBytes=%d filesystem=%s%n",
                System.getProperty("java.runtime.version"), System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory(), Files.getFileStore(output).type());
        System.out.println("Broker and clients share one JVM; no OS quota, physical-media identification,"
                + " offered-load schedule or concurrent consumption. Results are diagnostic only.");
        System.out.println("Data directory: " + output.toAbsolutePath());

        for (int repeat = 0; repeat < repeats; repeat++) {
            for (int position = 0; position < Variant.values().length; position++) {
                Variant variant = Variant.values()[(repeat + position) % Variant.values().length];
                Path data = output.resolve((repeat + 1) + "-" + variant);
                try (var broker = mode.equals("FILE") ? new DefaultBroker(data, 1024 * 1024) : new DefaultBroker(1024 * 1024);
                     var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                             new FramedConnectionHandler(FRAME_BYTES, new BrokerRequestHandler(broker)))) {
                    server.start();
                    int port = server.localAddress().getPort();
                    try (var reader = connect(port);
                         var executor = Executors.newVirtualThreadPerTaskExecutor();
                         var clients = new Clients(variant, writers, port)) {
                        checked(reader.createTopic("comparison", writers));
                        checked(reader.joinGroup("readers", "comparison", "member"));
                        try {
                            runPhase(clients, executor, writers, WARMUP_BATCHES, 0);
                            verify(reader, writers, 0, WARMUP_BATCHES);
                            Result measured = runPhase(clients, executor, writers, batches, WARMUP_BATCHES);
                            verify(reader, writers, WARMUP_BATCHES, batches);
                            System.out.printf(Locale.ROOT,
                                    "CLIENT_COMPARISON mode=%s variant=%s repeat=%d writers=%d connections=%d"
                                            + " records=%d elapsed_ms=%.3f records_per_s=%.3f request_p99_ms=%.3f"
                                            + " verified=true qualified=false%n",
                                    mode, variant, repeat + 1, writers, variant == Variant.SHARED_SYNC ? 1 : writers,
                                    (long) writers * batches * BATCH_RECORDS, measured.elapsedNanos / 1e6,
                                    (long) writers * batches * BATCH_RECORDS * 1e9 / measured.elapsedNanos,
                                    measured.p99Nanos / 1e6);
                        } finally {
                            checked(reader.leaveGroup("readers", "member"));
                        }
                    }
                }
            }
        }
        System.out.println("CLIENT_COMPARISON_END verified=true qualified=false");
    }

    private static Result runPhase(Clients clients, java.util.concurrent.ExecutorService executor,
                                   int writers, int batches, int firstBatch) throws Exception {
        var ready = new CountDownLatch(writers);
        var start = new CountDownLatch(1);
        var work = new ArrayList<Future<?>>();
        long[] latencies = new long[writers * batches];
        for (int writer = 0; writer < writers; writer++) {
            int partition = writer;
            work.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(60, TimeUnit.SECONDS)) throw new IOException("Start barrier timed out");
                for (int batch = 0; batch < batches; batch++) {
                    // Every variant uses the same generic request path, including payload copying.
                    long before = System.nanoTime();
                    var request = new BrokerRequest(batch + 1, Command.PRODUCE_BATCH, "comparison", partition,
                            -1, -1, null, 0, null, null, values(partition, firstBatch + batch));
                    var response = checked(clients.write(partition, request));
                    if (response.offset() != (long) (firstBatch + batch) * BATCH_RECORDS)
                        throw new IOException("Unexpected batch offset: " + response.offset());
                    latencies[partition * batches + batch] = System.nanoTime() - before;
                }
                return null;
            }));
        }
        try {
            if (!ready.await(60, TimeUnit.SECONDS)) throw new IOException("Writers did not start");
            long before = System.nanoTime();
            start.countDown();
            long deadline = before + TimeUnit.SECONDS.toNanos(60);
            for (var future : work) future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            long elapsed = System.nanoTime() - before;
            Arrays.sort(latencies);
            return new Result(elapsed, latencies[(int) Math.ceil(latencies.length * 0.99) - 1]);
        } finally {
            start.countDown();
            for (var future : work) future.cancel(true);
        }
    }

    private static void verify(SetuClient reader, int writers, int firstBatch, int batches) throws IOException {
        for (int partition = 0; partition < writers; partition++) {
            long next = (long) firstBatch * BATCH_RECORDS;
            long end = next + (long) batches * BATCH_RECORDS;
            while (next < end) {
                var page = checked(reader.fetch("readers", "member", "comparison", partition, next, 1024 * 1024));
                if (page.records().isEmpty()) throw new IOException("Missing records at " + next);
                for (var record : page.records()) {
                    if (record.offset() != next || next >= end || !Arrays.equals(record.value(),
                            value(partition, (int) (next / BATCH_RECORDS), (int) (next % BATCH_RECORDS))))
                        throw new IOException("Readback mismatch at " + next);
                    next++;
                }
                checked(reader.acknowledge("readers", "member", "comparison", partition, next));
            }
            if (!checked(reader.fetch("readers", "member", "comparison", partition, end, RECORD_BYTES)).records().isEmpty())
                throw new IOException("Unexpected records beyond measured writes");
        }
    }

    private static List<byte[]> values(int partition, int batch) {
        var values = new ArrayList<byte[]>(BATCH_RECORDS);
        for (int record = 0; record < BATCH_RECORDS; record++) values.add(value(partition, batch, record));
        return values;
    }

    private static byte[] value(int partition, int batch, int record) {
        byte[] bytes = new byte[RECORD_BYTES];
        Arrays.fill(bytes, (byte) 0x5a);
        ByteBuffer.wrap(bytes).putInt(partition).putInt(batch).putInt(record);
        return bytes;
    }

    private static SetuClient connect(int port) throws IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 5_000, 35_000, FRAME_BYTES);
    }

    private static BrokerResponse checked(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE)
            throw new IOException(response.errorCode() + ": " + response.errorMessage());
        return response;
    }

    private record Result(long elapsedNanos, long p99Nanos) { }

    private static final class Clients implements AutoCloseable {
        private final List<SetuClient> synchronous = new ArrayList<>();
        private final AsyncSetuClient async;

        Clients(Variant variant, int writers, int port) throws IOException {
            async = variant == Variant.ASYNC_POOL ? new AsyncSetuClient(() -> connect(port), writers, writers, 0) : null;
            if (async == null) {
                try {
                    for (int index = 0; index < (variant == Variant.SHARED_SYNC ? 1 : writers); index++)
                        synchronous.add(connect(port));
                } catch (IOException failure) {
                    try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        }

        BrokerResponse write(int writer, BrokerRequest request) throws Exception {
            return async == null ? synchronous.get(writer % synchronous.size()).request(request)
                    : async.request(request).get(40, TimeUnit.SECONDS);
        }

        @Override public void close() throws IOException {
            IOException failure = null;
            if (async != null) async.close();
            for (var client : synchronous) {
                try { client.close(); }
                catch (IOException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
