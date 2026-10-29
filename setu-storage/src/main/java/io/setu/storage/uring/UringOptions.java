package io.setu.storage.uring;

public record UringOptions(int entries, int maxWriteBytes, int maxPendingRequests, long maxPendingBytes) {
    public UringOptions {
        if (entries < 2 || entries > 256 || Integer.bitCount(entries) != 1
                || maxWriteBytes < 1 || maxWriteBytes > 16 * 1024 * 1024
                || (long) entries * Math.max(4096, maxWriteBytes) > 64L * 1024 * 1024
                || maxPendingRequests < entries || maxPendingRequests > 65536
                || maxPendingBytes < maxWriteBytes)
            throw new IllegalArgumentException("Invalid io_uring admission or native-buffer limits");
    }
    public static UringOptions defaults() { return new UringOptions(16, 1024 * 1024, 1024, 32L * 1024 * 1024); }
    public long nativeBufferBytes() { return (long) entries * Math.max(4096, maxWriteBytes); }
}
