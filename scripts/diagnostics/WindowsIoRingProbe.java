import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Windows capability and mapped-flush diagnostic; never a broker-throughput qualification. */
public final class WindowsIoRingProbe {
    public static void main(String[] args) throws Throwable {
        if (args.length > 1 || ADDRESS.byteSize() != 8 || !System.getProperty("os.name").startsWith("Windows"))
            throw new IllegalArgumentException("Run on 64-bit Windows with an optional fresh output-directory argument");
        capabilities();
        if (args.length == 1) FlushTrial.run(Path.of(args[0]));
    }

    private static void capabilities() throws Throwable {
        System.out.println("OS=" + System.getProperty("os.name") + " version=" + System.getProperty("os.version")
                + " arch=" + System.getProperty("os.arch") + " java=" + System.getProperty("java.version"));
        try (var arena = Arena.ofConfined()) {
            var symbols = SymbolLookup.libraryLookup("KernelBase.dll", arena);
            var linker = Linker.nativeLinker();
            for (String name : new String[]{"QueryIoRingCapabilities", "CreateIoRing", "BuildIoRingReadFile",
                    "BuildIoRingWriteFile", "BuildIoRingFlushFile"})
                System.out.println(name + " exported=" + symbols.find(name).isPresent());
            var query = linker.downcallHandle(symbols.find("QueryIoRingCapabilities").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var capabilities = arena.allocate(MemoryLayout.structLayout(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
            int result = (int) query.invokeExact(capabilities);
            check("query", result);
            int version = capabilities.get(JAVA_INT, 0);
            System.out.println("version=" + version + " maxSQ=" + capabilities.get(JAVA_INT, 4)
                    + " maxCQ=" + capabilities.get(JAVA_INT, 8) + " flags=" + capabilities.get(JAVA_INT, 12));
            var flagsLayout = MemoryLayout.structLayout(JAVA_INT, JAVA_INT);
            var create = linker.downcallHandle(symbols.find("CreateIoRing").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, flagsLayout, JAVA_INT, JAVA_INT, ADDRESS));
            var close = linker.downcallHandle(symbols.find("CloseIoRing").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            var supported = linker.downcallHandle(symbols.find("IsIoRingOpSupported").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            var output = arena.allocate(ADDRESS);
            check("create", (int) create.invokeExact(version, arena.allocate(flagsLayout), 16, 32, output));
            var ring = output.get(ADDRESS, 0);
            try {
                // Windows SDK IORING_OP_CODE: NOP, READ, WRITE, FLUSH.
                for (int opcode : new int[]{0, 1, 5, 6})
                    System.out.println("opcode=" + opcode + " supportedBOOL=" + (int) supported.invokeExact(ring, opcode));
            } finally { check("close", (int) close.invokeExact(ring)); }
        }
    }
    private static void check(String operation, int result) {
        System.out.printf("%s HRESULT=0x%08x%n", operation, result);
        if (result < 0) throw new IllegalStateException(operation);
    }

    private static final class FlushTrial {
        static final long SIZE = 64L << 20;
        static final int PAYLOAD = 256 * 1024;
        static final GroupLayout FLAGS = MemoryLayout.structLayout(JAVA_INT, JAVA_INT);
        // IORING_HANDLE_REF: 32-bit kind, alignment padding, 64-bit raw handle.
        static final GroupLayout REF = MemoryLayout.structLayout(JAVA_INT, MemoryLayout.paddingLayout(4), ADDRESS);
        static MethodHandle bind(SymbolLookup symbols, String name, MemoryLayout result, MemoryLayout... arguments) {
            return Linker.nativeLinker().downcallHandle(symbols.find(name).orElseThrow(), FunctionDescriptor.of(result, arguments));
        }
        static void check(String op, int hr) {
            if (hr < 0) throw new IllegalStateException(op + " HRESULT=" + Integer.toHexString(hr));
        }
        static void run(Path outputDirectory) throws Throwable {
            Path root = outputDirectory.toAbsolutePath();
            Files.createDirectories(root);
            System.out.println("java=" + System.getProperty("java.runtime.version") + " os=" + System.getProperty("os.name")
                    + " filesystem=" + Files.getFileStore(root).type() + " file_bytes=" + SIZE + " payload_bytes=" + PAYLOAD);
            try (var arena = Arena.ofConfined()) {
                var symbols = SymbolLookup.libraryLookup("KernelBase.dll", arena);
                var create = bind(symbols, "CreateIoRing", JAVA_INT, JAVA_INT, FLAGS, JAVA_INT, JAVA_INT, ADDRESS);
                var closeRing = bind(symbols, "CloseIoRing", JAVA_INT, ADDRESS);
                var open = bind(symbols, "CreateFileW", ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
                var close = bind(symbols, "CloseHandle", JAVA_INT, ADDRESS);
                var build = bind(symbols, "BuildIoRingFlushFile", JAVA_INT, ADDRESS, REF, JAVA_INT, JAVA_LONG, JAVA_INT);
                var submit = bind(symbols, "SubmitIoRing", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
                var pop = bind(symbols, "PopIoRingCompletion", JAVA_INT, ADDRESS, ADDRESS);
                var output = arena.allocate(ADDRESS);
                check("create", (int) create.invokeExact(300, arena.allocate(FLAGS), 16, 32, output));
                var ring = output.get(ADDRESS, 0);
                var cqe = arena.allocate(24, 8);
                byte[] data = new byte[PAYLOAD];
                new java.util.Random(42).nextBytes(data);
                try {
                    for (int round = 1; round <= 4; round++) {
                        for (int position = 0; position < 2; position++) {
                            boolean nativeFlush = ((round + position) & 1) == 0;
                            String variant = nativeFlush ? "panama-ring-flush" : "jdk-force";
                            Path path = Files.createFile(root.resolve("round-" + round + "-" + variant + ".bin"));
                            // Existing file; read/write access, share read/write/delete, overlapped I/O.
                        var handle = (MemorySegment) open.invokeExact(arena.allocateFrom(path.toString(), StandardCharsets.UTF_16LE),
                                    0xc0000000, 7, MemorySegment.NULL, 3, 0x40000080, MemorySegment.NULL);
                            if (handle.address() == -1) throw new IllegalStateException("open");
                            var reference = arena.allocate(REF);
                            reference.set(ADDRESS, 8, handle);
                            try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                                 var maps = Arena.ofConfined()) {
                                var writer = channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE, maps);
                                writer.force(); channel.force(true);
                                long sequence = 0;
                                for (int phase = 0; phase < 2; phase++) {
                                    long start = System.nanoTime(), deadline = start + (phase == 0 ? 2_000_000_000L : 5_000_000_000L);
                                    long count = 0;
                                    while (System.nanoTime() < deadline) {
                                        long offset = 65536 + (sequence % 128) * PAYLOAD;
                                        MemorySegment.copy(MemorySegment.ofArray(data), 0, writer, offset, PAYLOAD);
                                        writer.set(JAVA_LONG, 0, ++sequence);
                                        try (var view = Arena.ofConfined()) {
                                            channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE, view).force();
                                        }
                                        if (nativeFlush) {
                                            // FILE_FLUSH_DEFAULT includes data, metadata and hardware sync.
                                        check("build", (int) build.invokeExact(ring, reference, 0, sequence, 0));
                                            check("submit", (int) submit.invokeExact(ring, 1, -1, MemorySegment.NULL));
                                            int result = (int) pop.invokeExact(ring, cqe);
                                            if (result != 0 || cqe.get(JAVA_LONG, 0) != sequence) throw new IllegalStateException("completion");
                                            check("flush completion", cqe.get(JAVA_INT, 8));
                                        } else channel.force(true);
                                        count++;
                                    }
                                    double seconds = (System.nanoTime() - start) / 1e9;
                                    if (phase == 1) System.out.printf(Locale.ROOT,
                                            "WINDOWS_FLUSH round=%d variant=%s seconds=%.6f count=%d flush_per_s=%.3f%n",
                                            round, variant, seconds, count, count / seconds);
                                }
                                if (writer.get(JAVA_LONG, 0) != sequence) throw new IllegalStateException("header");
                            } finally {
                                if ((int) close.invokeExact(handle) == 0) throw new IllegalStateException("close");
                            }
                            // Independent descriptor verifies mapped payload contents after close.
                            try (var reader = FileChannel.open(path, StandardOpenOption.READ)) {
                                var buffer = java.nio.ByteBuffer.allocate(PAYLOAD);
                                while (buffer.hasRemaining()) {
                                    if (reader.read(buffer, 65536 + buffer.position()) <= 0) throw new IllegalStateException("read");
                                }
                                if (!Arrays.equals(data, buffer.array())) throw new IllegalStateException("payload");
                            }
                        }
                    }
                } finally { check("close ring", (int) closeRing.invokeExact(ring)); }
            }
            System.out.println("WINDOWS_FLUSH readback=true completed=true qualified=false reason=flush_microbenchmark");
        }
    }
}
