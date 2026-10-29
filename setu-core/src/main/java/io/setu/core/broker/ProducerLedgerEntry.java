package io.setu.core.broker;

record ProducerLedgerEntry(long epoch, long sequence, byte[] fingerprint, long offset) { }
