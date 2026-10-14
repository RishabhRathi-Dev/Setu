package io.setu.benchmark.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Check effective JVM/JUnit settings, rather than just the launcher's claimed budget. */
@EnabledIfSystemProperty(named = "setu.benchmark.expected.tmpdir", matches = ".+")
class BenchmarkEnvironmentTests {
    @TempDir Path temporary;

    @Test void usesRequestedFilesystemAndBoundedSurefireHeap() throws Exception {
        Path expected = Path.of(System.getProperty("setu.benchmark.expected.tmpdir")).toRealPath();
        assertTrue(temporary.toRealPath().startsWith(expected),
                () -> "JUnit temp directory " + temporary + " is outside requested data directory " + expected);
        assertTrue(Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024,
                "Surefire must honor the launcher's 256 MiB heap at JVM startup");
    }
}
