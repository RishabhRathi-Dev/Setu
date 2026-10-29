package io.setu.storage.log;

record GroupCommitEntry(PendingAppend pending, GroupCommitEntry next) { }
