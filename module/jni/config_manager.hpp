#pragma once

#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <linux/limits.h>
#include <android/log.h>
#include "include/json.hpp"

#ifndef LOG_TAG
#define LOG_TAG "zheng_inject"
#endif
#define CFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define CFG_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define CFG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * @brief 注入加载模式
 */
enum class InjectModel {
    MEMFD,          ///< 默认：使用 memfd 加载
    CUSTOM_LINKER,  ///< 使用自定义链接器加载
    MEMFD_JIT       ///< memfd + 伪装命名 (针对部分检测规避)
};

/**
 * @brief 注入任务配置项
 */
struct InjectConfig {
    std::string package;
    bool        loadSo = false;
    std::string soName;
    InjectModel model  = InjectModel::MEMFD;
    bool        loadDex = false;
    std::string dexPath;
    bool        matched = false;
};

namespace cfg_util {

/**
 * 校验 SO 文件名：字母数字 . _ -，长度 1~128，禁止路径分隔符及 ".."。
 * 与 manager 端 ConfigRepository.isValidSoName 保持一致。
 */
inline bool isValidSoName(const std::string& s) {
    if (s.empty() || s.size() > 128) return false;
    if (s.find("..") != std::string::npos) return false;
    for (char c : s) {
        bool ok = (c >= 'A' && c <= 'Z') ||
                  (c >= 'a' && c <= 'z') ||
                  (c >= '0' && c <= '9') ||
                  c == '.' || c == '_' || c == '-';
        if (!ok) return false;
    }
    return true;
}

/**
 * 进程名匹配：等于包名 或 以 "包名:" 开头（多进程子进程：xxx.app:push）。
 * 这样可以避免 substr(0, len) 造成 com.foo 命中 com.foo.bar 的问题。
 */
inline bool processMatch(const std::string& process, const std::string& pkg) {
    if (pkg.empty() || process.empty()) return false;
    if (process == pkg) return true;
    if (process.size() > pkg.size() &&
        process.compare(0, pkg.size(), pkg) == 0 &&
        process[pkg.size()] == ':') {
        return true;
    }
    return false;
}

inline InjectModel parseModel(const std::string& s) {
    if (s == "custom_linker") return InjectModel::CUSTOM_LINKER;
    if (s == "memfd_jit")     return InjectModel::MEMFD_JIT;
    return InjectModel::MEMFD;
}

inline const char* modelName(InjectModel m) {
    switch (m) {
        case InjectModel::CUSTOM_LINKER: return "custom_linker";
        case InjectModel::MEMFD_JIT:     return "memfd_jit";
        case InjectModel::MEMFD:
        default:                          return "memfd";
    }
}

} // namespace cfg_util

/**
 * @brief 配置管理：解析 config.json 并按进程名匹配。
 *        独立于 Zygisk Api，方便单元测试和复用。
 */
class ConfigManager {
public:
    explicit ConfigManager(int dirfd) : moduleDirFd_(dirfd) {}

    InjectConfig getMatchingConfig(const char* process) {
        InjectConfig result;
        if (moduleDirFd_ < 0 || process == nullptr || *process == '\0') return result;

        // config.json 优先在模块目录，回退到上一级（部分 Zygisk 环境会传入 zygisk 子目录的 fd）
        int cfd = openat(moduleDirFd_, "config.json", O_RDONLY | O_CLOEXEC);
        if (cfd < 0 && errno == ENOENT) {
            cfd = openat(moduleDirFd_, "../config.json", O_RDONLY | O_CLOEXEC);
            if (cfd >= 0) parentRoot_ = true;
        }
        if (cfd < 0) {
            CFG_LOGE("config.json not found (errno=%d:%s)", errno, strerror(errno));
            return result;
        }

        struct stat st{};
        if (fstat(cfd, &st) != 0 || st.st_size <= 0 || st.st_size > kMaxCfgSize) {
            CFG_LOGE("config.json fstat invalid (size=%lld errno=%d)",
                     (long long) st.st_size, errno);
            close(cfd);
            return result;
        }

        std::vector<char> buf;
        buf.resize(static_cast<size_t>(st.st_size) + 1);

        size_t off = 0;
        while (off < (size_t) st.st_size) {
            ssize_t n = read(cfd, buf.data() + off, (size_t) st.st_size - off);
            if (n < 0) {
                if (errno == EINTR) continue;
                CFG_LOGE("read config.json failed: errno=%d:%s", errno, strerror(errno));
                close(cfd);
                return result;
            }
            if (n == 0) break;
            off += (size_t) n;
        }
        buf[off] = '\0';
        close(cfd);

        try {
            auto j = nlohmann::json::parse(buf.data(), buf.data() + off, nullptr, false);
            if (j.is_discarded() || !j.is_array()) {
                CFG_LOGE("config.json is not a valid JSON array");
                return result;
            }

            std::string proc(process);
            for (const auto& item : j) {
                if (!item.is_object()) continue;
                std::string pkg = item.value("package", "");
                if (!cfg_util::processMatch(proc, pkg)) continue;

                result.package = pkg;
                result.loadSo  = item.value("loadSo", false);
                result.soName  = item.value("soName", "");
                result.loadDex = item.value("loadDex", false);
                result.dexPath = item.value("dexPath", "");
                result.model   = cfg_util::parseModel(item.value("model", "memfd"));
                result.matched = true;
                break;
            }
        } catch (const std::exception& e) {
            CFG_LOGE("JSON parse error: %s", e.what());
        }
        return result;
    }

    /**
     * 通过 /proc/self/fd 反查模块目录的真实路径。
     */
    std::string getModuleRootPath() const {
        if (moduleDirFd_ < 0) return {};
        char proc_path[64];
        snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", moduleDirFd_);

        char path[PATH_MAX];
        ssize_t len = readlink(proc_path, path, sizeof(path) - 1);
        if (len <= 0) return {};
        path[len] = '\0';

        std::string full(path);
        if (parentRoot_) {
            size_t lastSlash = full.find_last_of('/');
            if (lastSlash != std::string::npos) return full.substr(0, lastSlash);
        }
        return full;
    }

private:
    static constexpr off_t kMaxCfgSize = 4 * 1024 * 1024;  // 4MB 上限
    int  moduleDirFd_;
    bool parentRoot_ = false;
};
