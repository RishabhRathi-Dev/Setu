package io.setu.storage.uring;

import java.io.IOException;

@FunctionalInterface interface UringRingFactory { UringRing open(UringOptions options) throws IOException; }
