#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <string.h>
#include <sys/stat.h>
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
//        LOGI("ZygiskAttach module loaded via onLoad\n");
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

        if (canUseInject(process)) {
            LOGI("process=[%s], on\n", process);
        } else {
            // 改为 INFO 级别，确保能看到
            LOGI("process=[%s], filtered\n", process);
        }

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
                                    std::string pkg = item["package"];
                                    bool loadSo = item["loadSo"];
                                    if (pkg == process && loadSo) {
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
            }
        }
        return canLoad;
    }

    void preSpecialize(const char *process) {
        LOGI("process=[%s], preServerSpecialize\n", process);
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
