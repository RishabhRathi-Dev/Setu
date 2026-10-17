package io.setu.storage.log;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

/** Compare real mapped data barriers on two filesystems with identical offered load. */
public final class StorageForceProbe {
    private static final int BATCH_BYTES = 256 * 1024;
    private static final long BLOCK_BYTES = 64L * 1024 * 1024;
    private static final long INTERVAL = 6_400_000; // Two writers, 256 records/batch: 80K records/s.

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            Path root = Files.createDirectories(Path.of(arg));
            String filesystem = Files.getFileStore(root).type();
            if (List.of("tmpfs", "ramfs").contains(filesystem))
                throw new IllegalArgumentException("Durable storage probe requires a disk filesystem");
            for (boolean metadata : new boolean[]{true, false}) {
                long start = System.nanoTime() + 100_000_000;
                try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = workers.submit(() -> run(root, metadata, start));
                    var second = workers.submit(() -> run(root, metadata, start));
                    List<Sample> samples = new ArrayList<>(first.get());
                    samples.addAll(second.get());
                    long count = samples.size();
                    double elapsed = (samples.stream().mapToLong(Sample::finish).max().orElseThrow() - start) / 1e9;
                    System.out.printf(Locale.ROOT,
                            "STORAGE_FORCE_PROBE root=%s filesystem=%s metadata=%s writers=2 offered_records_per_s=80000 batch_records=256 samples=%d measured_records_per_s=%.3f force_p99_ms=%.3f scheduled_p99_ms=%.3f max_force_ms=%.3f%n",
                            root, filesystem, metadata, count, count * 256 / elapsed,
                            percentile(samples.stream().map(Sample::force).toList(), .99),
                            percentile(samples.stream().map(Sample::scheduled).toList(), .99),
                            samples.stream().mapToLong(Sample::force).max().orElseThrow() / 1e6);
                }
            }
        }
    }

    private static List<Sample> run(Path root, boolean metadata, long start) throws Exception {
        Path data = Files.createTempFile(root, "storage-force-", ".bin");
        List<Sample> samples = new ArrayList<>();
        byte[] payload = new byte[BATCH_BYTES];
        java.util.Arrays.fill(payload, (byte) 1);
        MemorySegment source = MemorySegment.ofArray(payload);
        try (var writer = FileChannel.open(data, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var sync = FileChannel.open(data, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            Arena arena = Arena.ofConfined();
            try {
                MemorySegment mapping = writer.map(FileChannel.MapMode.READ_WRITE, 0, BLOCK_BYTES, arena);
                long position = 4096, blockStart = 0;
                long due = start;
                while (System.nanoTime() < start + 10_000_000_000L) {
                    long remaining;
                    while ((remaining = due - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
                    if (position + BATCH_BYTES > BLOCK_BYTES) {
                        arena.close();
                        arena = Arena.ofConfined();
                        blockStart += BLOCK_BYTES;
                        mapping = writer.map(FileChannel.MapMode.READ_WRITE, blockStart, BLOCK_BYTES, arena);
                        position = 4096;
                    }
                    mapping.set(ValueLayout.JAVA_LONG, 0, due); // Existing header/index page.
                    MemorySegment.copy(source, 0, mapping, position, BATCH_BYTES);
                    position += BATCH_BYTES;
                    long forcing = System.nanoTime();
                    sync.force(metadata); // Real disk barrier through a different descriptor.
                    long finish = System.nanoTime();
                    samples.add(new Sample(finish - forcing, Math.max(0, finish - due), finish));
                    due += INTERVAL; // Never reset/drop scheduling backlog.
                }
            } finally { arena.close(); }
        } finally { Files.delete(data); }
        return samples;
    }

    private static double percentile(List<Long> samples, double percentile) {
        return samples.stream().sorted().toList().get((int) Math.ceil(samples.size() * percentile) - 1) / 1e6;
    }
    private record Sample(long force, long scheduled, long finish) { }
}
