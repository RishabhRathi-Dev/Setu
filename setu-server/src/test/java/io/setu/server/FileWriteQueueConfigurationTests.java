package io.setu.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileWriteQueueConfigurationTests {
    @TempDir Path directory;

    @Test void invalidQueueLimitsFailBeforeOpeningTheDataDirectory() throws Exception {
        for (String property : new String[]{"file.write.queue.max.bytes", "file.write.queue.max.requests"}) {
            Path data = directory.resolve(property);
            Path config = directory.resolve("server.properties");
            Files.writeString(config, "data.dir=" + data.toString().replace('\\', '/') + "\n" + property + "=-1\n");
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SetuServerMain.main(new String[]{"--config", config.toString()}));
            assertTrue(failure.getMessage().contains("FILE write queue limits"));
            assertFalse(Files.exists(data));
        }
    }

    @Test void requestCountCannotOverflowIntoAnUnlimitedSetting() throws Exception {
        Path config = directory.resolve("server.properties");
        Files.writeString(config, "file.write.queue.max.requests=2147483648\n");
        assertThrows(ArithmeticException.class, () -> SetuServerMain.main(new String[]{"--config", config.toString()}));
    }
}
