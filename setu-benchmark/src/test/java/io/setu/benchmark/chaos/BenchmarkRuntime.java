package io.setu.benchmark.chaos;

import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.util.stream.Collectors;

/** Child processes must verify their collector; Maven JVM flags are not inherited by ProcessBuilder. */
final class BenchmarkRuntime {
    private BenchmarkRuntime() { }

    static java.util.List<String> childCommand(String executable, String... arguments) {
        var command = new java.util.ArrayList<String>();
        command.add(executable);
        command.addAll(io.setu.storage.runtime.JvmOptions.arguments());
        command.addAll(java.util.List.of(arguments));
        return command;
    }

    static void verifyZgc(Class<?> worker) {
        var collectors = ManagementFactory.getGarbageCollectorMXBeans();
        if (collectors.stream().noneMatch(gc -> gc.getName().startsWith("ZGC")))
            throw new IllegalStateException("Benchmark worker requires -XX:+UseZGC");
        String deduplication = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                .getVMOption("UseStringDeduplication").getValue();
        if (!deduplication.equals("true"))
            throw new IllegalStateException("Benchmark worker requires -XX:+UseStringDeduplication");
        System.out.printf("BENCHMARK_RUNTIME worker=%s string_dedup=%s gc=%s%n", worker.getSimpleName(), deduplication,
                collectors.stream().map(GarbageCollectorMXBean::getName)
                        .map(name -> name.replace(' ', '_')).collect(Collectors.joining(",")));
    }
}
