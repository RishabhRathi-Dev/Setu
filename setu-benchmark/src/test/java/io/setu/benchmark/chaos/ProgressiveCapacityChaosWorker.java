package io.setu.benchmark.chaos;

import com.sun.management.OperatingSystemMXBean;
import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.ErrorCode;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import io.setu.server.tcp.TcpServerOptions;
import io.setu.storage.log.StorageMode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/** Child-JVM load generator for an intentionally aggressive, progressively doubled capacity test. */
final class ProgressiveCapacityChaosWorker {
    private static final int RECORD_BYTES = 512;
    private static final int MAX_FRAME_BYTES = 15 * 1024 * 1024;
    private static final long STAGE_MILLIS = 2_000;
    private static final long SAMPLE_MILLIS = 250;
    private static final double CPU_SATURATION = 0.92;
    private static final int SATURATED_SAMPLES_TO_STOP = 4;
    private static final byte[] PAYLOAD = new byte[RECORD_BYTES];
    private static final Field CLIENT_SOCKET = clientSocketField();

    private ProgressiveCapacityChaosWorker() { }

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Expected <storage-mode> <data-directory>");
        StorageMode mode = StorageMode.valueOf(args[0]);
        Path dataRoot = Path.of(args[1]);
        AtomicBoolean outOfMemory = new AtomicBoolean();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure instanceof OutOfMemoryError) outOfMemory.set(true);
            else System.err.println("WORKER_THREAD_FAILURE thread=" + thread.getName() + " error=" + failure);
        });
        try {
            run(mode, dataRoot, outOfMemory);
        } catch (OutOfMemoryError exhausted) {
            stop(mode, "JVM_OOM", 0, 0, "main-thread heap allocation failed");
            Runtime.getRuntime().halt(42);
        } catch (Throwable failure) {
            stop(mode, "WORKER_FAILURE", 0, 0, failure.toString());
            Runtime.getRuntime().halt(43);
        }
    }

    private static void run(StorageMode mode, Path dataRoot, AtomicBoolean outOfMemory) throws Exception {
        Files.createDirectories(dataRoot);
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Path brokerData = mode == StorageMode.FILE ? dataRoot.resolve("broker-data") : null;
        DefaultBroker broker = new DefaultBroker(mode, brokerData, 1024, 64L * 1024 * 1024,
                60 * 60 * 1000L, 2 * 60 * 60 * 1000L);
        TcpServer server = new TcpServer(new java.net.InetSocketAddress("127.0.0.1", 0),
                new FramedConnectionHandler(new BrokerRequestHandler(broker)),
                new TcpServerOptions(65_536, 65_536, 0));
        List<SetuClient> clients = new ArrayList<>();
        int stage = 0;
        int concurrency = 1;
        try {
            server.start();
            List<SetuClient> producers = new ArrayList<>();
            List<SetuClient> consumers = new ArrayList<>();
            while (true) {
                stage++;
                StageResult result;
                try {
                    result = runStage(mode, stage, concurrency, server, producers, consumers, clients, os, outOfMemory);
                } catch (OutOfMemoryError exhausted) {
                    stop(mode, "JVM_OOM", stage, concurrency / 2, "heap exhausted while preparing load stage");
                    Runtime.getRuntime().halt(42);
                    return;
                } catch (IOException | RuntimeException resourceFailure) {
                    stop(mode, "RESOURCE_LIMIT", stage, concurrency / 2,
                            resourceFailure.getClass().getSimpleName() + ": " + resourceFailure.getMessage());
                    return;
                }

                printStage(mode, result);
                if (outOfMemory.get() || "JVM_OOM".equals(result.reason)) {
                    stop(mode, "JVM_OOM", stage, concurrency, "JVM heap exhausted during active load");
                    Runtime.getRuntime().halt(42);
                }
                if (result.reason != null) {
                    stop(mode, result.reason, stage, concurrency, result.detail);
                    return;
                }
                if (concurrency > Integer.MAX_VALUE / 2) {
                    stop(mode, "CONCURRENCY_COUNTER_LIMIT", stage, concurrency, "producer/consumer counter cannot double");
                    return;
                }
                concurrency *= 2;
            }
        } finally {
            for (SetuClient client : clients) closeQuietly(client);
            server.close();
            broker.close();
        }
    }

    private static StageResult runStage(StorageMode mode, int stage, int concurrency, TcpServer server,
                                        List<SetuClient> producers, List<SetuClient> consumers,
                                        List<SetuClient> clients, OperatingSystemMXBean os,
                                        AtomicBoolean outOfMemory) throws Exception {
        String topic = "capacity-" + stage;
        String group = "capacity-group-" + stage;
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        LongAdder produced = new LongAdder();
        LongAdder consumed = new LongAdder();
        LatencyHistogram latency = new LatencyHistogram();
        long started = 0;
        long elapsedNanos = 0;
        double cpuAverage = 0;
        double cpuMaximum = 0;
        long heapUsed = 0;
        String reason = null;
        String detail = "";

        try {
            while (producers.size() < concurrency) {
                SetuClient producer = connect(server.localAddress().getPort());
                clients.add(producer);
                producers.add(producer);
            }
            while (consumers.size() < concurrency) {
                SetuClient consumer = connect(server.localAddress().getPort());
                clients.add(consumer);
                consumers.add(consumer);
            }
            requireSuccess(producers.getFirst().createTopic(topic, 1), "create topic");
            for (int i = 0; i < concurrency; i++)
                requireSuccess(consumers.get(i).joinGroup(group, topic, "member-" + i), "join consumer");

            started = System.nanoTime();
            for (int i = 0; i < concurrency; i++) {
                SetuClient producer = producers.get(i);
                workers.execute(() -> produceUntilStopped(producer, topic, running, outOfMemory,
                        workerFailure, produced, latency));
            }
            for (int i = 0; i < concurrency; i++) {
                SetuClient consumer = consumers.get(i);
                String member = "member-" + i;
                workers.execute(() -> consumeUntilStopped(consumer, topic, group, member, running,
                        outOfMemory, workerFailure, consumed, latency));
            }

            int saturatedSamples = 0;
            double cpuTotal = 0;
            int cpuSamples = 0;
            long deadline = started + STAGE_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadline && running.get()) {
                Thread.sleep(SAMPLE_MILLIS);
                if (outOfMemory.get()) { reason = "JVM_OOM"; break; }
                Throwable failure = workerFailure.get();
                if (failure != null) {
                    reason = "BROKER_OR_CLIENT_LIMIT";
                    detail = failure.toString();
                    break;
                }
                double cpu = os.getProcessCpuLoad();
                if (cpu >= 0) {
                    cpuTotal += cpu;
                    cpuSamples++;
                    cpuMaximum = Math.max(cpuMaximum, cpu);
                    saturatedSamples = cpu >= CPU_SATURATION ? saturatedSamples + 1 : 0;
                    if (saturatedSamples >= SATURATED_SAMPLES_TO_STOP) {
                        reason = "CPU_SATURATION";
                        detail = String.format(Locale.ROOT, "process CPU remained at or above %.0f%%", CPU_SATURATION * 100);
                        break;
                    }
                }
                MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                heapUsed = usage.getUsed();
            }
            elapsedNanos = Math.max(1, System.nanoTime() - started);
            cpuAverage = cpuSamples == 0 ? 0 : cpuTotal / cpuSamples;
            if (outOfMemory.get()) reason = "JVM_OOM";
            if (workerFailure.get() != null && reason == null) {
                reason = "BROKER_OR_CLIENT_LIMIT";
                detail = workerFailure.get().toString();
            }
        } finally {
            running.set(false);
            if (outOfMemory.get()) {
                workers.shutdownNow();
                workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                workers.shutdown();
                if (!workers.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (reason == null) {
                        reason = "CLIENT_STALL";
                        detail = "in-flight client requests did not drain after the stage stopped";
                    }
                    workers.shutdownNow();
                    workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
            if (outOfMemory.get()) {
                reason = "JVM_OOM";
                detail = "JVM heap exhausted during active load";
            }
        }

        return new StageResult(stage, concurrency, elapsedNanos, produced.sum(), consumed.sum(),
                cpuAverage, cpuMaximum, heapUsed, latency.p99Nanos(), reason, detail);
    }

    private static void produceUntilStopped(SetuClient client, String topic, AtomicBoolean running,
                                            AtomicBoolean outOfMemory, AtomicReference<Throwable> failure,
                                            LongAdder produced, LatencyHistogram latency) {
        try {
            while (running.get()) {
                long start = System.nanoTime();
                var response = client.produce(topic, 0, PAYLOAD);
                if (response.errorCode() != ErrorCode.NONE)
                    throw new IOException("produce rejected: " + response.errorCode() + " " + response.errorMessage());
                produced.increment();
                latency.record(System.nanoTime() - start);
            }
        } catch (OutOfMemoryError exhausted) {
            outOfMemory.set(true);
            running.set(false);
        } catch (Throwable problem) {
            if (running.get()) failure.compareAndSet(null, problem);
        }
    }

    private static void consumeUntilStopped(SetuClient client, String topic, String group, String member,
                                            AtomicBoolean running, AtomicBoolean outOfMemory,
                                            AtomicReference<Throwable> failure, LongAdder consumed,
                                            LatencyHistogram latency) {
        long offset = 0;
        try {
            while (running.get()) {
                long start = System.nanoTime();
                var response = client.fetch(group, member, topic, 0, offset, 256 * 1024);
                if (response.errorCode() != ErrorCode.NONE)
                    throw new IOException("fetch rejected: " + response.errorCode() + " " + response.errorMessage());
                if (response.records().isEmpty()) {
                    LockSupport.parkNanos(100_000);
                    continue;
                }
                long nextOffset = response.records().getLast().offset() + 1;
                var ack = client.acknowledge(group, member, topic, 0, nextOffset);
                if (ack.errorCode() != ErrorCode.NONE)
                    throw new IOException("acknowledgement rejected: " + ack.errorCode() + " " + ack.errorMessage());
                offset = nextOffset;
                consumed.add(response.records().size());
                latency.record(System.nanoTime() - start);
            }
        } catch (OutOfMemoryError exhausted) {
            outOfMemory.set(true);
            running.set(false);
        } catch (Throwable problem) {
            if (running.get()) failure.compareAndSet(null, problem);
        }
    }

    private static SetuClient connect(int port) throws IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 5_000, 30_000, MAX_FRAME_BYTES);
    }

    private static void requireSuccess(io.setu.core.protocol.BrokerResponse response, String operation)
            throws IOException {
        if (response.errorCode() != ErrorCode.NONE)
            throw new IOException(operation + " failed: " + response.errorCode() + " " + response.errorMessage());
    }

    private static void printStage(StorageMode mode, StageResult result) {
        double seconds = result.elapsedNanos / 1_000_000_000.0;
        System.out.printf(Locale.ROOT,
                "CAPACITY_STAGE mode=%s stage=%d producers=%d consumers=%d duration_s=%.2f " +
                        "produced=%d produce_per_s=%.0f consumed=%d consume_per_s=%.0f " +
                        "cpu_avg=%.3f cpu_peak=%.3f heap_mb=%.1f p99_ms=%.3f stop=%s%n",
                mode, result.stage, result.concurrency, result.concurrency, seconds,
                result.produced, result.produced / seconds, result.consumed, result.consumed / seconds,
                result.cpuAverage, result.cpuMaximum, result.heapUsed / (1024.0 * 1024),
                result.p99Nanos / 1_000_000.0, result.reason == null ? "ramp" : result.reason);
        System.out.flush();
    }

    private static void stop(StorageMode mode, String reason, int stage, int concurrency, String detail) {
        System.out.printf(Locale.ROOT, "CAPACITY_STOP mode=%s reason=%s stage=%d producers=%d consumers=%d detail=%s%n",
                mode, reason, stage, concurrency, concurrency, detail.replace('\n', ' '));
        System.out.flush();
    }

    private static void closeQuietly(SetuClient client) {
        try {
            ((Socket) CLIENT_SOCKET.get(client)).setSoLinger(true, 0);
        } catch (ReflectiveOperationException | IOException ignored) {
            // Fall back to the client's regular close if an abortive close is unavailable.
        }
        try { client.close(); } catch (IOException ignored) { }
    }

    private static Field clientSocketField() {
        try {
            Field field = SetuClient.class.getDeclaredField("socket");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static final class LatencyHistogram {
        private final LongAdder[] buckets = new LongAdder[64];
        private final LongAdder count = new LongAdder();

        private LatencyHistogram() {
            for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder();
        }

        private void record(long nanos) {
            int bucket = Math.min(63, 63 - Long.numberOfLeadingZeros(Math.max(1, nanos)));
            buckets[bucket].increment();
            count.increment();
        }

        private long p99Nanos() {
            long total = count.sum();
            if (total == 0) return 0;
            long target = (long) Math.ceil(total * 0.99);
            long seen = 0;
            for (int i = 0; i < buckets.length; i++) {
                seen += buckets[i].sum();
                if (seen >= target) return 1L << Math.min(i + 1, 62);
            }
            return 0;
        }
    }

    private record StageResult(int stage, int concurrency, long elapsedNanos, long produced, long consumed,
                               double cpuAverage, double cpuMaximum, long heapUsed, long p99Nanos,
                               String reason, String detail) { }
}
