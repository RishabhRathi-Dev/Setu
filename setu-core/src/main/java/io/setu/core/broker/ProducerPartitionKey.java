package io.setu.core.broker;

record ProducerPartitionKey(String producer, String topic, int partition) { }
