package io.setu.core.broker;

import java.io.IOException;

@FunctionalInterface
interface JournalFault { void at(String stage) throws IOException; }
