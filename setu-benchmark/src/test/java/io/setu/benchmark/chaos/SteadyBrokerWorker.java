package io.setu.benchmark.chaos;

import io.setu.core.broker.DefaultBroker;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import io.setu.storage.log.StorageMode;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

/** Independent broker JVM for the fixed-rate benchmark; clients never share its CPU or heap metrics. */
public final class SteadyBrokerWorker {
    public static void main(String[] args) throws Exception {
        BenchmarkRuntime.verifyZgc(SteadyBrokerWorker.class);
        StorageMode mode = StorageMode.valueOf(args[0]);
        Path control = Path.of(args[1]);
        Path data = args.length > 2 ? Path.of(args[2]) : control.resolve("data");
        try (var broker = new DefaultBroker(mode, mode == StorageMode.FILE ? data : null, 1024);
             var server = new TcpServer(new java.net.InetSocketAddress("127.0.0.1", 0),
                     new FramedConnectionHandler(new BrokerRequestHandler(broker)))) {
            server.start();
            Files.writeString(control.resolve("ready"), Integer.toString(server.localAddress().getPort()));
            var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long started = 0, cpuStarted = 0, peakHeap = 0;
            while (!Files.exists(control.resolve("shutdown"))) {
                if (started == 0 && Files.exists(control.resolve("measure.start"))) {
                    started = System.nanoTime(); cpuStarted = os.getProcessCpuTime();
                }
                if (started != 0) peakHeap = Math.max(peakHeap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                if (started != 0 && Files.exists(control.resolve("measure.stop"))) {
                    long elapsed = System.nanoTime() - started;
                    double cpu = (os.getProcessCpuTime() - cpuStarted) / (double) elapsed / Runtime.getRuntime().availableProcessors();
                    System.out.printf(java.util.Locale.ROOT,
                            "STEADY_BROKER mode=%s cpus=%d cpu_avg=%.4f sampled_peak_heap_mb=%.1f retained_log_bytes=%d%n",
                            mode, Runtime.getRuntime().availableProcessors(), cpu, peakHeap / (1024.0 * 1024), broker.retainedInMemoryBytes());
                    System.out.flush();
                    Files.writeString(control.resolve("measure.done"), "done");
                    started = 0;
                    // There is one measured stage per broker process.
                    while (!Files.exists(control.resolve("shutdown"))) Thread.sleep(20);
                }
                Thread.sleep(20);
            }
        }
    }
}
