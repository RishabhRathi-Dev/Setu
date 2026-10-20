import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Perform one offline file-retention sweep and verify the retained offsets survive reopening. */
public class SetuFileRetention {
    public static void main(String[] args) throws Exception {
        if (args.length > 3)
            throw new IllegalArgumentException("Usage: SetuFileRetention [partition-directory [days [max-record-bytes]]]");
        long days = args.length > 1 ? Long.parseLong(args[1]) : 7;
        if (days < 0 || days > 365) throw new IllegalArgumentException("days must be 0..365");
        int maxRecordBytes = args.length > 2 ? Integer.parseInt(args[2]) : 1024 * 1024;
        if (maxRecordBytes < 0) throw new IllegalArgumentException("max-record-bytes must be non-negative");
        boolean demo = args.length == 0;
        Path directory;
        if (demo) {
            Path base = Files.createDirectories(Path.of("examples", "target"));
            directory = Files.createTempDirectory(base, "retention-").toAbsolutePath();
        } else {
            directory = Path.of(args[0]).toAbsolutePath();
            if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Partition directory must exist");
        }
        System.out.println("Partition: " + directory);
        long retained;
        long end;
        try (var log = MessageLogs.open(StorageMode.FILE, directory, maxRecordBytes)) {
            if (demo) log.append(new byte[]{1});
            System.out.printf("Before: files=%d offsets=[%d, %d)%n", files(directory), log.firstOffset(), log.nextOffset());
            log.discardExpiredFiles(Duration.ofDays(days).toMillis());
            retained = log.firstOffset();
            end = log.nextOffset();
            System.out.printf("After:  files=%d offsets=[%d, %d)%n", files(directory), retained, end);
            System.out.println("First retained page records: " + log.read(retained, 1024).size());
        }
        try (var reopened = MessageLogs.open(StorageMode.FILE, directory, maxRecordBytes)) {
            if (reopened.firstOffset() != retained || reopened.nextOffset() != end)
                throw new IllegalStateException("Retention offsets changed after reopening");
            System.out.printf("Reopened offsets=[%d, %d)%n", retained, end);
        }
        if (demo) System.out.println("The newest populated daily file remains; no files expire in this fresh demo.");
    }

    private static long files(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".cq4")).count();
        }
    }
}
