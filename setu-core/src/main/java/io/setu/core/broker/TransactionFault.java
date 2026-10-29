package io.setu.core.broker;

import java.io.IOException;

@FunctionalInterface interface TransactionFault { void at(TransactionStage stage) throws IOException; }
