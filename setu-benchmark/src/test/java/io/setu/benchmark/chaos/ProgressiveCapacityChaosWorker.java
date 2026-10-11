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
    private static final long STAGE_MILLIS = configuredInt("setu.benchmark.stage.millis", 2_000, 250, 60_000);
    private static final int WARMUP_MILLIS = configuredInt("setu.benchmark.warmup.millis", 2_000, 0, 60_000);
    private static final int CPU_HOLD_MILLIS = configuredInt("setu.benchmark.cpu.hold.millis", 10_000, 0, 60_000);
    private static final int MAX_CONCURRENCY = configuredInt("setu.benchmark.max.concurrency", 0, 0, 32_768);
    private static final int BATCH_RECORDS = configuredInt("setu.benchmark.batch.records", 256, 1, 10_000);
    private static final long SAMPLE_MILLIS = 250;
    private static final double CPU_SATURATION = 0.92;
    private static final int SATURATED_SAMPLES_TO_STOP = 4;
    private static final byte[] PAYLOAD = new byte[RECORD_BYTES];
    private static final List<byte[]> PRODUCE_BATCH = java.util.Collections.nCopies(BATCH_RECORDS, PAYLOAD);
    private static final Field CLIENT_SOCKET = clientSocketField();
    private static CapacityStopReporter stopReporter;

    private ProgressiveCapacityChaosWorker() { }

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Expected <storage-mode> <data-directory>");
        StorageMode mode = StorageMode.valueOf(args[0]);
        stopReporter = new CapacityStopReporter(mode.name());
        if (MAX_CONCURRENCY != 0 && Integer.bitCount(MAX_CONCURRENCY) != 1)
            throw new IllegalArgumentException("setu.benchmark.max.concurrency must be zero or a power of two");
        System.out.printf(Locale.ROOT, "CAPACITY_CONFIG mode=%s record_bytes=%d batch_records=%d stage_ms=%d warmup_ms=%d cpu_hold_ms=%d max_concurrency=%d quotas=disabled memory_log_budget=unlimited cpu_metric=process_time_per_available_cpu%n",
                mode, RECORD_BYTES, BATCH_RECORDS, STAGE_MILLIS, WARMUP_MILLIS, CPU_HOLD_MILLIS, MAX_CONCURRENCY);
        Path dataRoot = Path.of(args[1]);
        System.out.printf(Locale.ROOT, "CAPACITY_RUNTIME java=%s os=%s cpus=%d max_heap_mb=%d log_level=%s log_directory=%s tcp_nodelay=true%n",
                System.getProperty("java.version"), System.getProperty("os.name").replace(' ', '_'),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / (1024 * 1024),
                System.getProperty("setu.log.level", "INFO"), System.getProperty("SETU_LOG_DIR", "logs"));
        AtomicBoolean outOfMemory = new AtomicBoolean();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure instanceof OutOfMemoryError) stopReporter.heapExhausted();
            else {
                System.err.println("WORKER_THREAD_FAILURE thread=" + thread.getName());
                failure.printStackTrace(System.err);
            }
        });
        try {
            run(mode, dataRoot, outOfMemory);
        } catch (OutOfMemoryError exhausted) {
            stopReporter.heapExhausted();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            stop(mode, "WORKER_FAILURE", 0, 0, failure.toString());
            Runtime.getRuntime().halt(43);
        }
    }

    private static void run(StorageMode mode, Path dataRoot, AtomicBoolean outOfMemory) throws Exception {
        Files.createDirectories(dataRoot);
        var fileStore = Files.getFileStore(dataRoot);
        System.out.printf("CAPACITY_STORAGE mode=%s directory=%s filesystem=%s usable_mb=%d%n", mode,
                dataRoot.toAbsolutePath(), fileStore.type(), fileStore.getUsableSpace() / (1024 * 1024));
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Path brokerData = mode == StorageMode.FILE ? dataRoot.resolve("broker-data") : null;
        DefaultBroker broker = new DefaultBroker(mode, brokerData, 1024, 64L * 1024 * 1024,
                60 * 60 * 1000L, 2 * 60 * 60 * 1000L, 0);
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
            if (WARMUP_MILLIS > 0) {
                StageResult warmup = runStage(mode, 0, 1, server, producers, consumers, clients, os,
                        outOfMemory, WARMUP_MILLIS, false);
                System.out.printf("CAPACITY_WARMUP mode=%s duration_ms=%d produced=%d consumed=%d stop=%s%n",
                        mode, warmup.elapsedNanos / 1_000_000, warmup.produced, warmup.consumed, warmup.reason);
                if (warmup.reason != null) {
                    stop(mode, warmup.reason, 0, 1, warmup.detail);
                    return;
                }
                requireSuccess(consumers.getFirst().leaveGroup("capacity-group-0", "member-0"), "leave warmup group");
            }
            while (true) {
                stage++;
                stopReporter.prepareStage(stage, concurrency);
                System.out.printf("CAPACITY_PREPARE mode=%s stage=%d producers=%d consumers=%d%n", mode, stage, concurrency, concurrency);
                StageResult result;
                try {
                    result = runStage(mode, stage, concurrency, server, producers, consumers, clients, os, outOfMemory,
                            STAGE_MILLIS, MAX_CONCURRENCY == 0);
                } catch (OutOfMemoryError exhausted) {
                    stopReporter.heapExhausted();
                    return;
                } catch (IOException | RuntimeException resourceFailure) {
                    resourceFailure.printStackTrace(System.err);
                    stop(mode, "RESOURCE_LIMIT", stage, concurrency / 2,
                            resourceFailure.getClass().getSimpleName() + ": " + resourceFailure.getMessage());
                    return;
                }

                printStage(mode, result, result.cpuHoldStarted ? "CAPACITY_STAGE_TOTAL" : "CAPACITY_STAGE");
                if (outOfMemory.get() || "JVM_OOM".equals(result.reason)) {
                    stopReporter.heapExhausted();
                }
                if (result.reason != null) {
                    stop(mode, result.reason, stage, concurrency, result.detail);
                    return;
                }
                if (MAX_CONCURRENCY > 0 && concurrency >= MAX_CONCURRENCY) {
                    stop(mode, "CONFIGURED_LIMIT", stage, concurrency, "configured concurrency ceiling reached");
                    return;
                }
                if (concurrency > Integer.MAX_VALUE / 2) {
                    stop(mode, "CONCURRENCY_COUNTER_LIMIT", stage, concurrency, "producer/consumer counter cannot double");
                    return;
                }
                concurrency *= 2;
            }
        } catch (OutOfMemoryError exhausted) {
            stopReporter.heapExhausted();
        } finally {
            for (SetuClient client : clients) closeQuietly(client);
            server.close();
            broker.close();
        }
    }

    private static StageResult runStage(StorageMode mode, int stage, int concurrency, TcpServer server,
                                        List<SetuClient> producers, List<SetuClient> consumers,
                                        List<SetuClient> clients, OperatingSystemMXBean os,
                                        AtomicBoolean outOfMemory, long durationMillis, boolean stopOnCpu) throws Exception {
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
        CpuSaturationHold cpuHold = new CpuSaturationHold(CPU_SATURATION, SATURATED_SAMPLES_TO_STOP,
                CPU_HOLD_MILLIS * 1_000_000L);
        long holdProduced = 0;
        long holdConsumed = 0;
        double holdCpuBaseline = 0;
        int holdSamplesBaseline = 0;
        double lastCpu = -1;

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
            ProcessCpuSampler cpuSampler = new ProcessCpuSampler(Runtime.getRuntime().availableProcessors(),
                    started, os.getProcessCpuTime());
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

            double cpuTotal = 0;
            int cpuSamples = 0;
            long deadline = started + durationMillis * 1_000_000L;
            long nextDiagnostic = started + 1_000_000_000L;
            while (System.nanoTime() < deadline && running.get()) {
                Thread.sleep(SAMPLE_MILLIS);
                if (outOfMemory.get()) { reason = "JVM_OOM"; break; }
                Throwable failure = workerFailure.get();
                if (failure != null) {
                    reason = "BROKER_OR_CLIENT_LIMIT";
                    detail = failure.toString();
                    break;
                }
                double cpu = cpuSampler.sample(System.nanoTime(), os.getProcessCpuTime());
                lastCpu = cpu;
                if (cpu >= 0) {
                    cpuTotal += cpu;
                    cpuSamples++;
                    cpuMaximum = Math.max(cpuMaximum, cpu);
                }
                boolean wasHolding = cpuHold.isHolding();
                boolean holdCompleted = stopOnCpu && cpuHold.sample(System.nanoTime(), cpu);
                if (!wasHolding && cpuHold.isHolding()) {
                    deadline = Math.max(deadline, cpuHold.deadlineNanos());
                    holdProduced = produced.sum();
                    holdConsumed = consumed.sum();
                    holdCpuBaseline = cpuTotal;
                    holdSamplesBaseline = cpuSamples;
                    // Publish the completed ramp prefix before the hold can exhaust the JVM or disk.
                    printStage(mode, new StageResult(stage, concurrency,
                            Math.max(1, cpuHold.startedNanos() - started), holdProduced, holdConsumed,
                            cpuSamples == 0 ? 0 : cpuTotal / cpuSamples, cpuMaximum,
                            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                            latency.p99Nanos(), "CPU_HOLD", "holding at fixed concurrency", true));
                    System.out.printf(Locale.ROOT,
                            "CAPACITY_CPU_HOLD_BEGIN mode=%s stage=%d producers=%d consumers=%d hold_ms=%d trigger_cpu=%.3f%n",
                            mode, stage, concurrency, concurrency, CPU_HOLD_MILLIS, cpu);
                }
                if (holdCompleted) {
                    reason = "CPU_SATURATION";
                    detail = "CPU saturation observed; completed " + CPU_HOLD_MILLIS + " ms at fixed concurrency";
                    break;
                }
                MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                heapUsed = usage.getUsed();
                if (System.nanoTime() >= nextDiagnostic) {
                    nextDiagnostic += 1_000_000_000L;
                    System.out.printf(Locale.ROOT,
                            "CAPACITY_SAMPLE mode=%s stage=%d elapsed_ms=%d produced=%d consumed=%d cpu=%.3f heap_mb=%.1f%n",
                            mode, stage, (System.nanoTime() - started) / 1_000_000, produced.sum(), consumed.sum(),
                            cpu, heapUsed / (1024.0 * 1024));
                }
            }
            elapsedNanos = Math.max(1, System.nanoTime() - started);
            if (reason == null && cpuHold.isHolding() && cpuHold.sample(System.nanoTime(), lastCpu)) {
                reason = "CPU_SATURATION";
                detail = "CPU saturation observed; completed " + CPU_HOLD_MILLIS + " ms at fixed concurrency";
            }
            cpuAverage = cpuSamples == 0 ? 0 : cpuTotal / cpuSamples;
            if (outOfMemory.get()) reason = "JVM_OOM";
            if (workerFailure.get() != null && (reason == null || "CPU_SATURATION".equals(reason))) {
                reason = "BROKER_OR_CLIENT_LIMIT";
                detail = workerFailure.get().toString();
            }
            if (cpuHold.isHolding()) {
                long holdElapsedNanos = Math.max(1, System.nanoTime() - cpuHold.startedNanos());
                double seconds = holdElapsedNanos / 1_000_000_000.0;
                int samples = cpuSamples - holdSamplesBaseline;
                long heldProduced = produced.sum() - holdProduced;
                long heldConsumed = consumed.sum() - holdConsumed;
                System.out.printf(Locale.ROOT,
                        "CAPACITY_CPU_HOLD_END mode=%s stage=%d duration_s=%.2f duration_ms=%d produced=%d produce_per_s=%.0f " +
                                "consumed=%d consume_per_s=%.0f cpu_avg=%.3f completed=%s stop=%s%n",
                        mode, stage, seconds, holdElapsedNanos / 1_000_000, heldProduced, heldProduced / seconds,
                        heldConsumed, heldConsumed / seconds,
                        samples == 0 ? lastCpu : (cpuTotal - holdCpuBaseline) / samples,
                        "CPU_SATURATION".equals(reason), reason);
            }
        } catch (OutOfMemoryError exhausted) {
            stopReporter.heapExhausted();
        } finally {
            running.set(false);
            if (outOfMemory.get()) {
                workers.shutdownNow();
                workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                workers.shutdown();
                if (!workers.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (reason == null || "CPU_SATURATION".equals(reason)) {
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
                cpuAverage, cpuMaximum, heapUsed, latency.p99Nanos(), reason, detail, cpuHold.isHolding());
    }

    private static void produceUntilStopped(SetuClient client, String topic, AtomicBoolean running,
                                            AtomicBoolean outOfMemory, AtomicReference<Throwable> failure,
                                            LongAdder produced, LatencyHistogram latency) {
        try {
            while (running.get()) {
                long start = System.nanoTime();
                var response = client.produceBatch(topic, 0, PRODUCE_BATCH);
                if (response.errorCode() != ErrorCode.NONE)
                    throw new IOException("produce rejected: " + response.errorCode() + " " + response.errorMessage());
                produced.add(PRODUCE_BATCH.size());
                latency.record(System.nanoTime() - start);
            }
        } catch (OutOfMemoryError exhausted) {
            stopReporter.heapExhausted();
        } catch (Throwable problem) {
            if (running.get() && failure.compareAndSet(null, problem)) {
                System.err.println("CAPACITY_REQUEST_FAILURE operation=produce topic=" + topic);
                problem.printStackTrace(System.err);
            }
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
            stopReporter.heapExhausted();
        } catch (Throwable problem) {
            if (running.get() && failure.compareAndSet(null, problem)) {
                System.err.println("CAPACITY_REQUEST_FAILURE operation=fetch_ack topic=" + topic + " group=" + group
                        + " member=" + member + " offset=" + offset);
                problem.printStackTrace(System.err);
            }
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
        printStage(mode, result, "CAPACITY_STAGE");
    }

    private static void printStage(StorageMode mode, StageResult result, String marker) {
        double seconds = result.elapsedNanos / 1_000_000_000.0;
        System.out.printf(Locale.ROOT,
                "%s mode=%s stage=%d producers=%d consumers=%d duration_s=%.2f " +
                        "produced=%d produce_per_s=%.0f consumed=%d consume_per_s=%.0f " +
                        "cpu_avg=%.3f cpu_peak=%.3f heap_mb=%.1f p99_ms=%.3f stop=%s%n",
                marker, mode, result.stage, result.concurrency, result.concurrency, seconds,
                result.produced, result.produced / seconds, result.consumed, result.consumed / seconds,
                result.cpuAverage, result.cpuMaximum, result.heapUsed / (1024.0 * 1024),
                result.p99Nanos / 1_000_000.0, result.reason == null ? "ramp" : result.reason);
        System.out.flush();
        long gcMillis = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
        System.out.printf("CAPACITY_DIAGNOSTICS mode=%s stage=%d live_threads=%d gc_total_ms=%d%n",
                mode, result.stage, ManagementFactory.getThreadMXBean().getThreadCount(), gcMillis);
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

    private static int configuredInt(String property, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(System.getProperty(property, Integer.toString(fallback)));
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(property + " must be between " + minimum + " and " + maximum);
        return value;
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
                               String reason, String detail, boolean cpuHoldStarted) { }
}
