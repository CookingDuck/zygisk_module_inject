package com.zheng.inject.manager.model;

import com.google.gson.annotations.SerializedName;

/**
 * 对应 config.json 中的每一项配置
 */
public class InjectConfig {
    @SerializedName("package")
    public String packageName;
    
    public boolean loadSo;
    public String soName;
    
    @SerializedName("model")
    public String injectModel = "memfd"; // memfd, custom_linker, memfd_jit
    
    public boolean loadDex;
    public String dexPath;

    // 默认构造函数
    public InjectConfig() {}

    public InjectConfig(String packageName) {
        this.packageName = packageName;
        this.loadSo = true;
        this.soName = "libinject.so";
        this.injectModel = "memfd";
    }
}
