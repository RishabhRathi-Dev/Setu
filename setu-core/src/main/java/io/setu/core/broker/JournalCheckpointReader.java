package io.setu.core.broker;

import java.io.DataInputStream;
import java.io.IOException;

@FunctionalInterface
interface JournalCheckpointReader { void read(DataInputStream input) throws IOException; }
