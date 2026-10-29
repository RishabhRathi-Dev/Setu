package io.setu.storage.uring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit native opt-in fails on denied/unsupported kernels rather than silently skipping them. */
@Timeout(30)
class NativeRingTests {
    @TempDir Path root;
    @BeforeEach void enabled() {
        assumeTrue(Boolean.getBoolean("setu.uring.tests"), "Use -Dsetu.uring.tests=true on Linux to exercise the actual kernel");
        assertTrue(UringFileIo.isSupportedPlatform(), "Native tests require supported Linux");
    }
    @Test void writesHeapDirectSlicesAndGrowthWithMappedHeaders() throws Exception {
        Path path = Files.createFile(root.resolve("records"));
        byte[] data = new byte[150_007]; new Random(13).nextBytes(data);
        try (var io = UringFileIo.create(new UringOptions(4, 4096, 16, 65536));
             var file = io.open(path);
             var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer direct = UringFileIo.allocateBuffer(data.length + 16).position(7).put(data).flip().position(7);
            file.writeFully(direct, 16);
            assertEquals(direct.limit(), direct.position());
            file.force(true);
            assertEquals(data.length + 16, Files.size(path));
            assertArrayEquals(data, java.util.Arrays.copyOfRange(Files.readAllBytes(path), 16, data.length + 16));
            var mapping = channel.map(FileChannel.MapMode.READ_WRITE, 0, Files.size(path));
            mapping.putLong(0, 0x123456789abcdefL);
            file.writeFully(ByteBuffer.wrap(new byte[]{42, 43}), 16);
            file.force(false);
            ByteBuffer recovered = ByteBuffer.wrap(Files.readAllBytes(path));
            assertEquals(0x123456789abcdefL, recovered.getLong());
            assertEquals(42, recovered.get(16));
            assertEquals(43, recovered.get(17));
            // Caller-managed lifetimes cannot be borrowed by the kernel after
            // an exceptional return; both arena kinds use the copy fallback.
            for (boolean confined : new boolean[]{true, false}) {
                try (var arena = confined ? Arena.ofConfined() : Arena.ofShared()) {
                    ByteBuffer scoped = arena.allocate(32).asByteBuffer();
                    scoped.put(new byte[]{4, 5, 6, 7}).flip().position(1);
                    file.writeFully(scoped.asReadOnlyBuffer(), 20);
                    file.force(false);
                }
                assertArrayEquals(new byte[]{5, 6, 7}, java.util.Arrays.copyOfRange(Files.readAllBytes(path), 20, 23));
            }
        }
    }
    @Test void independentFilesCompleteTogetherAndRepeatedCloseLeavesNoOwnerThreads() throws Exception {
        long original = owners();
        for (int round = 0; round < 8; round++) {
            try (var io = UringFileIo.create(new UringOptions(4, 4096, 16, 65536));
                 var callers = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < 8; index++) {
                    Path path = Files.createFile(root.resolve(round + "-" + index));
                    tasks.add(callers.submit(() -> {
                        try (var file = io.open(path)) {
                            ByteBuffer data = UringFileIo.allocateBuffer(8).put(new byte[]{0, 1, 2, 3}).flip();
                            for (int n = 0; n < 20; n++) {
                                file.writeFully(data.asReadOnlyBuffer().position(1), n * 3L);
                                if (n % 4 == 0) Thread.sleep(1); // Exercise wakeups after idle transitions.
                            }
                            file.force(true);
                        }
                        assertEquals(60, Files.size(path));
                        return null;
                    }));
                }
                for (var task : tasks) task.get(20, TimeUnit.SECONDS);
            }
        }
        assertEquals(original, owners());
    }
    @Test void nativeWriteAndForceErrorsPropagateAndMissingOpenDoesNotKillExecutor() throws Exception {
        try (var io = UringFileIo.create(new UringOptions(2, 4096, 8, 8192))) {
            assertThrows(IOException.class, () -> io.open(root.resolve("missing")));
            try (var full = io.open(Path.of("/dev/full"))) {
                assertThrows(IOException.class, () -> full.writeFully(ByteBuffer.wrap(new byte[]{1}), 0));
                assertThrows(IOException.class, () -> full.force(true));
            }
            try (var device = io.open(Path.of("/dev/null"))) {
                assertThrows(IOException.class, () -> device.force(true));
            }
            Path valid = Files.createFile(root.resolve("valid"));
            try (var file = io.open(valid)) { file.writeFully(ByteBuffer.wrap(new byte[]{9}), 0); file.force(true); }
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(valid));
        }
    }
    private static long owners() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("setu-io-uring-") && t.isAlive()).count();
    }
}
