package io.setu.server.observability;

import java.lang.management.ManagementFactory;

/** Bounded JVM/process samples. Unsupported MXBean measurements are omitted. */
final class RuntimeMetrics {
    private RuntimeMetrics() { }

    static void appendTo(StringBuilder text) {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        gauge(text, "process_start_time_seconds", runtime.getStartTime() / 1e3);
        gauge(text, "process_uptime_seconds", runtime.getUptime() / 1e3);
        gauge(text, "jvm_heap_used_bytes", heap.getUsed());
        gauge(text, "jvm_heap_committed_bytes", heap.getCommitted());
        gauge(text, "jvm_heap_max_bytes", heap.getMax());
        gauge(text, "jvm_nonheap_used_bytes", ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        gauge(text, "jvm_platform_threads", ManagementFactory.getThreadMXBean().getThreadCount());
        gauge(text, "jvm_available_processors", Runtime.getRuntime().availableProcessors());
        long collections = 0, millis = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, gc.getCollectionCount());
            millis += Math.max(0, gc.getCollectionTime());
        }
        sample(text, "jvm_gc_collections_total", "counter", collections);
        sample(text, "jvm_gc_collection_seconds_total", "counter", millis / 1e3);
        for (var pool : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            if (pool.getName().equals("direct") || pool.getName().equals("mapped"))
                gauge(text, "jvm_" + pool.getName() + "_buffer_bytes", pool.getMemoryUsed());
        }
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            long cpuNanos = os.getProcessCpuTime();
            sample(text, "process_cpu_seconds_total", "counter", cpuNanos < 0 ? -1 : cpuNanos / 1e9);
        }
    }

    private static void gauge(StringBuilder text, String name, double value) { sample(text, name, "gauge", value); }

    private static void sample(StringBuilder text, String name, String type, double value) {
        if (value < 0 || !Double.isFinite(value)) return;
        text.append("# TYPE setu_").append(name).append(' ').append(type).append('\n');
        text.append("setu_").append(name).append(' ').append(value).append('\n');
    }
}
