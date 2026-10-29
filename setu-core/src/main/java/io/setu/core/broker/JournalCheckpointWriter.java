package io.setu.core.broker;

import java.io.DataOutputStream;
import java.io.IOException;

@FunctionalInterface
interface JournalCheckpointWriter { void write(DataOutputStream output) throws IOException; }
