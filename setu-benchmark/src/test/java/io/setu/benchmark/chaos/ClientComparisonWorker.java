package io.setu.benchmark.chaos;

import io.setu.client.AsyncSetuClient;
import io.setu.client.SetuClient;
import io.setu.core.protocol.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Closed-loop comparison with identical submission concurrency, payloads and background consumers. */
public final class ClientComparisonWorker {
    private static final int FRAME_BYTES = 16 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        BenchmarkRuntime.verifyZgc(ClientComparisonWorker.class);
        int port = Integer.parseInt(args[0]), pool = Integer.parseInt(args[2]);
        String kind = args[1];
        int batchSize = Integer.parseInt(args[3]), partitions = Integer.parseInt(args[4]);
        int warmup = Integer.parseInt(args[5]), seconds = Integer.parseInt(args[6]);
        int submitters = Integer.parseInt(args[7]), queue = Integer.parseInt(args[8]);
        Path capture = Path.of(args[9]);
        int backlogRecords = Integer.parseInt(args[10]);
        Semaphore backlog = backlogRecords == 0 ? null : new Semaphore(backlogRecords);
        var produced = new AtomicLong();
        var delivered = new AtomicLong();
        var stopping = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var connections = new ArrayList<SetuClient>();
        var available = new ArrayBlockingQueue<SetuClient>(pool);
        try (var admin = connect(port);
             var workers = Executors.newVirtualThreadPerTaskExecutor();
             var async = new AsyncSetuClient(() -> connect(port), pool, queue, 0);
             var recording = new Recording();
             var stopConsumers = (AutoCloseable) () -> stopping.set(true)) {
            success(admin.createTopic("comparison", partitions));
            success(admin.joinGroup("comparison", "comparison", "reader"));
            int count = kind.equals("SHARED") ? 1 : kind.equals("POOLED") ? pool : 0;
            for (int index = 0; index < count; index++) {
                var client = connect(port); connections.add(client); available.add(client);
            }
            var consumers = new ArrayList<Future<?>>();
            for (int partition = 0; partition < partitions; partition++) {
                int selected = partition;
                consumers.add(workers.submit(() -> {
                    try (var client = connect(port)) {
                        long offset = 0;
                        while (!stopping.get()) {
                            var page = client.fetch("comparison", "reader", "comparison", selected, offset, 1024 * 1024, 25);
                            success(page);
                            for (var record : page.records()) {
                                if (record.offset() != offset++) throw new IOException("Consumer offset gap");
                            }
                            if (!page.records().isEmpty()) {
                                success(client.acknowledge("comparison", "reader", "comparison", selected, offset));
                                delivered.addAndGet(page.records().size());
                                if (backlog != null) backlog.release(page.records().size());
                            }
                        }
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                }));
            }
            List<byte[]> batch = java.util.stream.IntStream.range(0, batchSize)
                    .mapToObj(index -> new byte[BenchmarkSettings.recordBytes()]).toList();
            // Start JFR before warmup so recorder initialization is outside the measured window.
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "300/s").withStackTrace();
            recording.enable("jdk.GCPhasePause");
            recording.setMaxSize(128L * 1024 * 1024);
            recording.start();
            phase(workers, kind, available, async, batch, partitions, submitters, warmup, produced, failure, backlog);
            drain(produced, delivered, failure);
            long before = produced.get();
            Files.writeString(capture.resolve("measure.start"), "start");
            Instant start = Instant.now();
            long began = System.nanoTime();
            var result = phase(workers, kind, available, async, batch, partitions, submitters, seconds, produced, failure, backlog);
            long elapsed = System.nanoTime() - began;
            Instant end = Instant.now();
            Files.writeString(capture.resolve("measure.stop"), "stop");
            long drainStarted = System.nanoTime();
            drain(produced, delivered, failure);
            double drainMillis = (System.nanoTime() - drainStarted) / 1_000_000.0;
            stopping.set(true);
            for (var consumer : consumers) consumer.get(10, TimeUnit.SECONDS);
            success(admin.leaveGroup("comparison", "reader"));
            recording.stop();
            Path jfr = capture.resolve("client.jfr");
            recording.dump(jfr);
            long allocated = 0, samples = 0;
            double gcMillis = 0;
            try (var events = new RecordingFile(jfr)) {
                while (events.hasMoreEvents()) {
                    var event = events.readEvent();
                    if (event.getStartTime().isBefore(start) || event.getStartTime().isAfter(end)) continue;
                    if (event.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
                        allocated += event.getLong("weight"); samples++;
                    } else if (event.getEventType().getName().equals("jdk.GCPhasePause"))
                        gcMillis += event.getDuration().toNanos() / 1_000_000.0;
                }
            }
            if (result.histogram().getTotalCount() == 0 || allocated == 0 || samples == 0)
                throw new IOException("Missing measured requests or allocation samples");
            long records = produced.get() - before;
            String report = String.format(Locale.ROOT,
                    "CLIENT_COMPARISON kind=%s pool=%d producer_connections=%d submitters=%d queue=%d max_backlog_records=%d batch=%d partitions=%d " +
                    "record_bytes=%d warmup_s=%d requested_s=%d measurement_start=%s measurement_end=%s elapsed_s=%.6f records=%d delivered=%d " +
                    "records_per_s=%.3f p99_ms=%.6f rejections=%d min_lane_requests=%d max_lane_requests=%d errors=0 drain_ms=%.3f " +
                    "client_alloc_estimated_bytes=%d client_alloc_samples=%d client_alloc_estimated_bytes_per_record=%.3f " +
                    "client_gc_pause_ms=%.3f qualified=false%n",
                    kind, pool, kind.equals("SHARED") ? 1 : pool, submitters, queue, backlogRecords, batchSize, partitions,
                    BenchmarkSettings.recordBytes(), warmup, seconds, start, end,
                    elapsed / 1e9, records, delivered.get() - before, records / (elapsed / 1e9),
                    result.histogram().getValueAtPercentile(99) / 1e6, result.rejected(),
                    result.minLaneRequests(), result.maxLaneRequests(), drainMillis,
                    allocated, samples, allocated / (double) records, gcMillis);
            Files.writeString(capture.resolve("result.txt"), report);
            System.out.print(report);
        } finally {
            stopping.set(true);
            for (var client : connections) client.close();
        }
    }

    private static ComparisonPhase phase(ExecutorService workers, String kind,
            ArrayBlockingQueue<SetuClient> available, AsyncSetuClient async, List<byte[]> batch,
            int partitions, int submitters, int seconds, AtomicLong produced,
            AtomicReference<Throwable> failure, Semaphore backlog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        var tasks = new ArrayList<Future<ComparisonPhase>>();
        for (int lane = 0; lane < submitters; lane++) {
            int id = lane;
            tasks.add(workers.submit(() -> {
                var histogram = new Histogram(TimeUnit.MINUTES.toNanos(1), 3);
                long rejected = 0, sequence = id;
                while (System.nanoTime() < deadline && failure.get() == null) {
                    int partition = (int) (sequence++ % partitions);
                    long start = System.nanoTime();
                    boolean reserved = false, appended = false;
                    try {
                        if (backlog != null) {
                            while (!backlog.tryAcquire(batch.size(), 100, TimeUnit.MILLISECONDS)) {
                                if (System.nanoTime() >= deadline || failure.get() != null)
                                    return new ComparisonPhase(histogram, rejected, histogram.getTotalCount(), histogram.getTotalCount());
                            }
                            reserved = true;
                        }
                        BrokerResponse response;
                        if (kind.equals("SHARED") || kind.equals("POOLED")) {
                            var client = available.take();
                            try { response = client.produceBatch("comparison", partition, batch); }
                            finally { available.add(client); }
                        } else if (kind.equals("ASYNC_BATCH")) {
                            response = async.produceBatch("comparison", partition, batch).get();
                        } else {
                            response = async.request(new BrokerRequest(0, Command.PRODUCE_BATCH, "comparison",
                                    partition, -1, -1, null, 0, null, null, batch)).get();
                        }
                        success(response);
                        appended = true;
                        produced.addAndGet(batch.size());
                        histogram.recordValue(System.nanoTime() - start);
                    } catch (ExecutionException error) {
                        if (!(error.getCause() instanceof RejectedExecutionException)) throw error;
                        rejected++;
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    } finally {
                        if (reserved && !appended) backlog.release(batch.size());
                    }
                }
                return new ComparisonPhase(histogram, rejected, histogram.getTotalCount(), histogram.getTotalCount());
            }));
        }
        var histogram = new Histogram(TimeUnit.MINUTES.toNanos(1), 3);
        long rejected = 0, minimum = Long.MAX_VALUE, maximum = 0;
        for (var task : tasks) {
            var result = task.get(seconds + 40L, TimeUnit.SECONDS);
            histogram.add(result.histogram()); rejected += result.rejected();
            minimum = Math.min(minimum, result.minLaneRequests()); maximum = Math.max(maximum, result.maxLaneRequests());
        }
        if (failure.get() != null) throw new IOException("Consumer failed", failure.get());
        return new ComparisonPhase(histogram, rejected, minimum, maximum);
    }

    private static void drain(AtomicLong produced, AtomicLong delivered, AtomicReference<Throwable> failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (delivered.get() < produced.get() && failure.get() == null && System.nanoTime() < deadline) Thread.sleep(5);
        if (failure.get() != null) throw new IOException("Consumer failed", failure.get());
        if (delivered.get() != produced.get()) throw new IOException("Consumer did not drain every accepted record");
    }

    private static SetuClient connect(int port) throws IOException {
        return SetuClient.connectPlain("127.0.0.1", port, 5000, 35_000, FRAME_BYTES);
    }

    private static void success(BrokerResponse response) throws IOException {
        if (response.errorCode() != ErrorCode.NONE) throw new IOException(response.errorCode() + ": " + response.errorMessage());
    }
}
