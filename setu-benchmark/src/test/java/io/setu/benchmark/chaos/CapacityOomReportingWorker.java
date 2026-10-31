package io.setu.benchmark.chaos;

import java.util.ArrayList;

/** Tiny-heap fixture that exercises real allocation failure in caught and uncaught paths. */
final class CapacityOomReportingWorker {
    public static void main(String[] args) {
        BenchmarkRuntime.verifyZgc(CapacityOomReportingWorker.class);
        CapacityStopReporter reporter = new CapacityStopReporter(args[0]);
        reporter.prepareStage(3, 4);
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure instanceof OutOfMemoryError) reporter.heapExhausted();
            else {
                reporter.awaitHeapExhaustionIfReported();
                failure.printStackTrace(System.err);
                Runtime.getRuntime().halt(1);
            }
        });
        if (args[1].equals("unexpected")) throw new IllegalStateException("Unexpected fixture failure");
        if (args[1].equals("racing")) {
            // Hold the dump lock so an unrelated failure deterministically arrives after the OOM
            // decision but before its terminal exit. That failure must not replace exit 42.
            synchronized (CapacityJfr.class) {
                Thread.ofPlatform().start(reporter::heapExhausted);
                while (!reporter.isHeapExhausted()) Thread.onSpinWait();
                Thread.ofPlatform().start(() -> { throw new IllegalStateException("Concurrent cleanup failure"); });
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            }
            reporter.awaitHeapExhaustionIfReported();
        }
        var allocations = new ArrayList<byte[]>();
        try {
            while (true) allocations.add(new byte[1024 * 1024]);
        } catch (OutOfMemoryError failure) {
            if (args[1].equals("uncaught")) throw failure;
            reporter.heapExhausted();
        }
    }
}
