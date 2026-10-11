package io.setu.benchmark.chaos;

import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;

import java.nio.file.Files;
import java.nio.file.Path;

/** Child JVM held open by BrokerCrashChaosTests until it is forcibly terminated. */
public final class BrokerCrashWorker {
    private BrokerCrashWorker() { }

    public static void main(String[] args) throws Exception {
        BenchmarkRuntime.verifyZgc(BrokerCrashWorker.class);
        Path data = Path.of(args[0]);
        Path ready = Path.of(args[1]);
        int recordCount = Integer.parseInt(args[2]);
        DefaultBroker broker = new DefaultBroker(data, 1024);
        broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "crash-recovery", -1, -1,
                -1, null, 1, null, null));
        for (int i = 0; i < recordCount; i++) {
            broker.execute(new BrokerRequest(i + 2, Command.PRODUCE, "crash-recovery", 0,
                    -1, -1, ("record-" + i).getBytes(), 0, null, null));
        }
        Files.writeString(ready, "all records appended");
        Thread.sleep(60_000); // parent kills this JVM without invoking broker.close()
    }
}
