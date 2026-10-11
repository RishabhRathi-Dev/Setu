package io.setu.benchmark.chaos;

import io.setu.client.SetuClient;
import io.setu.core.protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Fixed offered rate, one delivery per record, separate produce/fetch/ack and scheduled-completion histograms. */
public final class SteadyLoadWorker {
    private static final int PRODUCERS = 4;
    private static final int BATCH = 32;
    private static final int RECORD_BYTES = 512;
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]), partitions = Integer.parseInt(args[1]);
        int rate = Integer.parseInt(args[2]), seconds = Integer.parseInt(args[3]);
        Path control = Path.of(args[4]);
        run(port, partitions, rate, 1, "warmup", null);
        run(port, partitions, rate, seconds, "steady", control);
    }
    private static SetuClient connect(int port) throws IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 5000, 5000, 16 * 1024 * 1024);
    }
    private static void success(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE) throw new IOException(response.errorCode() + ": " + response.errorMessage());
    }
    private static void run(int port, int partitions, int rate, int seconds, String topic, Path control) throws Exception {
        List<SetuClient> producers = new ArrayList<>();
        try (var admin = connect(port); var consumer = connect(port); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            success(admin.createTopic(topic, partitions));
            success(consumer.joinGroup(topic, topic, "reader"));
            for (int i = 0; i < PRODUCERS; i++) producers.add(connect(port));
            var produced = new AtomicLong(); var consumed = new AtomicLong();
            var measuredProduced = new AtomicLong(); var measuredConsumed = new AtomicLong();
            var errors = new AtomicLong(); var misses = new AtomicLong();
            var finished = new AtomicInteger(); var failure = new AtomicReference<Throwable>();
            var produceLatency = new Histogram(); var fetchLatency = new Histogram();
            var ackLatency = new Histogram(); var scheduleLatency = new Histogram();
            long interval = Math.max(1, (long) (BATCH * (double) PRODUCERS * 1_000_000_000 / rate));
            long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
            long deadline = start + TimeUnit.SECONDS.toNanos(seconds);
            if (control != null) {
                waitUntil(start);
                Files.writeString(control.resolve("measure.start"), "start");
                // Scheduling starts after the broker can observe the measurement signal.
                start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
                deadline = start + TimeUnit.SECONDS.toNanos(seconds);
            }
            final long beginning = start, end = deadline;
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < PRODUCERS; i++) {
                int id = i; SetuClient producer = producers.get(i);
                tasks.add(workers.submit(() -> {
                    try {
                        long sequence = 0;
                        for (long due = beginning + interval * id / PRODUCERS; due < end; due += interval) {
                            if (System.nanoTime() >= end) {
                                misses.addAndGet((end - due + interval - 1) / interval * BATCH); break;
                            }
                            waitUntil(due);
                            long requested = System.nanoTime();
                            var batch = new ArrayList<byte[]>(BATCH);
                            int partition = (int) ((sequence++ + id) % partitions);
                            for (int record = 0; record < BATCH; record++) batch.add(new byte[RECORD_BYTES]);
                            var response = producer.produceBatch(topic, partition, batch);
                            long completed = System.nanoTime();
                            produceLatency.record(completed - requested);
                            scheduleLatency.record(completed - due); // includes scheduling delay under overload
                            if (response.errorCode() == ErrorCode.NONE) {
                                produced.addAndGet(BATCH);
                                if (completed <= end) measuredProduced.addAndGet(BATCH);
                            }
                            else errors.incrementAndGet();
                        }
                    } catch (Throwable e) { failure.compareAndSet(null, e); errors.incrementAndGet(); }
                    finally { finished.incrementAndGet(); }
                }));
            }
            tasks.add(workers.submit(() -> {
                long[] offsets = new long[partitions];
                try {
                    waitUntil(beginning);
                    long drainDeadline = end + TimeUnit.SECONDS.toNanos(5);
                    while (System.nanoTime() < drainDeadline && (finished.get() < PRODUCERS || consumed.get() < produced.get())) {
                        for (int partition = 0; partition < partitions; partition++) {
                            long requested = System.nanoTime();
                            var response = consumer.fetch(topic, "reader", topic, partition, offsets[partition], 64 * 1024, 25);
                            fetchLatency.record(System.nanoTime() - requested);
                            success(response);
                            if (!response.records().isEmpty()) {
                                long next = response.records().getLast().offset() + 1;
                                if (response.records().getFirst().offset() != offsets[partition]) throw new IOException("delivery gap");
                                requested = System.nanoTime();
                                success(consumer.acknowledge(topic, "reader", topic, partition, next));
                                ackLatency.record(System.nanoTime() - requested);
                                consumed.addAndGet(response.records().size()); offsets[partition] = next;
                                if (System.nanoTime() <= end) measuredConsumed.addAndGet(response.records().size());
                            }
                        }
                    }
                } catch (Throwable e) { failure.compareAndSet(null, e); errors.incrementAndGet(); }
            }));
            for (var task : tasks) task.get(seconds + 15L, TimeUnit.SECONDS);
            long completed = System.nanoTime();
            success(consumer.leaveGroup(topic, "reader"));
            if (control != null) {
                Files.writeString(control.resolve("measure.stop"), "stop");
                System.out.printf(Locale.ROOT,
                        "STEADY_STAGE partitions=%d producers=%d consumers=1 offered_records_per_s=%d duration_s=%d " +
                        "produced=%d delivered=%d produce_per_s=%.1f deliver_per_s=%.1f missed_records=%d errors=%d drain_ms=%.3f " +
                        "produce_p99_ms=%.3f fetch_p99_ms=%.3f ack_p99_ms=%.3f scheduled_completion_p99_ms=%.3f%n",
                        partitions, PRODUCERS, rate, seconds, produced.get(), consumed.get(),
                        measuredProduced.get() / (double) seconds, measuredConsumed.get() / (double) seconds, misses.get(), errors.get(),
                        Math.max(0, completed - end) / 1_000_000.0, produceLatency.p99(), fetchLatency.p99(), ackLatency.p99(), scheduleLatency.p99());
            }
            if (failure.get() != null) throw new IOException("load worker failed", failure.get());
            if (consumed.get() != produced.get()) throw new IOException("consumer failed to drain accepted records");
        } finally { for (var producer : producers) producer.close(); }
    }
    private static void waitUntil(long due) {
        long remaining;
        while ((remaining = due - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
    }
    /** Fixed memory, logarithmic buckets with approximately 1% relative resolution; -1 means unavailable. */
    private static final class Histogram {
        private final AtomicLongArray buckets = new AtomicLongArray(2048);
        private final AtomicLong count = new AtomicLong();
        void record(long nanos) {
            int bucket = Math.min(2047, (int) Math.ceil(Math.log1p(Math.max(0, nanos) / 1000.0) / Math.log(1.01)));
            buckets.incrementAndGet(bucket); count.incrementAndGet();
        }
        double p99() {
            long total = count.get(); if (total == 0) return -1;
            long target = (long) Math.ceil(total * .99), seen = 0;
            for (int i = 0; i < buckets.length(); i++) {
                seen += buckets.get(i);
                if (seen >= target) return Math.expm1(i * Math.log(1.01)) / 1000;
            }
            return -1;
        }
    }
}
