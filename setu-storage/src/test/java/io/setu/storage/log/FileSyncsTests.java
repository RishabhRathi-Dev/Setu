package io.setu.storage.log;

import io.setu.storage.uring.UringFileIo;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(30)
class FileSyncsTests {
    @TempDir Path root;

    @Test void unsupportedPlatformsAndDeniedSetupChooseFallbackOnlyOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var unsupported = new FileSyncs.Pool(false, () -> { throw new AssertionError("Must not initialize native bindings"); });
        assertNull(unsupported.acquire());
        for (int errno : new int[]{1, 13, 38, 95}) {
            var denied = new FileSyncs.Pool(true, () -> { attempts.incrementAndGet(); throw new UringFileIo.UnavailableException(errno); });
            assertNull(denied.acquire());
            assertNull(denied.acquire());
        }
        assertEquals(4, attempts.get());
    }

    @Test void resourceAndIoFailuresDoNotSilentlySwitchBackends() {
        AtomicInteger attempts = new AtomicInteger();
        var failed = new FileSyncs.Pool(true, () -> { attempts.incrementAndGet(); throw new IOException("resource failure"); });
        assertThrows(IOException.class, failed::acquire);
        assertThrows(IOException.class, failed::acquire);
        assertEquals(2, attempts.get());
        assertThrows(IllegalArgumentException.class, () -> new UringFileIo.UnavailableException(12));
    }

    @Test void defaultNativeWritesAndMappedHeadersHaveRecordedDurabilityBarriers() throws Exception {
        nativeEnabled();
        Path path = Files.createFile(root.resolve("data.cq4"));
        Path capture = root.resolve("barriers.jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.FileForce").withThreshold(java.time.Duration.ZERO);
            recording.enable("io.setu.storage.FileForce").withThreshold(java.time.Duration.ZERO);
            recording.start();
            var sync = FileSyncs.open(path);
            try (sync; var writer = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var arena = Arena.ofConfined()) {
                Bytes<?> destination = Bytes.allocateElasticOnHeap();
                try {
                    var payload = FileSyncs.allocatePayload(4096);
                    assertTrue(payload.isDirect());
                    payload.putInt(42).position(0);
                    sync.write(destination, payload);
                    assertEquals(4096, destination.writePosition());
                } finally { destination.releaseLast(); }
                sync.force();
                writer.map(FileChannel.MapMode.READ_WRITE, 0, 4096, arena).set(ValueLayout.JAVA_BYTE, 0, (byte) 7);
                sync.force();
            }
            sync.close(); // No second pool release.
            assertThrows(IOException.class, sync::force);
            recording.stop(); recording.dump(capture);
        }
        assertEquals(7, Files.readAllBytes(path)[0]);
        var barriers = new ArrayList<String>();
        try (var input = new RecordingFile(capture)) {
            while (input.hasMoreEvents()) {
                var event = input.readEvent();
                String type = event.getEventType().getName();
                if ((type.equals("jdk.FileForce") || type.equals("io.setu.storage.FileForce"))
                        && path.toString().equals(event.getString("path"))) {
                    barriers.add(type + ":" + event.getBoolean("metaData"));
                    if (event.hasField("success")) assertTrue(event.getBoolean("success"));
                }
            }
        }
        assertEquals(List.of("io.setu.storage.FileForce:false", "jdk.FileForce:true"), barriers.stream().sorted().toList());
        Files.delete(path);
    }

    @Test void sharedOwnerSurvivesIntermediateCloseAndStopsAfterLastLease() throws Exception {
        nativeEnabled();
        AtomicInteger starts = new AtomicInteger();
        var pool = new FileSyncs.Pool(true, () -> { starts.incrementAndGet(); return UringFileIo.createWithBackpressure(); });
        var first = pool.acquire();
        var second = pool.acquire();
        assertSame(first, second);
        try (var file = first.open(Files.createFile(root.resolve("first")))) {
            pool.release();
            file.writeFully(UringFileIo.allocateBuffer(8).putLong(9).flip(), 0);
            file.force(true);
            pool.release();
            assertThrows(IOException.class, () -> file.force(false));
        }
        assertNotSame(first, pool.acquire());
        pool.release();
        assertEquals(2, starts.get());
    }

    private static void nativeEnabled() {
        assumeTrue(Boolean.getBoolean("setu.uring.tests"), "Native default verification requires explicit Linux opt-in");
        assertTrue(UringFileIo.isSupportedPlatform());
    }
}
