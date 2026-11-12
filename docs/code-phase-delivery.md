# HyperLink 代码阶段交付说明（#12–#20 与 #2 探针）

## 交付状态

- 集成分支：`codex/hyperlink-code-12-20`
- 代码工单：#12–#20 已关闭
- #2 Hook 目标确认：静态与实机确认通过，已关闭
- 实机环境工单 #11：已关闭
- 后续设备验收：#3–#10 保持 OPEN
- 总规格 #1 保持 OPEN

本阶段完成的是可离线构建、可运行 JVM 测试的 fake/纯逻辑代码骨架，以及 Issue #2 所需的
只读 LSPosed 目标探针。探针不包含规则读写、通知发送或设备级业务验收。

## 已完成的代码能力

- 静态确认 VoiceAssist 与 MiLink 目标，记录推荐 Provider 与 fail-closed 认证边界。
- 建立 Android/LSPosed 工程、独立设置面板、版本化 RPC 和五种 fake 活体状态。
- 建立 fake 只读 gateway，无损展示目标、规则、DSL、授权和过期缓存状态。
- 建立目标授权、能力探测、未知字段禁写、LKG、乐观并发、回读校验和基础回滚。
- 建立预设与结构化编辑器，支持应用多选、OR 分支和自动规则摘要。
- 支持完整 DSL、`regex/not_regex`、复杂组合和整份模型只读保护。
- 支持暂停、恢复、短时撤销、删除和 fake 权限同步。
- 支持受控重试、自动回滚、持久只读故障和默认关闭的调试模式。
- 提供设备验收矩阵、只读预检、证据模板、安全退出和 ACC-14 日志校验入口。
- 提供 MiLink preference 注入与 VoiceAssist `LinkContentProvider.call()` 认证探针。

## 自动化验证

最终集成分支使用的常规门禁：

```bash
export JAVA_HOME=/d/Scoop/Folder/apps/openjdk17/current
export ANDROID_HOME=/d/AppData/Android/Sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

最后一次完整 JVM 测试结果为 201/201 通过；Lint 为 0 error，仅有既有非阻塞 warning；
`assembleDebug` 与 `assembleDebugAndroidTest` 成功。各工单的测试与 review 记录见对应
GitHub Issue 评论。

验收工具另有仓库自检和 Issue #2 设备探针：

```bash
bash scripts/acceptance/check-acceptance-kit.sh
HYPERLINK_ACCEPTANCE_DEVICE_OK=1 \
ANDROID_SERIAL='<设备序列号占位>' \
bash scripts/acceptance/probe-hook-targets.sh
```

仓库自检耗时数分钟，不作为每次代码构建的默认门禁；设备探针只能由执行人显式启动。

## Issue #2 设备确认

2026-11-12 在 Xiaomi `25128PNA1C`、HyperOS `OS4.0.6.0.XPACNXM`、Android 17、
SELinux `Enforcing` 环境完成确认：

- MiLink `ConnectivityFragment` 探针稳定加载，`hyperlink_notification_flow` 插入
  `category_pref_key_cross_device`；手动点击后前台 Activity 为模块 `SettingsActivity`。
- VoiceAssist Provider 冷启动返回 `SERVICE_UNAVAILABLE`；listener 就绪后认证 RPC 返回
  规则 `3` 条、目标设备状态 `3` 个、通知监听使用权 `true`。
- HyperLink UID `10602`、VoiceAssist UID `10158`、MiLink UID `10186`；认证通过证明 UID、
  包名、签名 pin 与包可见性组合可工作。
- VoiceAssist 前后 PID 均存在且不同，进程重启验证通过。
- 探针前后规则文件 SHA-256 均为
  `43a2e5d4b140aeafdb6d21c644b5a78b615c6be79434a7239aefd8128775e5e8`，授权文件均为
  `ae0c5b95bf3f41a2689d624db0e448d7a3471ee02b5c1bd2aac377bcc1ad8708`。

## 尚未执行的后续实机项

- 生产规则适配器、设置面板 RPC 接线和真实 store 操作。
- 真实规则 store、权限 store、LKG 持久化和 Provider 冷启动。
- 真实规则 CRUD 热生效与跨进程原子性。
- 真实通知转发、桌面端展示、单次 `forward_attempt` 日志证据。
- #3–#10 的完整纵向验收。

## 后续实机验收顺序

1. 按 `docs/acceptance/README.md` 运行设备预检。
2. 按 `docs/acceptance/matrix.md` 的 ACC-01–ACC-14 顺序执行；失败时按安全退出流程停止。
3. 仅通过模块 LKG/受控 API 备份和恢复规则，禁止 shell 直接覆盖生产 JSON。
4. 将设备型号、系统版本、应用版本、APK SHA-256、时间、日志和 Issue 链接写入证据模板。
5. 修复设备验收发现的问题后重新执行相关矩阵项；未执行 ACC-14 前不得宣称单次转发成立。

## 边界声明

fake gateway、fake store、JVM 测试和编译成功均不代表真实业务适配完成。#2 只证明入口、
Provider、认证和只读能力探测可用；在 #3–#10 完成前，不应宣称真实规则 CRUD 热生效、
权限同步、通知送达或完整设备兼容性验收通过。
