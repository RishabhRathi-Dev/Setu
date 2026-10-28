package io.setu.storage.log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.openhft.chronicle.bytes.Bytes;

/** Growth, CRC/readback, abrupt-exit recovery and unsuccessful-CQE checks for every candidate. */
public final class VerifyIo {
    private static final int RECORD_BYTES = 1024;
    private static final int BATCH = 256;
    private static final List<MessageValue> VALUES = values();

    private static List<MessageValue> values() {
        var values = new ArrayList<MessageValue>();
        for (int record = 0; record < BATCH; record++) {
            byte[] bytes = new byte[RECORD_BYTES];
            new java.util.Random(record).nextBytes(bytes);
            values.add(MessageValue.copyOf(bytes));
        }
        return values;
    }
    private static ChronicleMessageLog open(Path directory) throws Exception {
        return new ChronicleMessageLog(directory, 2 * 1024 * 1024, 64L << 20);
    }
    private static void verify(ChronicleMessageLog log, long count) throws Exception {
        for (long offset = 0; offset < count;) {
            var page = log.read(offset, 1024 * 1024);
            if (page.isEmpty()) throw new AssertionError("Missing record " + offset);
            for (var record : page) {
                if (record.offset() != offset || !Arrays.equals(record.value(), VALUES.get((int) (offset % BATCH)).copy()))
                    throw new AssertionError("Corrupt or non-dense record " + offset);
                offset++;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[1]));
        if (args[0].equals("child")) {
            try (var log = open(root)) {
                for (int i = 0; i < 20; i++) log.appendValues(VALUES);
                System.out.println("DURABLE " + log.nextOffset());
                System.out.flush();
                Thread.sleep(60000);
            }
            return;
        }
        Path growth = root.resolve("growth");
        try (var log = open(growth)) {
            for (int batch = 0; batch < 300; batch++) {
                if (log.appendValues(VALUES) != (long) batch * BATCH) throw new AssertionError("Incorrect append offset");
            }
            verify(log, 300L * BATCH);
        }
        try (var log = open(growth)) {
            if (log.nextOffset() != 300L * BATCH) throw new AssertionError("Wrong recovered boundary");
            verify(log, 300L * BATCH);
        }
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), VerifyIo.class.getName(),
                "child", root.resolve("crash").toString()));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        long acknowledged = -1;
        try (var reader = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("DURABLE ")) { acknowledged = Long.parseLong(line.substring(8)); break; }
            }
        } finally {
            child.destroyForcibly();
            if (!child.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Child did not exit");
        }
        if (acknowledged != 20L * BATCH) throw new AssertionError("Child never acknowledged the expected prefix");
        try (var log = open(root.resolve("crash"))) {
            if (log.nextOffset() != acknowledged) throw new AssertionError("Durable data lost after forced exit");
            verify(log, acknowledged);
            if (log.appendValues(VALUES) != acknowledged) throw new AssertionError("Append after crash failed");
        }
        // /dev/full always rejects writes; no request may be reported as successfully written.
        try (var sync = new ComparisonSync(Path.of("/dev/full"))) {
            Bytes<?> destination = Bytes.allocateElasticOnHeap();
            try {
                ByteBuffer payload = ByteBuffer.allocateDirect(8).putLong(42).flip();
                try { sync.write(destination, payload); throw new AssertionError("Failed write acknowledged"); }
                catch (java.io.IOException expected) { }
            } finally { destination.releaseLast(); }
        }
        System.out.println("IO_VERIFY variant=" + ComparisonSync.VARIANT + " growth_records=76800 crash_records="
                + acknowledged + " crc_readback=true failed_write_rejected=true passed=true");
    }
}
