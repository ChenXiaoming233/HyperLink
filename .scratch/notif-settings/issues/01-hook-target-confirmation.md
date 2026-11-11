# 01: Hook 目标确认

**What to build:** 确认实现所需的外部事实，使后续模块能够稳定接入 MiLink 设置页和 VoiceAssist 进程，而不是在大型实现 ticket 中边写边猜。提供的 VoiceAssist 与 MiLink 安装包作为主要静态证据来源；交付内容区分 APK 已证明事实和仍需实机确认的运行时事实，不包含完整业务实现。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 记录 VoiceAssist 与 MiLink 的版本、包名、APK SHA-256 和签名信息。
- [ ] 基于 APK 定位 MiLink 跨设备设置页的候选入口，并记录 Activity、Fragment、资源或 UI 节点证据。
- [ ] 基于 APK 枚举可复用的 VoiceAssist 导出 ContentProvider，记录 authority、exported、permission、进程声明和调用约束。
- [ ] 基于 APK 选定一个不需要修改目标 APK manifest、且与规则 store 位于同一进程的候选 Provider。
- [ ] 基于 APK 和 manifest 设计调用方 UID、包名、签名证书及协议版本认证，不依赖目标应用签名权限。
- [ ] 基于 APK 提取类、方法、字段、Intent、资源和系统设置页入口，形成能力探测与 Hook 候选。
- [ ] 实机确认候选 MiLink 入口可稳定注入，并记录实际页面层级与启动行为。
- [ ] 实机确认候选 Provider 可达，验证 force-stop、冷启动、进程重启和失败关闭行为。
- [ ] 实机确认 UID、SELinux、包可见性等运行时限制不会破坏认证和 RPC。
- [ ] 实机确认目标设备信息和通知监听器状态可读取，且不触发规则或通知状态变更。
- [ ] 将 APK 已证明事实、实机确认结果、候选方案、最终推荐和未解决风险追加到本 ticket 的 `## Comments`。
- [ ] 若结论改变现有 ADR，明确列出需要同步更新的决策，不直接覆盖历史记录。

