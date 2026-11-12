# HyperLink 代码阶段交付说明（#12–#20）

## 交付状态

- 集成分支：`codex/hyperlink-code-12-20`
- 代码工单：#12–#20 已关闭
- 设备验收：#2–#10 与实机环境工单 #11 保持 OPEN
- 总规格 #1 保持 OPEN

本阶段完成的是可离线构建、可运行 JVM 测试的 fake/纯逻辑代码骨架。它不包含真实
LSPosed Hook、VoiceAssist Provider 接线、真实 MiLink 入口注入或设备级验收。

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

## 自动化验证

最终集成分支使用的常规门禁：

```bash
export JAVA_HOME=/d/Scoop/Folder/apps/openjdk17/current
export ANDROID_HOME=/d/AppData/Android/Sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

最后一次完整 JVM 测试结果为 197/197 通过；Lint 为 0 error，仅有既有非阻塞 warning；
`assembleDebug` 成功。各工单的测试与 review 记录见对应 GitHub Issue 评论。

验收工具另有仓库自检：

```bash
bash scripts/acceptance/check-acceptance-kit.sh
```

该自检耗时数分钟，不作为每次代码构建的默认门禁。

## 尚未执行的实机项

- 真实 LSPosed 模块加载、MiLink 设置入口注入和 VoiceAssist 进程内适配器。
- 生产 `CallerEvidenceResolver`、ContentProvider `call()` 适配器和真实认证采集。
- 真实规则 store、权限 store、LKG 持久化和 Provider 冷启动。
- 热生效、进程重启、SELinux、UID、包可见性和跨进程原子性。
- 真实通知转发、桌面端展示、单次 `forward_attempt` 日志证据。
- #2–#10 的完整纵向验收与 #11 的实机环境验证。

## 恢复设备后的下一步

1. 先完成 #11，确认指定设备、root shell、SELinux、VoiceAssist/MiLink 进程和版本基线。
2. 阅读 `docs/acceptance/README.md`，执行只读预检并归档脱敏证据。
3. 按 `docs/acceptance/matrix.md` 的 ACC-01–ACC-14 顺序执行；失败时按安全退出流程停止。
4. 仅通过模块 LKG/受控 API 备份和恢复规则，禁止 shell 直接覆盖生产 JSON。
5. 将设备型号、系统版本、应用版本、APK SHA-256、时间、日志和 Issue 链接写入证据模板。
6. 修复设备验收发现的问题后重新执行相关矩阵项；未执行 ACC-14 前不得宣称单次转发成立。

## 边界声明

fake gateway、fake store、JVM 测试和编译成功均不代表真实 VoiceAssist/MiLink 集成完成。
在 #10 与 #11 完成前，不应宣称真实热生效、进程恢复、权限同步、通知送达或设备兼容性验收通过。
