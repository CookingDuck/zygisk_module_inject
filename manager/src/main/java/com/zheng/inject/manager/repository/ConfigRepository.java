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
    private static final String TAG = "ConfigRepo";
    private static final String CONFIG_PATH = "/data/adb/modules/AAAIjt/config.json";
    private final Gson gson = new Gson();

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
            Type listType = new TypeToken<ArrayList<InjectConfig>>(){}.getType();
            return gson.fromJson(json, listType);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse config.json", e);
            return new ArrayList<>();
        }
    }

    /**
     * 保存配置列表
     */
    public boolean saveConfigs(List<InjectConfig> configs) {
        String json = gson.toJson(configs);
        // 通过 Root 写入文件
        // 1. 写入临时文件 2. 移动到目标位置
        String tmpPath = "/data/local/tmp/inject_config.json";
        Shell.cmd("echo '" + json.replace("'", "'\\''") + "' > " + tmpPath).exec();
        Shell.Result result = Shell.cmd("mv " + tmpPath + " " + CONFIG_PATH,
                                        "chmod 644 " + CONFIG_PATH).exec();
        return result.isSuccess();
    }
}
