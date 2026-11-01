package io.setu.benchmark.chaos;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepares the terminal marker while allocations are healthy; OOM must bypass logging and cleanup. */
final class CapacityStopReporter {
    private final String mode;
    private final FileOutputStream stdout = new FileOutputStream(FileDescriptor.out);
    private final Runtime runtime = Runtime.getRuntime();
    private final AtomicBoolean reported = new AtomicBoolean();
    private volatile byte[] marker;
    // Released before emitting the marker so the VM has room to complete low-level I/O.
    private volatile byte[] emergencyReserve = new byte[4 * 1024 * 1024];

    CapacityStopReporter(String mode) {
        this.mode = mode;
        prepareStage(0, 0);
    }

    void prepareStage(int stage, int concurrency) {
        marker = ("\nCAPACITY_STOP mode=" + mode + " reason=JVM_OOM stage=" + stage
                + " producers=" + concurrency + " consumers=" + concurrency
                + " detail=JVM heap exhausted; inspect the preceding stage and worker logs\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    void heapExhausted() {
        if (!reported.compareAndSet(false, true)) {
            awaitHeapExhaustionIfReported();
            return;
        }
        emergencyReserve = null;
        try { stdout.write(marker); stdout.flush(); }
        catch (IOException ignored) { /* A broken stdout cannot be recovered during heap exhaustion. */ }
        finally {
            CapacityJfr.emergencyDump();
            runtime.halt(42);
        }
    }

    boolean isHeapExhausted() { return reported.get(); }

    /** The reporting thread owns the terminal marker and exit while its best-effort dump runs. */
    void awaitHeapExhaustionIfReported() {
        while (reported.get()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
    }
}
