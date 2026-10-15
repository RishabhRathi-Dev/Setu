package io.setu.benchmark.chaos;

import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Kills a real broker process and checks recovery through the normal broker constructor. */
class BrokerCrashChaosTests {
    private static final int RECORDS = 4_096;

    @TempDir Path tempDir;

    @Test
    void forceKillsBrokerThenRecoversAcknowledgedChronicleRecords() throws Exception {
        Path data = tempDir.resolve("crash-data");
        Path ready = tempDir.resolve("ready.flag");
        Path output = Files.createDirectories(tempDir.resolve("logs")).resolve("child.log");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(BenchmarkRuntime.childCommand(javaExecutable,
                "-DSETU_LOG_DIR=" + output.getParent().toAbsolutePath(),
                "-Dlogback.configurationFile=" + System.getProperty("logback.configurationFile"), "-cp", classpath,
                BrokerCrashWorker.class.getName(), data.toString(), ready.toString(), Integer.toString(RECORDS)))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (!Files.exists(ready) && System.nanoTime() < deadline && child.isAlive())
                Thread.sleep(20);
            assertTrue(Files.exists(ready), "child broker did not finish appending: " + readOutput(output));

            child.destroyForcibly(); // do not call DefaultBroker.close() in the child
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "forcibly terminated broker did not exit");
            assertNotEquals(0, child.exitValue(), "the child should exit abnormally");

            try (DefaultBroker recovered = new DefaultBroker(data, 1024)) {
                var firstPage = recovered.execute(new BrokerRequest(10, Command.FETCH, "crash-recovery", 0,
                        0, 15 * 1024 * 1024, null, 0, null, null));
                assertEquals(RECORDS, firstPage.records().size());
                assertEquals(0, firstPage.records().getFirst().offset());
                assertEquals(RECORDS - 1, firstPage.records().getLast().offset());
                var appended = recovered.execute(new BrokerRequest(11, Command.PRODUCE, "crash-recovery", 0,
                        -1, -1, "after-recovery".getBytes(), 0, null, null));
                assertEquals(RECORDS, appended.offset());
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String readOutput(Path output) {
        try { return Files.exists(output) ? Files.readString(output) : "no child output"; }
        catch (IOException ignored) { return "unable to read child output"; }
    }
}
