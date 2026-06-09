package com.zheng.inject.manager.repository;

import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.topjohnwu.superuser.Shell;
import com.zheng.inject.manager.model.InjectConfig;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 与 Magisk 模块文件交互的仓库层。
 *
 * 安全策略：
 *   - 所有写入路径都是常量，不来自用户。
 *   - 配置 JSON 通过 base64 解码后写入，避免在 shell 命令字符串中嵌入用户文本。
 *   - SO 文件名做严格白名单校验，杜绝拼接路径与 shell 元字符。
 *   - 写入采用 "tmp + mv" 原子替换。
 */
public class ConfigRepository {
    private static final String TAG = "zheng";
    private static final String MODULE_DIR = "/data/adb/modules/AAAIjt";
    private static final String CONFIG_PATH = MODULE_DIR + "/config.json";
    private static final String CONFIG_TMP = MODULE_DIR + "/config.json.tmp";
    private static final String SO_DIR = MODULE_DIR + "/modules";
    private static final String SO_SRC_DIR = "/data/local/tmp";

    private static final Pattern SO_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public boolean isModuleInstalled() {
        Shell.Result result = Shell.cmd("[ -d " + MODULE_DIR + " ]").exec();
        return result.isSuccess();
    }

    public List<InjectConfig> loadConfigs() {
        if (!Shell.getShell().isRoot()) {
            Log.e(TAG, "loadConfigs: root not granted");
            return new ArrayList<>();
        }

        Shell.Result result = Shell.cmd(
                "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true"
        ).exec();
        if (!result.isSuccess()) {
            Log.e(TAG, "loadConfigs failed: " + result.getErr());
            return new ArrayList<>();
        }

        StringBuilder jsonBuilder = new StringBuilder();
        for (String line : result.getOut()) {
            jsonBuilder.append(line).append('\n');
        }
        String json = jsonBuilder.toString().trim();
        if (json.isEmpty()) return new ArrayList<>();

        try {
            Type listType = new TypeToken<ArrayList<InjectConfig>>() {}.getType();
            ArrayList<InjectConfig> list = gson.fromJson(json, listType);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse config.json", e);
            return new ArrayList<>();
        }
    }

    public boolean saveConfigs(List<InjectConfig> configs) {
        boolean jsonRes = saveJsonConfig(configs);
        boolean soRes = copySoFiles(configs);
        return jsonRes && soRes;
    }

    /**
     * 通过 base64 + 原子替换写入 config.json，避免 shell 转义问题。
     */
    public boolean saveJsonConfig(List<InjectConfig> configs) {
        if (!Shell.getShell().isRoot()) {
            Log.e(TAG, "saveJsonConfig: root not granted");
            return false;
        }

        String json = gson.toJson(configs);
        String b64 = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        Shell.Result result = Shell.cmd(
                "mkdir -p " + MODULE_DIR,
                "echo " + b64 + " | base64 -d > " + CONFIG_TMP,
                "chmod 644 " + CONFIG_TMP,
                "chcon u:object_r:magisk_file:s0 " + CONFIG_TMP + " 2>/dev/null || true",
                "mv -f " + CONFIG_TMP + " " + CONFIG_PATH
        ).exec();

        if (!result.isSuccess()) {
            Log.e(TAG, "saveJsonConfig failed: " + result.getErr());
            // 清理失败留下的 tmp 文件
            Shell.cmd("rm -f " + CONFIG_TMP).exec();
            return false;
        }
        return true;
    }

    /**
     * 拷贝 SO：仅当 loadSo=true 且 soName 合法时才执行。
     * SO 名经过严格白名单校验，避免 shell 元字符或路径穿越。
     */
    public boolean copySoFiles(List<InjectConfig> configs) {
        if (!Shell.getShell().isRoot()) {
            Log.e(TAG, "copySoFiles: root not granted");
            return false;
        }

        Shell.cmd("mkdir -p " + SO_DIR).exec();

        boolean allOk = true;
        for (InjectConfig config : configs) {
            if (!config.loadSo) continue;
            String soName = config.soName == null ? "" : config.soName.trim();
            if (soName.isEmpty()) continue;

            if (!isValidSoName(soName)) {
                Log.e(TAG, "copySoFiles: invalid soName skipped: " + soName);
                allOk = false;
                continue;
            }

            String src = SO_SRC_DIR + "/" + soName;
            String dst = SO_DIR + "/" + soName;

            Shell.Result existing = Shell.cmd("[ -f " + src + " ]").exec();
            if (!existing.isSuccess()) {
                Log.w(TAG, "copySoFiles: source not found, skipped: " + src);
                continue;
            }

            Shell.Result result = Shell.cmd("cp -f " + src + " " + dst).exec();
            if (!result.isSuccess()) {
                Log.e(TAG, "copySoFiles failed for " + soName + ": " + result.getErr());
                allOk = false;
                continue;
            }
            Shell.cmd(
                    "chmod 644 " + dst,
                    "chcon u:object_r:magisk_file:s0 " + dst + " 2>/dev/null || true"
            ).exec();
        }

        Shell.cmd(
                "chmod 755 " + SO_DIR,
                "chcon u:object_r:magisk_file:s0 " + SO_DIR + " 2>/dev/null || true"
        ).exec();

        return allOk;
    }

    /**
     * SO 文件名白名单：字母、数字、_ - . ，1-128 字符。
     * 拒绝任何路径分隔符或 shell 元字符。
     */
    public static boolean isValidSoName(String name) {
        if (name == null) return false;
        if (name.contains("..")) return false;
        return SO_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * 列出 /data/local/tmp 下的常规文件，需 Root。
     */
    public List<String> listTmpFiles() {
        List<String> result = new ArrayList<>();
        if (!Shell.getShell().isRoot()) return result;

        Shell.Result r = Shell.cmd(
                "ls -1A " + SO_SRC_DIR + " 2>/dev/null"
        ).exec();
        if (!r.isSuccess()) return result;

        for (String line : r.getOut()) {
            String name = line == null ? "" : line.trim();
            if (name.isEmpty()) continue;
            // 仅保留常规文件
            Shell.Result chk = Shell.cmd("[ -f " + SO_SRC_DIR + "/" + name + " ]").exec();
            if (chk.isSuccess()) result.add(name);
        }
        return result;
    }

    /**
     * 检查 /data/local/tmp 下指定文件是否存在。soName 经过白名单校验后才允许传入。
     */
    public boolean tmpFileExists(String soName) {
        if (!isValidSoName(soName)) return false;
        if (!Shell.getShell().isRoot()) return false;
        Shell.Result r = Shell.cmd("[ -f " + SO_SRC_DIR + "/" + soName + " ]").exec();
        return r.isSuccess();
    }
}
