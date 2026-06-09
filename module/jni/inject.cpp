#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <android/log.h>

#include <memory>
#include <string>

#include "include/zygisk.hpp"
#include "config_manager.hpp"
#include "injector.hpp"

#ifndef LOG_TAG
#define LOG_TAG "zheng_inject"
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

__attribute__((constructor))
static void on_load_static() {
    LOGI("Zygisk module library constructor called.");
}

/**
 * Zygisk 主模块。
 * 为提升健壮性，本类做了以下改动：
 *   - 所有 fd 走 RAII，避免 early-return 泄漏；
 *   - 注入失败时打印错误码并直接退出，不影响目标进程其余流程；
 *   - 进程名匹配从 substr 改为完全匹配/":子进程"匹配，避免误中前缀同名包；
 *   - 关键路径校验 SO 名合法性，杜绝目录穿越。
 */
class ZygiskAttach : public zygisk::ModuleBase {
public:
    void onLoad(Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
        if (env == nullptr || env->GetJavaVM(&vm_) != JNI_OK) {
            vm_ = nullptr;
            LOGW("onLoad: JNIEnv->GetJavaVM failed, custom_linker mode will be disabled");
        }
    }

    void preAppSpecialize(AppSpecializeArgs* args) override {
        if (api_ != nullptr) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
        if (args == nullptr || args->nice_name == nullptr || env_ == nullptr) return;

        const char* process = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (process == nullptr) return;

        // 用栈上的局部 std::string 复制，避免在 handleInjection 抛错时 release 调用被绕过
        std::string processStr(process);
        env_->ReleaseStringUTFChars(args->nice_name, process);

        handleInjection(processStr.c_str());
    }

    void preServerSpecialize(ServerSpecializeArgs*) override {
        handleInjection("system_server");
    }

private:
    Api*     api_ = nullptr;
    JNIEnv*  env_ = nullptr;
    JavaVM*  vm_  = nullptr;

    void handleInjection(const char* process) {
        if (api_ == nullptr || process == nullptr || *process == '\0') return;

        int rawDirFd = api_->getModuleDir();
        if (rawDirFd < 0) {
            LOGE("getModuleDir failed (errno=%d:%s)", errno, strerror(errno));
            return;
        }
        inj::ScopedFd dirFd(rawDirFd);

        ConfigManager configManager(dirFd.get());
        InjectConfig config = configManager.getMatchingConfig(process);
        if (!config.matched) return;
        if (!config.loadSo) {
            LOGI("[%s] matched but loadSo=false, skip", process);
            return;
        }
        if (!cfg_util::isValidSoName(config.soName)) {
            LOGE("[%s] invalid soName rejected: '%s'", process, config.soName.c_str());
            return;
        }
        if (config.model == InjectModel::CUSTOM_LINKER && vm_ == nullptr) {
            LOGE("[%s] custom_linker requested but JavaVM is null", process);
            return;
        }

        std::string root = configManager.getModuleRootPath();
        if (root.empty()) {
            LOGE("[%s] cannot resolve module root path", process);
            return;
        }

        std::string soPath = root + "/modules/" + config.soName;
        struct stat st{};
        if (stat(soPath.c_str(), &st) != 0) {
            LOGE("[%s] SO not present: %s (errno=%d:%s)",
                 process, soPath.c_str(), errno, strerror(errno));
            return;
        }
        if (!S_ISREG(st.st_mode)) {
            LOGE("[%s] SO path is not a regular file: %s", process, soPath.c_str());
            return;
        }

        LOGI("inject -> process=[%s] so=[%s] model=%s",
             process, soPath.c_str(), cfg_util::modelName(config.model));

        auto injector = InjectorFactory::create(config.model, vm_);
        if (!injector) {
            LOGE("[%s] InjectorFactory returned null", process);
            return;
        }
        if (injector->doInject(soPath)) {
            LOGI("[%s] injection succeeded (so=%s)", process, config.soName.c_str());
        } else {
            LOGE("[%s] injection FAILED (so=%s)", process, config.soName.c_str());
        }
    }
};

/**
 * Companion 服务示例：以 Magisk daemon 权限运行，
 * 此处保留原有随机数示例并清理资源。
 */
static void companion_handler(int socket_fd) {
    int urandom = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (urandom < 0) {
        LOGE("companion: open /dev/urandom failed: errno=%d:%s", errno, strerror(errno));
        return;
    }
    unsigned r = 0;
    ssize_t got = read(urandom, &r, sizeof(r));
    close(urandom);

    if (got == sizeof(r)) {
        LOGI("companion: random seed=%u", r);
        ssize_t w = write(socket_fd, &r, sizeof(r));
        (void) w;
    } else {
        LOGE("companion: read /dev/urandom short (%zd)", got);
    }
}

REGISTER_ZYGISK_MODULE(ZygiskAttach)
REGISTER_ZYGISK_COMPANION(companion_handler)
