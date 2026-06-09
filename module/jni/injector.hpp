#pragma once

#include <string>
#include <memory>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sendfile.h>
#include <linux/memfd.h>
#include <android/log.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <jni.h>

#include "mylinker/include/mylinker.h"
#include "config_manager.hpp"

#ifndef LOG_TAG
#define LOG_TAG "zheng_inject"
#endif
#ifndef LOGI
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#endif
#ifndef LOGE
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace inj {

/**
 * 简单 RAII 文件描述符封装，防止异常路径下泄漏。
 */
class ScopedFd {
public:
    explicit ScopedFd(int fd = -1) : fd_(fd) {}
    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;
    ScopedFd(ScopedFd&& o) noexcept : fd_(o.fd_) { o.fd_ = -1; }
    ~ScopedFd() { reset(); }

    int get() const { return fd_; }
    int release() { int t = fd_; fd_ = -1; return t; }
    void reset(int fd = -1) {
        if (fd_ >= 0) ::close(fd_);
        fd_ = fd;
    }
    bool valid() const { return fd_ >= 0; }
private:
    int fd_;
};

/**
 * 通过 sendfile 把 SO 拷到 memfd，返回 memfd 的所有权。
 * 大文件做长度校验，防止异常文件造成内存爆炸。
 */
inline ScopedFd makeMemfdFromSo(const std::string& soPath, const char* memfdName) {
    static constexpr off_t kMaxSoSize = 64 * 1024 * 1024;  // 64MB

    ScopedFd fd(open(soPath.c_str(), O_RDONLY | O_CLOEXEC));
    if (!fd.valid()) {
        LOGE("open SO failed: %s (errno=%d:%s)", soPath.c_str(), errno, strerror(errno));
        return ScopedFd();
    }

    struct stat st{};
    if (fstat(fd.get(), &st) != 0) {
        LOGE("fstat SO failed: errno=%d:%s", errno, strerror(errno));
        return ScopedFd();
    }
    if (st.st_size <= 0 || st.st_size > kMaxSoSize) {
        LOGE("invalid SO size: %lld", (long long) st.st_size);
        return ScopedFd();
    }

    ScopedFd memfd(static_cast<int>(syscall(__NR_memfd_create, memfdName, MFD_CLOEXEC)));
    if (!memfd.valid()) {
        LOGE("memfd_create failed: errno=%d:%s", errno, strerror(errno));
        return ScopedFd();
    }

    off_t off = 0;
    size_t remaining = static_cast<size_t>(st.st_size);
    while (remaining > 0) {
        ssize_t n = sendfile(memfd.get(), fd.get(), &off, remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("sendfile failed: errno=%d:%s", errno, strerror(errno));
            return ScopedFd();
        }
        if (n == 0) {
            LOGE("sendfile produced 0 bytes (truncated source?)");
            return ScopedFd();
        }
        remaining -= static_cast<size_t>(n);
    }

    return memfd;
}

inline bool dlopenFromMemfd(int memfd, const char* nameInDlopen) {
    android_dlextinfo extinfo{};
    extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    extinfo.library_fd = memfd;

    void* handle = android_dlopen_ext(nameInDlopen, RTLD_NOW, &extinfo);
    if (handle == nullptr) {
        LOGE("android_dlopen_ext failed: %s", dlerror());
        return false;
    }
    return true;
}

} // namespace inj

class IInjector {
public:
    virtual ~IInjector() = default;
    virtual bool doInject(const std::string& soPath) = 0;
};

class MemfdInjector : public IInjector {
public:
    bool doInject(const std::string& soPath) override {
        inj::ScopedFd memfd = inj::makeMemfdFromSo(soPath, "jit-cache");
        if (!memfd.valid()) return false;
        bool ok = inj::dlopenFromMemfd(memfd.get(), "jit-cache");
        if (ok) LOGI("MemfdInjector: loaded %s", soPath.c_str());
        return ok;
    }
};

class CustomLinkerInjector : public IInjector {
public:
    explicit CustomLinkerInjector(JavaVM* vm) : javaVM_(vm) {}
    bool doInject(const std::string& soPath) override {
        if (javaVM_ == nullptr) {
            LOGE("CustomLinkerInjector: JavaVM is null");
            return false;
        }
        bool ok = mylinker_load_library(soPath.c_str(), javaVM_);
        if (!ok) LOGE("CustomLinkerInjector: mylinker_load_library failed");
        return ok;
    }
private:
    JavaVM* javaVM_;
};

class JitMemfdInjector : public IInjector {
public:
    bool doInject(const std::string& soPath) override {
        inj::ScopedFd memfd = inj::makeMemfdFromSo(soPath, "jit-zygote-cache");
        if (!memfd.valid()) return false;
        bool ok = inj::dlopenFromMemfd(memfd.get(), "jit-zygote-cache");
        if (ok) LOGI("JitMemfdInjector: loaded %s (Standard Mode).", soPath.c_str());
        return ok;
    }
};

class InjectorFactory {
public:
    static std::unique_ptr<IInjector> create(InjectModel model, JavaVM* vm) {
        switch (model) {
            case InjectModel::CUSTOM_LINKER: return std::make_unique<CustomLinkerInjector>(vm);
            case InjectModel::MEMFD_JIT:     return std::make_unique<JitMemfdInjector>();
            case InjectModel::MEMFD:
            default:                          return std::make_unique<MemfdInjector>();
        }
    }
};
