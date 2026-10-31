package io.setu.benchmark.chaos;

import com.sun.management.OperatingSystemMXBean;
import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import io.setu.server.tcp.TcpServerOptions;
import io.setu.storage.log.StorageMode;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Same-box broker and load clients. Each producer/consumer pair owns one topic: no fanout. */
final class SustainedThroughputWorker {
    private static final int BYTES = BenchmarkSettings.recordBytes();
    private static final int BATCH = BenchmarkSettings.integer("setu.benchmark.batch.records", 256, 1, 10000);
    private static final int MILLIS = BenchmarkSettings.integer("setu.benchmark.stage.millis", 3000, 250, 60000);
    private static final int WARMUP = BenchmarkSettings.integer("setu.benchmark.warmup.millis", 2000, 250, 60000);
    private static final int MEMORY_BUDGET = BenchmarkSettings.integer("setu.benchmark.memory.log.bytes", 256 * 1024 * 1024, 1, Integer.MAX_VALUE);
    private static final boolean FULL = "full".equals(System.getProperty("setu.benchmark.measurement", "smoke"));
    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static int stageSequence = Integer.getInteger("setu.benchmark.stage.id", 1) - 1;

    public static void main(String[] args) throws Exception {
        BenchmarkRuntime.verifyZgc(SustainedThroughputWorker.class);
        StorageMode mode = StorageMode.valueOf(args[0]);
        Path root = Files.createDirectories(Path.of(args[1]));
        if (!Set.of("full", "smoke").contains(System.getProperty("setu.benchmark.measurement", "smoke")))
            throw new IllegalArgumentException("measurement must be full or smoke");
        if (FULL && (MILLIS < 30000 || WARMUP < 10000)) throw new IllegalArgumentException("full needs 10s warmup and 30s measurement");
        if ((long) BATCH * (BYTES + 4) + 1024 > 15 * 1024 * 1024) throw new IllegalArgumentException("batch exceeds frame limit");
        int ceiling = SustainedCriteria.concurrencyLimit(BenchmarkSettings.cpuCores(),
                BenchmarkSettings.integer("setu.benchmark.max.concurrency", 32768, 1, 32768));
        System.out.printf(Locale.ROOT, "SUSTAINED_CONFIG mode=%s profile=%s record_bytes=%d batch_records=%d stage_ms=%d warmup_ms=%d max_concurrency=%d cpu_cores=%.3f ram_bytes=%d filesystem=%s durability=%s histogram=HdrHistogram_3_digits unique_delivery=true jfr=%s jvm_scope=stage%n",
                mode, FULL ? "full" : "smoke", BYTES, BATCH, MILLIS, WARMUP, ceiling, BenchmarkSettings.cpuCores(),
                OS.getTotalMemorySize(), Files.getFileStore(root).type(), mode == StorageMode.FILE ? "force_each_group_commit_before_ack" : "volatile", Boolean.getBoolean("setu.benchmark.jfr"));
        int stage = 0;
        Result knee = null;
        for (int concurrency = 1; concurrency <= ceiling; concurrency *= 2) {
            Result result = BenchmarkStageProcess.sustained(++stage, mode.name(), root, concurrency, 0, 0);
            if (!result.healthy) break;
            if (knee == null || result.rate > knee.rate) knee = result;
        }
        boolean latencyQualified = knee != null;
        if (knee != null) for (double fraction : new double[]{.5, .8, 1})
            latencyQualified &= BenchmarkStageProcess.sustained(++stage, mode.name(), root,
                    knee.concurrency, knee.rate * fraction, fraction).healthy;
        System.out.printf(Locale.ROOT, "SUSTAINED_END mode=%s knee_per_s=%.3f concurrency=%d qualified=%s%n",
                mode, knee == null ? 0 : knee.rate, knee == null ? 0 : knee.concurrency, FULL && latencyQualified);
    }

