#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sendfile.h>
#include <linux/memfd.h>
#include <vector>

#include "include/zygisk.hpp"
#include "include/json.hpp"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

// 简单的调试日志宏，统一使用标签 zheng_inject
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "zheng_inject", __VA_ARGS__)

// 静态构造函数，.so 一加载就会打印
__attribute__((constructor))
static void on_load_static() {
    LOGI("SystemInject library constructor called (library is being loaded)\n");
}

// 示例模块：在进程专项化阶段按配置选择性加载自定义 payload.so
class ZygiskAttach : public zygisk::ModuleBase {

public:

    // 模块加载回调：保存 Zygisk API 句柄与 JNIEnv 以备后续使用
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    // 应用进程专项化前：获取进程名并进入统一处理逻辑
    void preAppSpecialize(AppSpecializeArgs *args) override {
        if (args->nice_name == nullptr) {
            return;
        }

        const char *process = env->GetStringUTFChars(args->nice_name, nullptr);
        if (process == nullptr) {
            return;
        }

        inject(process);

        // system_server 专项化前：同样走统一处理逻辑
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        env->ReleaseStringUTFChars(args->nice_name, process);
    }


    void preServerSpecialize(ServerSpecializeArgs *args) override {
        preSpecialize("system_server");
    }

private:
    Api *api;
    JNIEnv *env;

    bool canUseInject(const char *process) {
        bool canLoad = false;
        int dirfd = api->getModuleDir();
        if (dirfd >= 0) {
            int cfd = openat(dirfd, "config.json", O_RDONLY | O_CLOEXEC);
            if (cfd >= 0) {
                struct stat st;
                if (fstat(cfd, &st) == 0 && st.st_size > 0) {
                    std::vector<char> buf(st.st_size + 1);
                    ssize_t n = read(cfd, buf.data(), st.st_size);
                    if (n > 0) {
                        buf[n] = 0;
                        try {
                            nlohmann::json config = nlohmann::json::parse(buf.data());
                            if (config.is_array()) {
                                for (const auto &item: config) {
                                    std::string pkg = item.value("package", "");
                                    bool loadSo = item.value("loadSo", false);
                                    // 使用前缀匹配，支持子进程注入
                                    if (!pkg.empty() && std::string(process).find(pkg) == 0 && loadSo) {
                                        canLoad = true;
                                        break;
                                    }
                                }
                            }
                        } catch (const nlohmann::json::parse_error &e) {
                            LOGI("JSON parse error: %s\n", e.what());
                        }
                    }
                }
                close(cfd);
            }
            close(dirfd);
        }
        return canLoad;
    }

    // 获取模块的真实绝对路径
    std::string getModulePath(int dirfd) {
        char path[PATH_MAX];
        char proc_path[64];
        snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", dirfd);
        ssize_t len = readlink(proc_path, path, sizeof(path) - 1);
        if (len != -1) {
            path[len] = '\0';
            return std::string(path);
        }
        return "";
    }

    void inject(const char *process) {
        std::string soPath = "";
        int dirfd = api->getModuleDir();
        if (dirfd >= 0) {
            std::string modulePath = getModulePath(dirfd);
            int cfd = openat(dirfd, "config.json", O_RDONLY | O_CLOEXEC);
            if (cfd >= 0) {
                struct stat st;
                if (fstat(cfd, &st) == 0 && st.st_size > 0) {
                    std::vector<char> buf(st.st_size + 1);
                    ssize_t n = read(cfd, buf.data(), st.st_size);
                    if (n > 0) {
                        buf[n] = 0;
                        try {
                            nlohmann::json config = nlohmann::json::parse(buf.data());
                            if (config.is_array()) {
                                for (const auto &item: config) {
                                    // 使用 value 提供默认值，防止字段缺失报错
                                    std::string pkg = item.value("package", "");
                                    bool loadSo = item.value("loadSo", false);
                                    
                                    if (!pkg.empty() && std::string(process).find(pkg) == 0) {
                                        if (loadSo && !modulePath.empty()) {
                                            std::string soName = item.value("soName", "");
                                            if (!soName.empty()) {
                                                // 拼接路径: [模块路径]/modules/[soName]
                                                soPath = modulePath + "/modules/" + soName;
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                        } catch (const nlohmann::json::parse_error &e) {
                            LOGI("JSON parse error: %s\n", e.what());
                        }
                    }
                }
                close(cfd);
            }
            close(dirfd);
        }

        if (!soPath.empty()) {
            LOGI("process=[%s], target soPath=[%s], loading via memfd...\n", process, soPath.c_str());
            loadSo(soPath);
        }
    }

    void preSpecialize(const char *process) {
        LOGI("process=[%s], preServerSpecialize\n", process);
    }

    void loadSo(std::string soPath) {
        int fd = open(soPath.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            LOGI("Failed to open .so: %s\n", soPath.c_str());
            return;
        }

        struct stat st;
        if (fstat(fd, &st) != 0) {
            close(fd);
            return;
        }

        // 1. 创建 memfd
        int memfd = syscall(__NR_memfd_create, "jit-cache", MFD_CLOEXEC);
        if (memfd < 0) {
            LOGI("Failed to create memfd: %s\n", strerror(errno));
            close(fd);
            return;
        }

        // 2. 将 .so 内容拷贝到 memfd
        if (sendfile(memfd, fd, nullptr, st.st_size) != st.st_size) {
            LOGI("Failed to sendfile to memfd: %s\n", strerror(errno));
            close(fd);
            close(memfd);
            return;
        }
        close(fd);

        // 3. 使用 android_dlopen_ext 从 fd 加载
        android_dlextinfo extinfo;
        extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
        extinfo.library_fd = memfd;

        // 这里名字写成 jit-cache 可以迷惑检测，maps 中会显示 /memfd:jit-cache (deleted)
        void *handle = android_dlopen_ext("jit-cache", RTLD_NOW, &extinfo);
        if (handle) {
            LOGI("Successfully loaded .so from memfd: %s\n", soPath.c_str());
        } else {
            LOGI("Failed to load .so from memfd: %s, error: %s\n", soPath.c_str(), dlerror());
        }
        close(memfd);
    }

    void loadDex(std::string soPath) {

    }
};

static int urandom = -1;

// root companion 处理函数：在 root 守护进程中生成随机数并通过 socket 返回
static void companion_handler(int i) {
    if (urandom < 0) {
        urandom = open("/dev/urandom", O_RDONLY);
    }
    unsigned r;
    read(urandom, &r, sizeof(r));
    LOGI("companion r=[%u]\n", r);
    write(i, &r, sizeof(r));
}

// 注册模块类与 companion 处理函数，使 Zygisk 能在相应阶段回调
REGISTER_ZYGISK_MODULE(ZygiskAttach)

REGISTER_ZYGISK_COMPANION(companion_handler)
