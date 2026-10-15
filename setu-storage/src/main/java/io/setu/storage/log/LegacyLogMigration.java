package io.setu.storage.log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Offline, read-only import of the retired length-prefixed format into a fresh Chronicle data root. */
public final class LegacyLogMigration {
    private LegacyLogMigration() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("Usage: LegacyLogMigration <offline-source-root> <new-target-root> [max-record-bytes]");
        int maximum = args.length == 3 ? Integer.parseInt(args[2]) : 1024 * 1024;
        long records = migrate(Path.of(args[0]), Path.of(args[1]), maximum);
        System.out.println("Migrated " + records + " records to " + Path.of(args[1]).toAbsolutePath()
                + "; source files were preserved. Update data.dir after verifying the target.");
    }

    public static long migrate(Path source, Path target, int maxRecordBytes) throws IOException {
        if (maxRecordBytes < 0 || maxRecordBytes > 15 * 1024 * 1024)
            throw new IllegalArgumentException("maxRecordBytes must be 0..15 MiB");
        Path original = source.toRealPath();
        Path requested = target.toAbsolutePath().normalize();
        Path destination = requested.getParent().toRealPath().resolve(requested.getFileName());
        if (!Files.isDirectory(original) || destination.startsWith(original))
            throw new IOException("Migration target must be outside the source data directory");
        Files.createDirectory(destination); // Never merge with or overwrite an existing target.
        long total = 0;
        for (Path topic : directories(original)) {
            String name = topic.getFileName().toString();
            if (!name.matches("[A-Za-z0-9._-]{1,249}")) continue;
            List<Path> partitions = directories(topic).stream()
                    .filter(path -> path.getFileName().toString().matches("partition-[0-9]+"))
                    .sorted(Comparator.comparingInt(path -> Integer.parseInt(path.getFileName().toString().substring(10))))
                    .toList();
            if (partitions.isEmpty()) continue;
            Path topicTarget = Files.createDirectory(destination.resolve(name));
            for (int partition = 0; partition < partitions.size(); partition++) {
                Path input = partitions.get(partition);
                if (!input.getFileName().toString().equals("partition-" + partition))
                    throw new IOException("Non-contiguous legacy partition directories: " + topic);
                try (var log = new ChronicleMessageLog(topicTarget.resolve(input.getFileName()), maxRecordBytes,
                        MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES)) {
                    importPartition(input, log, maxRecordBytes);
                    total = Math.addExact(total, log.nextOffset());
                }
            }
        }
        return total;
    }

    private static void importPartition(Path directory, ChronicleMessageLog target, int maximum) throws IOException {
        List<Path> segments;
        try (var paths = Files.list(directory)) {
            segments = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("[0-9]{12,}\\.log"))
                    .sorted(Comparator.comparingLong(LegacyLogMigration::baseOffset)).toList();
        }
        if (segments.isEmpty()) throw new IOException("No legacy segments in " + directory);
        for (Path segment : segments) {
            if (baseOffset(segment) != target.nextOffset()) throw new IOException("Legacy offset gap or overlap: " + segment);
            long expectedSize = Files.size(segment);
            long remaining = expectedSize;
            var batch = new ArrayList<MessageValue>();
            long batchBytes = 0;
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(segment)))) {
                while (remaining > 0) {
                    if (remaining < Integer.BYTES) throw new IOException("Incomplete legacy tail; repair offline before migration: " + segment);
                    int length = input.readInt();
                    remaining -= Integer.BYTES;
                    if (length < 0 || length > maximum || length > remaining)
                        throw new IOException("Invalid or incomplete legacy record in " + segment);
                    batch.add(MessageValue.readFrom(input, length));
                    remaining -= length;
                    batchBytes += Integer.BYTES + (long) length;
                    if (batchBytes >= 1024 * 1024 || batch.size() == 1024) {
                        target.appendValues(batch);
                        batch.clear();
                        batchBytes = 0;
                    }
                }
            }
            if (!batch.isEmpty()) target.appendValues(batch);
            if (Files.size(segment) != expectedSize) throw new IOException("Legacy source changed; stop its broker before migration: " + segment);
        }
    }

    private static long baseOffset(Path segment) {
        String name = segment.getFileName().toString();
        return Long.parseLong(name.substring(0, name.length() - 4));
    }

    private static List<Path> directories(Path parent) throws IOException {
        try (var paths = Files.list(parent)) { return paths.filter(Files::isDirectory).sorted().toList(); }
    }
}
