package com.zheng.inject.manager.repository;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.topjohnwu.superuser.Shell;
import com.zheng.inject.manager.model.InjectConfig;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置仓库：负责与 Magisk 模块的文件交互 (Root 权限)
 */
public class ConfigRepository {
    private static final String TAG = "zheng";
    private static final String MODULE_DIR = "/data/adb/modules/AAAIjt";
    private static final String CONFIG_PATH = MODULE_DIR + "/config.json";
    private final Gson gson = new Gson();

    /**
     * 检查模块是否已安装
     */
    public boolean isModuleInstalled() {
        Shell.Result result = Shell.cmd("[ -d " + MODULE_DIR + " ]").exec();
        return result.isSuccess();
    }

    /**
     * 读取配置列表
     */
    public List<InjectConfig> loadConfigs() {
        if (!Shell.getShell().isRoot()) {
            return new ArrayList<>();
        }

        List<String> output = Shell.cmd("cat " + CONFIG_PATH).exec().getOut();
        StringBuilder jsonBuilder = new StringBuilder();
        for (String line : output) {
            jsonBuilder.append(line);
        }

        String json = jsonBuilder.toString();
        if (json.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            Type listType = new TypeToken<ArrayList<InjectConfig>>() {
            }.getType();
            return gson.fromJson(json, listType);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse config.json", e);
            return new ArrayList<>();
        }
    }
/**
 * 保存所有配置（入口函数）
 */
public boolean saveConfigs(List<InjectConfig> configs) {
    boolean jsonRes = saveJsonConfig(configs);
    boolean soRes = copySoFiles(configs);
    return jsonRes && soRes;
}

/**
 * 1. 专门保存 JSON 配置文件
 */
public boolean saveJsonConfig(List<InjectConfig> configs) {
    if (!Shell.getShell().isRoot()) {
        Log.e(TAG, "Root access denied, cannot save JSON");
        return false;
    }

    Log.i(TAG, "Starting to save JSON config...");
    String json = gson.toJson(configs).replace("'", "'\\''");

    Shell.Result result = Shell.cmd(
            "mkdir -p " + MODULE_DIR,
            "echo '" + json + "' > " + CONFIG_PATH,
            "chmod 644 " + CONFIG_PATH,
            "chcon u:object_r:magisk_file:s0 " + CONFIG_PATH + " 2>/dev/null || true"
    ).exec();

    if (result.isSuccess()) {
        Log.i(TAG, "JSON config saved successfully to: " + CONFIG_PATH);
    } else {
        Log.e(TAG, "Failed to save JSON config: " + result.getErr());
    }
    return result.isSuccess();
}

/**
 * 2. 专门拷贝 SO 文件
 */
public boolean copySoFiles(List<InjectConfig> configs) {
    if (!Shell.getShell().isRoot()) {
        Log.e(TAG, "Root access denied, cannot copy SO files");
        return false;
    }

    Log.i(TAG, "Starting to copy SO files...");
    String modulesDir = MODULE_DIR + "/modules";
    Shell.cmd("mkdir -p " + modulesDir).exec();

    boolean allOk = true;
    for (InjectConfig config : configs) {
        String soName = config.soName;
        if (soName == null || soName.trim().isEmpty()) continue;

        String src = "/data/local/tmp/" + soName.trim();
        String dst = modulesDir + "/" + soName.trim();

        Log.i(TAG, "Copying SO: " + src + " -> " + dst);
        // 使用 -f 强制覆盖，并对路径加引号防止空格问题
        Shell.Result result = Shell.cmd("cp -f \"" + src + "\" \"" + dst + "\"").exec();

        if (result.isSuccess()) {
            Log.i(TAG, "Successfully copied: " + soName);
            // 每个文件拷贝完立即修复权限和上下文
            Shell.cmd("chmod 644 \"" + dst + "\"",
                      "chcon u:object_r:magisk_file:s0 \"" + dst + "\" 2>/dev/null || true").exec();
        } else {
            Log.e(TAG, "Failed to copy: " + soName + ". Error: " + result.getErr());
            allOk = false;
        }
    }

    // 最后统一刷一下目录权限
    Shell.cmd("chmod 755 " + modulesDir, 
              "chcon u:object_r:magisk_file:s0 " + modulesDir + " 2>/dev/null || true").exec();

    return allOk;
}
}
