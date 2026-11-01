package io.setu.benchmark.chaos;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

/** Probes the JVM's effective host/container resources before sizing a local exhaustion worker. */
public final class BenchmarkHostResources {
    private BenchmarkHostResources() { }

    public static void main(String[] args) {
        var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        System.out.printf("REDLINE_HOST processors=%d memory_available_bytes=%d%n",
                Runtime.getRuntime().availableProcessors(), os.getFreeMemorySize());
    }
}
