package io.setu.storage.uring;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

final class UringNativeCalls {
    static final Linker LINKER = Linker.nativeLinker();
    static final MemoryLayout ERRORS = Linker.Option.captureStateLayout();
    static final long ERRNO = ERRORS.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    static final Linker.Option CAPTURE = Linker.Option.captureCallState("errno");
    // syscall(long, ...): first variadic argument is index 1. Linux uses these
    // syscall numbers and LP64 layouts on both supported architectures.
    static final MethodHandle SETUP = bind("syscall", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS),
            CAPTURE, Linker.Option.firstVariadicArg(1));
    static final MethodHandle ENTER = bind("syscall", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG), CAPTURE, Linker.Option.firstVariadicArg(1));
    static final MethodHandle MMAP = bind("mmap", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG), CAPTURE);
    static final MethodHandle MUNMAP = bind("munmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    static final MethodHandle CLOSE = bind("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT), CAPTURE);
    static final MethodHandle EVENTFD = bind("eventfd", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), CAPTURE);
    static final MethodHandle READ = bind("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), CAPTURE);
    static final MethodHandle WRITE = bind("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), CAPTURE);
    static MethodHandle bind(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor, options);
    }
}
