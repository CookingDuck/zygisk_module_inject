#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>
#include <vector>
#include <memory>

#include "include/zygisk.hpp"
#include "config_manager.hpp"
#include "injector.hpp"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

// 静态构造函数，确保 .so 加载即打印，方便确认模块是否正常运行
__attribute__((constructor))
static void on_load_static() {
    __android_log_print(ANDROID_LOG_INFO, "zheng_inject", "Zygisk module library constructor called!\n");
}

/**
 * @brief Zygisk 模块核心类
 * 负责与 Zygisk 框架交互，并在进程专项化阶段触发注入逻辑。
 */
class ZygiskAttach : public zygisk::ModuleBase {

public:
    /**
     * @brief 模块加载回调
     * 保存 API 句柄和 JavaVM 指针，以便后续注入器使用。
     */
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
        if (env->GetJavaVM(&this->vm) != JNI_OK) {
            this->vm = nullptr;
        }
    }

    /**
     * @brief 应用进程专项化前的回调
     * 在此阶段判断是否需要对当前应用进行注入。
     */
    void preAppSpecialize(AppSpecializeArgs *args) override {
        if (args->nice_name == nullptr) return;

        const char *process = env->GetStringUTFChars(args->nice_name, nullptr);
        if (process != nullptr) {
            handleInjection(process);
            env->ReleaseStringUTFChars(args->nice_name, process);
        }
        
        // 设置选项以在模块卸载时自动关闭句柄
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

    /**
     * @brief 系统服务 (system_server) 专项化前的回调
     */
    void preServerSpecialize(ServerSpecializeArgs *args) override {
        handleInjection("system_server");
    }

private:
    Api *api;
    JNIEnv *env;
    JavaVM *vm;

    /**
     * @brief 统一注入处理逻辑
     */
    void handleInjection(const char *process) {
        LOGI("handleInjection called for process: [%s]", process);
        
        int dirfd = api->getModuleDir();
        if (dirfd < 0) {
            LOGI("Failed to get module directory FD");
            return;
        }

        // 1. 解析配置
        ConfigManager configManager(dirfd);
        InjectConfig config = configManager.getMatchingConfig(process);
        
        if (!config.matched) {
            // 如果觉得日志太多，测试稳定后可以删掉这一行
            // LOGI("Process [%s] not matched in config.", process);
            close(dirfd);
            return;
        }

        if (config.loadSo) {
            std::string rootPath = configManager.getModuleRootPath();
            if (rootPath.empty()) {
                LOGI("Error: Could not resolve module root path.");
                close(dirfd);
                return;
            }

            // 2. 构造完整的 SO 路径
            // 确保是从模块根目录下的 modules 文件夹寻找
            std::string soPath = rootPath + "/modules/" + config.soName;
            
            LOGI("Target match! Process: [%s], SO: [%s], Model: %s", 
                 process, 
                 soPath.c_str(),
                 (config.model == InjectModel::CUSTOM_LINKER ? "custom_linker" : 
                 (config.model == InjectModel::MEMFD_JIT ? "memfd_jit" : "memfd")));

            // 3. 使用工厂创建对应的注入策略
            auto injector = InjectorFactory::create(config.model, this->vm);
            if (injector) {
                if (injector->doInject(soPath)) {
                    LOGI("Successfully injected [%s] into [%s]", config.soName.c_str(), process);
                } else {
                    LOGI("Failed to inject [%s] into [%s]", config.soName.c_str(), process);
                }
            }
        }
        
        close(dirfd);
    }
};

/**
 * @brief Companion 处理逻辑 (可选)
 * 用于处理 Zygisk 的 Companion 服务请求（以 root 权限运行）。
 */
static int urandom = -1;
static void companion_handler(int socket_fd) {
    if (urandom < 0) {
        urandom = open("/dev/urandom", O_RDONLY);
    }
    unsigned r;
    if (read(urandom, &r, sizeof(r)) > 0) {
        LOGI("Companion service: generated random seed: %u", r);
        write(socket_fd, &r, sizeof(r));
    }
}

// 注册 Zygisk 模块类
REGISTER_ZYGISK_MODULE(ZygiskAttach)

// 注册 Companion 处理函数
REGISTER_ZYGISK_COMPANION(companion_handler)
