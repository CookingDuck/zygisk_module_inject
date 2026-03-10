#pragma once

#include <string>
#include <memory>
#include <unistd.h>
#include <fcntl.h>
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

#define LOG_TAG "zheng_inject"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

class IInjector {
public:
    virtual ~IInjector() = default;
    virtual bool doInject(const std::string& soPath) = 0;
};

/**
 * @brief 基础版 Memfd 注入器
 */
class MemfdInjector : public IInjector {
public:
    bool doInject(const std::string& soPath) override {
        int fd = open(soPath.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;

        struct stat st;
        if (fstat(fd, &st) != 0) {
            close(fd);
            return false;
        }

        int memfd = syscall(__NR_memfd_create, "jit-cache", MFD_CLOEXEC);
        if (memfd < 0) {
            close(fd);
            return false;
        }

        sendfile(memfd, fd, nullptr, st.st_size);
        close(fd);

        android_dlextinfo extinfo;
        extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
        extinfo.library_fd = memfd;

        void *handle = android_dlopen_ext("jit-cache", RTLD_NOW, &extinfo);
        close(memfd);

        return handle != nullptr;
    }
};

/**
 * @brief 自定义链接器注入器
 */
class CustomLinkerInjector : public IInjector {
public:
    explicit CustomLinkerInjector(JavaVM* vm) : javaVM(vm) {}
    bool doInject(const std::string& soPath) override {
        return mylinker_load_library(soPath.c_str(), javaVM);
    }
private:
    JavaVM* javaVM;
};

/**
 * @brief JIT 模拟版注入器 (回滚到使用 android_dlopen_ext 的稳定版)
 */
class JitMemfdInjector : public IInjector {
public:
    bool doInject(const std::string& soPath) override {
        int fd = open(soPath.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;

        struct stat st;
        if (fstat(fd, &st) != 0) {
            close(fd);
            return false;
        }

        // 使用伪装名称，但在 maps 中仍会显示为 r-xp
        int memfd = syscall(__NR_memfd_create, "jit-zygote-cache", MFD_CLOEXEC);
        if (memfd < 0) {
            close(fd);
            return false;
        }

        sendfile(memfd, fd, nullptr, st.st_size);
        close(fd);

        android_dlextinfo extinfo;
        extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
        extinfo.library_fd = memfd;

        void *handle = android_dlopen_ext("jit-zygote-cache", RTLD_NOW, &extinfo);
        close(memfd);

        if (handle) {
            LOGI("JitMemfdInjector: Successfully loaded via android_dlopen_ext (Standard Mode).");
            return true;
        }
        return false;
    }
};

class InjectorFactory {
public:
    static std::unique_ptr<IInjector> create(InjectModel model, JavaVM* vm) {
        switch (model) {
            case InjectModel::CUSTOM_LINKER:
                return std::make_unique<CustomLinkerInjector>(vm);
            case InjectModel::MEMFD_JIT:
                return std::make_unique<JitMemfdInjector>();
            case InjectModel::MEMFD:
            default:
                return std::make_unique<MemfdInjector>();
        }
    }
};
