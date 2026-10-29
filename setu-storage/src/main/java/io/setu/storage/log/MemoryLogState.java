package io.setu.storage.log;

record MemoryLogState(MemoryLogNode root, MemoryLogChunk active, long firstOffset, long nextOffset, boolean closed) { }
