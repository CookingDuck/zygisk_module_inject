# Zygisk Module Injector

这是一个基于 Zygisk 实现的 Android So 注入工具。它能够无痕地将自定义 So 文件注入到目标进程中，并提供了一个 UI 管理端（Manager APK）来便捷地配置注入逻辑。

## 核心特性

- **多种注入模式**：支持 `memfd` (内存加载)、`custom_linker` (自定义链接器) 以及 `memfd_jit` (进阶规避模式)。
- **UI 管理端**：配套的 Android 应用，支持动态配置注入包名、So 文件及加载模式。
- **自动安装**：Magisk 模块刷入后会自动安装管理应用。
- **配置持久化**：管理端直接操作 `/data/adb/modules/` 下的配置文件，实现配置即时生效。

## 项目结构

- `module/`: Zygisk 核心模块，负责 Hook 进程专项化过程并执行注入。
- `manager/`: 管理应用源码，负责 UI 界面、配置解析及 So 文件分发。
- `template/`: Magisk 模块打包模板。
- `build_release.bat`: Windows 环境下的 APK 签名与发布脚本。

## 编译指南

本项目使用 Gradle 进行构建。请确保您的开发环境已安装 Android SDK 和 NDK (建议版本 r26+)。

### 1. 整体编译 (生成 Magisk 模块)

此命令会编译模块 So、编译管理端 APK，并最终打包成可刷入的 Magisk Zip 压缩包。

```bash
# 编译 Release 版本
./gradlew zipRelease

# 编译 Debug 版本 (用于开发测试，避免签名验证问题)
./gradlew :module:zipDebug
```
生成的产物位于：`module/build/outputs/magisk/`

### 2. 单独编译管理端 (Manager APK)

如果您只想更新管理端的 UI 逻辑：

```bash
# 编译 Release APK
./gradlew :manager:assembleRelease

# 编译并签名 (使用项目根目录下的 build_release.bat)
./build_release.bat
```

### 3. 单独编译 Zygisk 模块 (So)

```bash
./gradlew :module:assembleRelease
```

### 4. 清理编译缓存

```bash
./gradlew clean
```

## 使用说明

1. **安装模块**：通过编译命令生成 Magisk Zip 包，在 Magisk/Kitsune Mask 中刷入并重启。
2. **准备 So 文件**：将您需要注入的自定义 So 文件推送到手机的 `/data/local/tmp/` 目录下。
   ```bash
   adb push your_lib.so /data/local/tmp/
   ```
3. **配置注入**：打开安装好的管理应用（Manager）：
   - 点击添加配置，输入目标应用的**包名**。
   - 输入要加载的 **So 文件名**（即您放在 `/data/local/tmp/` 下的文件名）。
   - 选择 **加载模式**（默认为 `memfd`）。
4. **生效注入**：保存配置后，管理应用会自动将 `/data/local/tmp/` 下的 So 拷贝到模块私有目录。重启目标应用，注入即可完成。

## 技术细节

- **注入路径**：管理端会将 So 拷贝至 `/data/adb/modules/AAAIjt/modules/`，Zygisk 模块从中读取。
- **权限处理**：模块会自动修复配置文件与 So 文件的 SELinux 上下文（`u:object_r:magisk_file:s0`），确保在严苛模式下也能正常加载。
- **规避检测**：`memfd_jit` 模式通过模拟 JIT 缓存文件名称，能够绕过部分针对内存加载 So 的安全检测。