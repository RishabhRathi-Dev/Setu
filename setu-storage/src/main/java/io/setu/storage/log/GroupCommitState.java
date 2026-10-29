package io.setu.storage.log;

import java.io.IOException;

record GroupCommitState(GroupCommitEntry head, long bytes, int requests, boolean closing, IOException failed) { }
