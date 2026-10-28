package io.setu.storage.uring;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Native barriers do not emit jdk.FileForce; keep the same path/metadata fields. */
@Name("io.setu.storage.FileForce")
@Label("Setu io_uring file force")
@Category("Setu")
@StackTrace(false)
@Threshold("20 ms")
public final class FileForceEvent extends Event {
    public String path;
    public boolean metaData;
    public boolean success;
}
