# HyperLink 开发与测试

当前阶段只包含可离线验证的模块骨架、领域接口、RPC 纯层和 fake 面板。它不包含真实
LSPosed Hook、VoiceAssist Provider 接线或设备级兼容性验收。

## 环境

- JDK 17
- Android SDK Platform 36
- Gradle Wrapper（仓库自带）

Windows Git Bash 中可如下设置环境变量：

```bash
export JAVA_HOME=/d/Scoop/Folder/apps/openjdk17/current
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/d/AppData/Android/Sdk
```

## 单元测试

```bash
./gradlew --no-daemon testDebugUnitTest
```

测试只运行 JVM 纯逻辑与 Android 编译桩，不会连接设备、真实 Provider、真实规则文件或
权限 store。

## 构建 APK

```bash
./gradlew --no-daemon assembleDebug
```

生成的 APK 可安装并打开独立的 fake 状态面板。LSPosed 元数据与 `xposed_init` 仅建立
模块加载骨架；当前模块不会安装任何方法 Hook，也不会改变 MiLink 或 VoiceAssist 行为。

## 当前边界

- `MiLinkEntryExtension` 是保留的未接线扩展点，不存在本地降级入口。
- 尚无生产用的 `CallerEvidenceResolver`；它必须由平台 UID 归属、包名和签名采集实现，
  且解析结果的 UID 必须与 Binder 调用方 UID 一致。
- 尚无生产 Provider `call()` 适配器。
- fake 状态、单元测试和编译成功均不代表真实 MiLink/VoiceAssist 集成完成。
