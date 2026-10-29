package io.setu.storage.uring;

import java.io.IOException;
import java.util.List;

interface UringRing extends AutoCloseable {
    int available();
    void submit(List<UringOperation> operations) throws IOException;
    List<UringCompletion> poll(boolean wait) throws IOException;
    void wakeup() throws IOException;
    void closeFile(int fd) throws IOException;
    @Override void close() throws IOException;
}
