package io.setu.core.broker;

enum TransactionStage { BEFORE_JOURNAL, JOURNAL_FORCED, OUTPUT_FORCED, INPUT_APPLIED, BEFORE_REPLY }
