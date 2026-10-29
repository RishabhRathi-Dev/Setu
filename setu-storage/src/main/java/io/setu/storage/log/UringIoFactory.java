package io.setu.storage.log;

import io.setu.storage.uring.UringFileIo;
import java.io.IOException;

@FunctionalInterface interface UringIoFactory { UringFileIo create() throws IOException; }
