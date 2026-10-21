package io.setu.core.protocol;

public record ProducerIdentity(String id, long epoch, long sequence) { }
