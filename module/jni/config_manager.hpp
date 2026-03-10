#pragma once

#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include "include/json.hpp"

/**
 * @brief 注入配置模型枚举
 */
enum class InjectModel {
    MEMFD,          ///< 默认：使用 memfd 加载
    CUSTOM_LINKER,  ///< 使用自定义链接器加载
    MEMFD_JIT       ///< 进阶：模拟 JIT 缓存的 memfd 加载 (针对 Hunter 规避)
};

/**
 * @brief 注入任务配置项
 */
struct InjectConfig {
    std::string package;          ///< 目标进程/包名
    bool loadSo = false;          ///< 是否加载 SO
    std::string soName;           ///< SO 文件名
    InjectModel model = InjectModel::MEMFD; ///< 加载模型
    bool loadDex = false;         ///< 是否加载 DEX (预留)
    std::string dexPath;          ///< DEX 路径 (预留)
    bool matched = false;         ///< 是否匹配成功
};

/**
 * @brief 配置管理器：负责解析 config.json 并匹配进程
 */
class ConfigManager {
public:
    /**
     * @brief 构造函数
     * @param dirfd Zygisk 提供的模块目录句柄
     */
    explicit ConfigManager(int dirfd) : moduleDirFd(dirfd) {}

    /**
     * @brief 根据进程名从配置文件中匹配相应的注入项
     */
    InjectConfig getMatchingConfig(const char* process) {
        InjectConfig result;
        if (moduleDirFd < 0 || !process) return result;

        // 尝试打开当前目录下的 config.json
        int cfd = openat(moduleDirFd, "config.json", O_RDONLY | O_CLOEXEC);
        
        // 如果找不到，尝试在上一级目录查找 (兼容某些 Zygisk 环境)
        if (cfd < 0) {
            cfd = openat(moduleDirFd, "../config.json", O_RDONLY | O_CLOEXEC);
            if (cfd >= 0) {
                isParentRoot = true; // 标记根目录在上一级
            }
        }

        if (cfd < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "zheng_inject", "Error: config.json NOT FOUND! (checked . and ..)");
            return result;
        }

        struct stat st;
        if (fstat(cfd, &st) == 0 && st.st_size > 0) {
            std::vector<char> buf(st.st_size + 1);
            ssize_t n = read(cfd, buf.data(), st.st_size);
            if (n > 0) {
                buf[n] = 0;
                try {
                    auto j = nlohmann::json::parse(buf.data());
                    if (j.is_array()) {
                        for (const auto& item : j) {
                            std::string pkg = item.value("package", "");
                            if (pkg.empty()) continue;

                            if (std::string(process).find(pkg) == 0) {
                                result.package = pkg;
                                result.loadSo = item.value("loadSo", false);
                                result.soName = item.value("soName", "");
                                result.loadDex = item.value("loadDex", false);
                                result.dexPath = item.value("dexPath", "");
                                
                                std::string modelStr = item.value("model", "memfd");
                                if (modelStr == "custom_linker") {
                                    result.model = InjectModel::CUSTOM_LINKER;
                                } else if (modelStr == "memfd_jit") {
                                    result.model = InjectModel::MEMFD_JIT;
                                } else {
                                    result.model = InjectModel::MEMFD;
                                }
                                
                                result.matched = true;
                                break;
                            }
                        }
                    }
                } catch (const std::exception& e) {
                    __android_log_print(ANDROID_LOG_ERROR, "zheng_inject", "JSON parse error: %s", e.what());
                }
            }
        }
        close(cfd);
        return result;
    }

    /**
     * @brief 获取模块根目录的绝对路径
     */
    std::string getModuleRootPath() {
        if (moduleDirFd < 0) return "";
        char path[PATH_MAX];
        char proc_path[64];
        snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", moduleDirFd);
        ssize_t len = readlink(proc_path, path, sizeof(path) - 1);
        if (len != -1) {
            path[len] = '\0';
            std::string fullPath(path);
            if (isParentRoot) {
                // 如果根目录在上一级，去掉末尾的子目录名
                size_t lastSlash = fullPath.find_last_of('/');
                if (lastSlash != std::string::npos) {
                    return fullPath.substr(0, lastSlash);
                }
            }
            return fullPath;
        }
        return "";
    }

private:
    int moduleDirFd;
    bool isParentRoot = false; // 是否需要回溯到父目录作为根
};
