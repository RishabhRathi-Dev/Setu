package io.setu.server.tcp;

import io.setu.core.protocol.BufferLease;
import io.setu.core.protocol.ResponseBuffers;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.*;

final class SelectorClient {
    private static final java.util.concurrent.atomic.AtomicLong SESSION_IDS = new java.util.concurrent.atomic.AtomicLong();
    final SocketChannel channel;
    final long sessionId = SESSION_IDS.incrementAndGet();
    final ByteBuffer header = ByteBuffer.allocate(4);
    final BufferLease connectionCredit;
    SelectionKey key;
    io.setu.storage.log.MessageFrameReader body;
    BufferLease requestCredit;
    ResponseBuffers responseCredit;
    ByteBuffer[] outbound;
    boolean inFlight;
    long activity = System.nanoTime();
    long writeStarted;
    long frameStarted;
    boolean receiving;
    SelectorClient(SocketChannel channel, BufferLease connectionCredit) { this.channel = channel; this.connectionCredit = connectionCredit; }
}