    static Result run(StorageMode mode, Path root, int concurrency, double offered, double fraction) throws Exception {
        Path data = Files.createTempDirectory(root, "stage-");
        try (Load load = new Load(mode, data, concurrency, offered)) {
            boolean stable = load.warmup();
            if (load.failure.get() != null) throw new IOException("warmup failed", load.failure.get());
            CapacityJfr.Stage event = new CapacityJfr.Stage();
            event.mode = mode.name();
            event.stage = ++stageSequence;
            event.begin();
            Window window = new Window(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(MILLIS));
            long backlogStart = load.backlog();
            long cpuStart = OS.getProcessCpuTime();
            load.window = window;
            List<Long> heaps = new ArrayList<>();
            List<Long> sampleTimes = new ArrayList<>();
            SplittableRandom sampling = new SplittableRandom(0x5e7);
            long peakBacklog = backlogStart;
            while (System.nanoTime() < window.end && load.failure.get() == null) {
                heaps.add(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                sampleTimes.add(System.nanoTime());
                peakBacklog = Math.max(peakBacklog, load.backlog());
                load.checkDisk();
                // Dither frequent samples so a periodic GC sawtooth cannot phase-lock to a 250 ms poll.
                Thread.sleep(Math.min(sampling.nextLong(40, 71),
                        Math.max(1, (window.end - System.nanoTime()) / 1000000)));
            }
            long stopped = System.nanoTime();
            event.end();
            heaps.add(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            sampleTimes.add(stopped);
            long cpuEnd = OS.getProcessCpuTime();
            long backlogEnd = load.backlog();
            // Stop and drain in-flight operations before reading histograms. Rates require both
            // start and completion inside the window; latency retains late completions too.
            load.stop();
            double seconds = (window.end - window.start) / 1e9;
            double cpu = (cpuEnd - cpuStart) / ((stopped - window.start) * BenchmarkSettings.cpuCores());
            long produced = window.produced.sum(), consumed = window.consumed.sum();
            event.produced = produced;
            event.consumed = consumed;
            event.commit();
            long errors = load.errors.get(); // include warmup and cleanup errors; never hide a rejection
            long missed = window.missed.sum();
            double p50 = window.percentile(50), p99 = window.percentile(99), p999 = window.percentile(99.9);
            long heapPeak = heaps.stream().mapToLong(Long::longValue).max().orElse(Long.MAX_VALUE);
            double slope = SustainedCriteria.heapSlope(heaps, sampleTimes);
            boolean healthy = SustainedCriteria.healthy(mode, produced, consumed, errors, missed, p99, cpu,
                    heapPeak, slope, stable, offered, produced / seconds, fraction) && load.failure.get() == null
                    && stopped >= window.end;
            System.out.printf(Locale.ROOT, "SUSTAINED_STAGE mode=%s kind=%s concurrency=%d load_fraction=%.1f offered_per_s=%.3f duration_s=%.3f produced=%d consumed=%d produce_per_s=%.3f consume_per_s=%.3f produce_mib_per_s=%.3f consume_mib_per_s=%.3f records_per_core=%.3f consume_ratio=%.6f backlog_start=%d backlog_end=%d backlog_peak=%d errors=%d missed_records=%d p50_ms=%.6f p99_ms=%.6f p999_ms=%.6f produce_p99_ms=%.6f fetch_p99_ms=%.6f ack_p99_ms=%.6f scheduled_p99_ms=%.6f cpu_avg=%.6f heap_peak_bytes=%d heap_slope_mib_s=%.6f heap_samples=%d heap_sampling=jitter_40_70_ms warmup_stable=%s healthy=%s qualified=%s stage=%d jvm_pid=%d jvm_start_millis=%d%n",
                    mode, offered == 0 ? "ramp" : "latency", concurrency, fraction, offered, seconds, produced, consumed,
                    produced / seconds, consumed / seconds, produced * (double) BYTES / seconds / (1024 * 1024),
                    consumed * (double) BYTES / seconds / (1024 * 1024), produced / seconds / BenchmarkSettings.cpuCores(),
                    produced == 0 ? 0 : (double) consumed / produced, backlogStart, backlogEnd, peakBacklog,
                    errors, missed, p50, p99, p999, window.p(window.produce, 99), window.p(window.fetch, 99),
                    window.p(window.ack, 99), window.p(window.scheduled, 99), cpu, heapPeak, slope, heaps.size(), stable, healthy,
                    FULL && healthy, event.stage, ProcessHandle.current().pid(), ManagementFactory.getRuntimeMXBean().getStartTime());
            if (load.failure.get() != null) throw new IOException("sustained workload failed", load.failure.get());
            return new Result(concurrency, produced / seconds, healthy);
        } finally {
            // Only the unique temporary stage directory created above is removed.
            try (var paths = Files.walk(data)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    record Result(int concurrency, double rate, boolean healthy) { }

    private static final class Window {
        final long start, end;
        final LongAdder produced = new LongAdder(), consumed = new LongAdder(), missed = new LongAdder();
        final Recorder produce = new Recorder(3), fetch = new Recorder(3), ack = new Recorder(3), scheduled = new Recorder(3);
        final Map<Recorder, Histogram> snapshots = new IdentityHashMap<>();
        Window(long start, long duration) { this.start = start; this.end = start + duration; }
        double p(Recorder recorder, double percentile) {
            Histogram histogram = snapshots.computeIfAbsent(recorder, Recorder::getIntervalHistogram);
            return histogram.getTotalCount() == 0 ? -1 : histogram.getValueAtPercentile(percentile) / 1e6;
        }
        double percentile(double percentile) {
            // Do not dilute slow produce requests by mixing them with many fast fetch/ack requests.
            return Math.max(Math.max(p(produce, percentile), p(fetch, percentile)),
                    Math.max(p(ack, percentile), p(scheduled, percentile)));
        }
    }

    private static final class Load implements AutoCloseable {
        final DefaultBroker broker;
        final TcpServer server;
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final List<SetuClient> clients = new ArrayList<>();
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong errors = new AtomicLong();
        final LongAdder totalProduced = new LongAdder(), totalConsumed = new LongAdder();
        final StorageMode mode;
        final Path data;
        volatile Window window;

        Load(StorageMode mode, Path data, int concurrency, double offered) throws Exception {
            this.mode = mode; this.data = data;
            broker = new DefaultBroker(mode, mode == StorageMode.FILE ? data : null, BYTES, 64L * 1024 * 1024,
                    3600000, 7200000, MEMORY_BUDGET);
            server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                    new FramedConnectionHandler(new BrokerRequestHandler(broker)), new TcpServerOptions(65536, 65536, 0));
            try {
                server.start();
                for (int i = 0; i < concurrency; i++) {
                    String topic = "pair-" + i;
                    SetuClient producer = connect(), consumer = connect();
                    success(producer.createTopic(topic, 1));
                    success(consumer.joinGroup(topic, topic, "reader"));
                    workers.submit(() -> produce(producer, topic, offered == 0 ? 0 : offered / concurrency));
                    workers.submit(() -> consume(consumer, topic));
                }
            } catch (Exception | Error e) { close(); throw e; }
        }
        SetuClient connect() throws IOException {
            SetuClient client = SetuClient.connectPlain("127.0.0.1", server.localAddress().getPort(), 5000, 5000, 15 * 1024 * 1024);
            clients.add(client); return client;
        }
        boolean warmup() throws Exception {
            List<Double> rates = new ArrayList<>();
            long start = System.nanoTime(), lastTime = start, last = totalProduced.sum();
            long limit = start + TimeUnit.MILLISECONDS.toNanos(FULL ? Math.max(WARMUP, 60000) : WARMUP);
            boolean stable = false;
            while (System.nanoTime() < limit && failure.get() == null) {
                Thread.sleep(Math.min(1000, WARMUP));
                checkDisk();
                long now = System.nanoTime(), count = totalProduced.sum();
                rates.add((count - last) / ((now - lastTime) / 1e9));
                last = count; lastTime = now;
                if (now - start >= TimeUnit.MILLISECONDS.toNanos(WARMUP)) {
                    stable = !FULL || SustainedCriteria.stable(rates.subList(Math.max(0, rates.size() - 10), rates.size()));
                    if (stable) break;
                }
            }
            System.out.printf("SUSTAINED_WARMUP mode=%s duration_ms=%d stable=%s jvm_pid=%d jvm_start_millis=%d%n",
                    mode, (System.nanoTime() - start) / 1000000, stable, ProcessHandle.current().pid(), ManagementFactory.getRuntimeMXBean().getStartTime());
            return stable;
        }
        long backlog() { return Math.max(0, totalProduced.sum() - totalConsumed.sum()); }
        void checkDisk() throws IOException {
            long reserve = Long.parseLong(System.getProperty("setu.benchmark.disk.reserve.bytes", "0"));
            if (mode == StorageMode.FILE && Files.getFileStore(data).getUsableSpace() <= reserve)
                throw new IOException("free disk reserve reached");
        }
        void produce(SetuClient client, String topic, double offered) {
            List<byte[]> batch = Collections.nCopies(BATCH, new byte[BYTES]);
            long interval = offered == 0 ? 0 : Math.max(1, (long) (BATCH * 1e9 / offered));
            Window previous = null;
            long due = System.nanoTime();
            try {
                while (running.get()) {
                    Window current = window;
                    if (current != previous) { due = current.start; previous = current; }
                    if (current != null && interval > 0 && due >= slotEnd(current, interval)) break;
                    if (interval > 0) waitUntil(due);
                    if (!running.get()) break;
                    long start = System.nanoTime();
                    if (current != null && start >= current.end) break;
                    // Admission pacing is part of latency, including scheduled completion. It
                    // bounds warmup backlog without hiding a rejected request or relaxing quotas.
                    while (mode == StorageMode.IN_MEMORY && broker.retainedInMemoryBytes() >= MEMORY_BUDGET / 2
                            && running.get() && failure.get() == null) LockSupport.parkNanos(1000000);
                    if (!running.get() || failure.get() != null) break;
                    success(client.produceBatch(topic, 0, batch));
                    long finish = System.nanoTime();
                    totalProduced.add(BATCH);
                    if (current != null && start >= current.start && start < current.end) {
                        if (finish <= current.end) current.produced.add(BATCH);
                        else if (interval > 0) current.missed.add(BATCH);
                        current.produce.recordValue(finish - start);
                        if (interval > 0) current.scheduled.recordValue(Math.max(0, finish - due));
                    }
                    if (interval > 0) due += interval;
                }
                Window current = window;
                if (interval > 0 && current != null && due < slotEnd(current, interval))
                    current.missed.add(((slotEnd(current, interval) - due + interval - 1) / interval) * BATCH);
            } catch (Throwable e) { fail(e); }
        }
        void consume(SetuClient client, String topic) {
            long offset = 0;
            try {
                while (running.get()) {
                    Window current = window;
                    long start = System.nanoTime();
                    var response = client.fetch(topic, "reader", topic, 0, offset, Math.max(BYTES + 64, 256 * 1024));
                    long fetched = System.nanoTime();
                    success(response);
                    if (current != null && start >= current.start && start < current.end) current.fetch.recordValue(fetched - start);
                    if (response.records().isEmpty()) { LockSupport.parkNanos(100000); continue; }
                    if (response.records().getFirst().offset() != offset) throw new IOException("delivery gap or duplicate");
                    long next = response.records().getLast().offset() + 1;
                    long ackStart = System.nanoTime();
                    success(client.acknowledge(topic, "reader", topic, 0, next));
                    long finish = System.nanoTime();
                    offset = next;
                    totalConsumed.add(response.records().size());
                    if (current != null && start >= current.start && start < current.end) {
                        if (finish <= current.end) current.consumed.add(response.records().size());
                        current.ack.recordValue(finish - ackStart);
                    }
                }
            } catch (Throwable e) { fail(e); }
        }
        void fail(Throwable e) { errors.incrementAndGet(); failure.compareAndSet(null, e); }
        void stop() throws InterruptedException, IOException {
            running.set(false);
            workers.shutdown();
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                for (SetuClient client : clients) client.close();
                workers.shutdownNow();
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IOException("load clients did not stop");
                fail(new IOException("load clients stalled"));
            }
        }
        public void close() throws Exception {
            try { stop(); }
            finally {
                for (SetuClient client : clients) try { client.close(); } catch (IOException ignored) { }
                try { server.close(); } finally { broker.close(); }
            }
        }
    }
    private static void success(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE) throw new IOException(response.errorCode() + ": " + response.errorMessage());
    }
    private static long slotEnd(Window window, long interval) {
        // Reserve one full service interval for the last offered batch.
        return window.start + (window.end - window.start) / interval * interval;
    }
    private static void waitUntil(long due) {
        long remaining;
        while ((remaining = due - System.nanoTime()) > 0 && !Thread.currentThread().isInterrupted()) LockSupport.parkNanos(Math.min(remaining, 1000000));
    }
}
