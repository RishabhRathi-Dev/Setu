package io.setu.storage.log;

import net.openhft.chronicle.bytes.Bytes;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;
import static java.lang.foreign.ValueLayout.*;

/** Benchmark overlay: never included in Setu's production artifacts. */
public final class ComparisonSync extends ChronicleFileSync {
    static final String VARIANT = System.getProperty("setu.io.variant", "current");
    private final Backend backend;
    private final String scope;
    private long forcedSize = -1;

    public ComparisonSync(Path path) throws IOException {
        super(path);
        scope = path.toString().contains("@consumer-state") ? "journal" : "partition";
        try {
            backend = switch (VARIANT) {
                case "current", "java-direct" -> null;
                case "cpp-uring" -> new NativeBackend(path);
                case "java-uring" -> new JavaUringBackend(path, channel);
                case "setu-uring" -> new SetuUringBackend(path, channel);
                default -> throw new IllegalArgumentException("Unknown variant: " + VARIANT);
            };
        } catch (Throwable failure) {
            super.close();
            throw new IOException("Cannot open " + VARIANT + " backend", failure);
        }
    }

    @Override void write(Bytes<?> destination, ByteBuffer payload) throws IOException {
        if (backend == null) { super.write(destination, payload); return; }
        int size = payload.remaining();
        if (!payload.isDirect() && !VARIANT.equals("setu-uring"))
            throw new IOException("External native backend requires an owned direct payload");
        backend.write(MemorySegment.ofBuffer(payload), destination.writePosition());
        payload.position(payload.limit());
        destination.writeSkip(size);
    }

    @Override void force() throws IOException {
        var event = new ForceEvent();
        event.scope = scope;
        event.begin();
        try {
            if (backend == null) super.force();
            else {
                long size = channel.size();
                backend.force(size != forcedSize);
                forcedSize = size;
            }
            event.success = true;
        } finally { event.end(); event.commit(); }
    }

    @Override public void close() throws IOException {
        try { if (backend != null) backend.close(); }
        finally { super.close(); }
    }

    @Name("io.setu.experiment.FileForce")
    @Threshold("0 ns")
    @StackTrace(false)
    public static final class ForceEvent extends Event {
        public String scope;
        public boolean success;
    }

    interface Backend extends AutoCloseable {
        void write(MemorySegment data, long position) throws IOException;
        void force(boolean metadata) throws IOException;
        @Override void close() throws IOException;
    }

    private static final class NativeBackend implements Backend {
        private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup(
                System.getProperty("setu.io.library"), Arena.global());
        private static final MethodHandle OPEN = function("setu_ring_open", ADDRESS, ADDRESS);
        private static final MethodHandle WRITE = function("setu_ring_write", ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG);
        private static final MethodHandle FORCE = function("setu_ring_force", ADDRESS, JAVA_INT);
        private static final MethodHandle CLOSE = function("setu_ring_close", ADDRESS);
        private final MemorySegment context;
        private boolean closed;

        private static MethodHandle function(String name, MemoryLayout... arguments) {
            return Linker.nativeLinker().downcallHandle(LIBRARY.find(name).orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, arguments));
        }
        NativeBackend(Path path) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment output = arena.allocate(ADDRESS);
                check((int) OPEN.invokeExact(arena.allocateFrom(path.toString()), output), "open");
                context = output.get(ADDRESS, 0);
            }
        }
        @Override public void write(MemorySegment data, long position) throws IOException {
            try { check((int) WRITE.invokeExact(context, data, Math.toIntExact(data.byteSize()), position), "write"); }
            catch (Throwable failure) { throw io(failure); }
        }
        @Override public void force(boolean metadata) throws IOException {
            try { check((int) FORCE.invokeExact(context, metadata ? 1 : 0), "force"); }
            catch (Throwable failure) { throw io(failure); }
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { check((int) CLOSE.invokeExact(context), "close"); }
            catch (Throwable failure) { throw io(failure); }
        }
    }

    static void check(int result, String operation) throws IOException {
        if (result != 0) throw new IOException(operation + " failed: " + result);
    }
    static IOException io(Throwable failure) {
        return failure instanceof IOException io ? io : new IOException(failure);
    }
}
