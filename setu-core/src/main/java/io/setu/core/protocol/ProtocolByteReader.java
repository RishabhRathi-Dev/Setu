package io.setu.core.protocol;

final class ProtocolByteReader {
    private final byte[] bytes;
    ProtocolByteReader(byte[] bytes) { this.bytes = bytes; }
    int intAt(int i) { return (bytes[i] & 255) << 24 | (bytes[i + 1] & 255) << 16 | (bytes[i + 2] & 255) << 8 | (bytes[i + 3] & 255); }
    int unsignedShortAt(int i) { return (bytes[i] & 255) << 8 | (bytes[i + 1] & 255); }
}
