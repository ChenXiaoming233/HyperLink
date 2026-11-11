# HyperLink 开发与测试

当前阶段只包含可离线验证的模块骨架、领域接口、RPC 纯层和 fake 只读面板。它不包含
真实 LSPosed Hook、VoiceAssist Provider 接线或设备级兼容性验收。

## fake 只读面板

独立设置面板只调用内存中的 `FakeNotificationFlowService`，不读取 VoiceAssist 规则
文件、权限 store、Provider 或 `notification-subscriptions.json`。所有 fake 设备名、
设备 ID、来源、规则 ID 和 DSL 示例均为合成占位（如 `Demo-PC`、`demo-target-a`、
`demo-rule-1`），不包含真实设备关联信息。面板提供正常、过期缓存、空数据、读取失败、
系统通知使用权缺失和已撤销演示场景，并可切换 fake 活体状态。

只读快照保留目标的类型、在线、可达、目标授权和接收能力，以及规则的原始顺序、
`ruleId`、来源、目标、上游状态、规则摘要和完整 DSL。面板仅展示具备接收能力的
PC、PAD、MAC 目标；手机等其他类型会被过滤。目标授权与系统通知使用权分别展示，
离线目标保留显示并明确说明不会排队或自动重试。

过期快照会标记“缓存快照已过期”，且不提供写入入口。`NotificationFlowService` 只有
`readSnapshot()` 操作；“已启用”或 fake 快照成功均不代表通知已经送达，也不代表真实
MiLink/VoiceAssist 已接线。未激活、进程不可达或能力不足时仍展示各自原因，不把未知
列表误写成“空数据”；读取失败时列表区显示“无法确定”，而不是断言没有设备或规则。
在线和可达分别展示：在线但不可达与当前离线使用不同提示。
已暂停规则若指向缺失或不可接收的目标，会显示“已暂停（目标不可用，恢复前需处理）”，
避免同时出现“已暂停”和“受阻”的冲突状态。

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
权限 store。只读面板测试覆盖目标过滤、在线/可达/双授权状态、规则数量与顺序、
`ruleId`、状态、目标、完整 DSL、规则摘要、过期缓存，以及失败和空数据安全文案。

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
- 尚无生产 Provider `call()` 适配器或真实快照 DTO 序列化。
- fake 状态、单元测试和编译成功均不代表真实 MiLink/VoiceAssist 集成完成。
