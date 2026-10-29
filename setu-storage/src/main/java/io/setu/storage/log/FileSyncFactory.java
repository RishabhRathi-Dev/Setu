package io.setu.storage.log;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface FileSyncFactory { ChronicleFileSync open(Path path) throws IOException; }
