---
status: accepted
---

# MiLink 仅承载入口，规则读写归属 VoiceAssist 适配器

## Context

系统通常把跨设备互联设置放在 MiLink 相关页面中，但手机到 PC/平板的实际通知转发和规则 store 位于 `com.miui.voiceassist`。两个应用运行在不同进程，MiLink 内组件无法直接持有 VoiceAssist 进程中的 `NotificationSubscriptionStore`。

如果为了入口位置而把规则读写放进 MiLink，就只能直接改文件或依赖另一套跨进程接口，无法满足运行中热生效和单一权威状态要求。

## Decision

- MiLink 只注入一个跨设备设置入口，负责打开独立的通知流转设置面板，不承载规则数据读写。
- 设置面板运行在模块自有的独立进程中。
- `com.miui.voiceassist` 内仅注入范围受控的数据适配器，由它访问同一进程内的规则 store、权限 store 及相关内部 API。
- 面板与适配器通过现有导出 ContentProvider 的 `call()` 形成窄 RPC 通道；适配器校验调用方 UID、包名、签名证书和协议版本。
- RPC 只暴露固定领域操作，不暴露任意反射、方法名、文件路径或原始文件写入。
- VoiceAssist 内的 store 是规则与目标授权的唯一权威状态。适配器持有应用私有目录中的 LKG 快照，并负责串行写入、回读校验、尽力回滚和持久只读故障态。
- 面板不可用、认证失败、协议不匹配或能力探测失败时必须 fail-closed，不得退回本地镜像或直接文件写入。

## Consequences

- 设置入口符合 MiLink 的系统信息架构，但数据所有权仍与真正执行转发的应用一致。
- 跨进程调用链比同进程设置页更长，必须维护认证、协议版本和故障态。
- 具体的 MiLink 页面注入点，以及复用哪一个 VoiceAssist 导出 ContentProvider，仍需后续定点确认，不影响本 ADR 确定的责任边界。
