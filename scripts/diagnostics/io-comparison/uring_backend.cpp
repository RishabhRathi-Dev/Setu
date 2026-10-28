// Local experiment only. One outstanding operation per ring; no early acknowledgement.
#include <liburing.h>
#include <cerrno>
#include <cstdlib>
#include <fcntl.h>
#include <unistd.h>

struct Context {
    io_uring ring;
    int fd;
};

static int complete(Context* context) {
    int result;
    do { result = io_uring_submit_and_wait(&context->ring, 1); } while (result == -EINTR);
    if (result < 0) return result;
    io_uring_cqe* cqe = nullptr;
    do { result = io_uring_wait_cqe(&context->ring, &cqe); } while (result == -EINTR);
    if (result < 0) return result;
    result = cqe->user_data == 1 ? cqe->res : -EPROTO;
    io_uring_cqe_seen(&context->ring, cqe);
    return result;
}

extern "C" int setu_ring_open(const char* path, void** output) {
    *output = nullptr;
    auto* context = static_cast<Context*>(std::calloc(1, sizeof(Context)));
    if (!context) return -ENOMEM;
    context->fd = open(path, O_RDWR | O_CLOEXEC);
    if (context->fd < 0) { int error = -errno; std::free(context); return error; }
    int result = io_uring_queue_init(8, &context->ring, 0);
    if (result >= 0) {
        result = io_uring_register_files(&context->ring, &context->fd, 1);
        if (result < 0) io_uring_queue_exit(&context->ring);
    }
    if (result < 0) { close(context->fd); std::free(context); return result; }
    *output = context;
    return 0;
}

extern "C" int setu_ring_write(void* pointer, const void* data, int size, long long offset) {
    auto* context = static_cast<Context*>(pointer);
    const auto* position = static_cast<const char*>(data);
    while (size > 0) {
        auto* sqe = io_uring_get_sqe(&context->ring);
        if (!sqe) return -EBUSY;
        io_uring_prep_write(sqe, 0, position, static_cast<unsigned>(size), offset);
        sqe->flags |= IOSQE_FIXED_FILE;
        sqe->user_data = 1;
        int result = complete(context);
        if (result < 0) return result;
        if (result == 0 || result > size) return -EIO;
        size -= result;
        offset += result;
        position += result;
    }
    return 0;
}

extern "C" int setu_ring_force(void* pointer, int metadata) {
    auto* context = static_cast<Context*>(pointer);
    auto* sqe = io_uring_get_sqe(&context->ring);
    if (!sqe) return -EBUSY;
    io_uring_prep_fsync(sqe, 0, metadata ? 0 : IORING_FSYNC_DATASYNC);
    sqe->flags |= IOSQE_FIXED_FILE;
    sqe->user_data = 1;
    return complete(context);
}

extern "C" int setu_ring_close(void* pointer) {
    auto* context = static_cast<Context*>(pointer);
    io_uring_queue_exit(&context->ring);
    int result = close(context->fd) == 0 ? 0 : -errno;
    std::free(context);
    return result;
}
