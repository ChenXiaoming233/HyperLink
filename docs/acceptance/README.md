# 设备验收工具包

本目录准备 Issue #2 的目标探针与 Issue #10 的实机验收材料，不自动执行任何实机验收，
也不关闭 Issue #2、#10 或 #11。仓库自检保持离线；设备预检、目标探针和功能矩阵只在
执行人显式设置授权环境变量后连接设备。探针只读取脱敏运行时摘要，不读取规则正文，
不发送通知。

## 入口

离线仓库自检：

```bash
bash scripts/acceptance/check-acceptance-kit.sh
bash -n scripts/acceptance/*.sh
```

构建级校验：

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

实机预检只能由获授权的执行人在设备恢复后手动启动。本工单没有运行下列命令：

```bash
HYPERLINK_ACCEPTANCE_DEVICE_OK=1 \
ANDROID_SERIAL='<设备序列号占位>' \
VOICEASSIST_SIGNER_SHA256='<64位十六进制占位>' \
MILINK_SIGNER_SHA256='<64位十六进制占位>' \
bash scripts/acceptance/preflight-device.sh
```

`ANDROID_SERIAL` 只作为本机环境变量使用，不写入仓库或证据模板。签名 SHA-256 必须取自
发布审批记录；脚本不会拉取、复制或反编译厂商 APK。

预检要求 `adb devices` 中恰好一台 online 设备且序列号匹配。若设备侧 `dumpsys` 无法提供可
计算的明确证书字节，脚本输出 `SIGNATURE_VERIFICATION=UNVERIFIED` 并以非零退出；人工审批
记录不能把该状态改写成通过。

## 可发现文件

- [14 项验收矩阵](matrix.md)（`docs/acceptance/matrix.md`）：逐项对应 Issue #10 前 14 条功能验收。
- [验收操作手册](operations.md)：只读预检、受控通知测试、日志过滤、备份恢复与安全退出。
- [证据模板](evidence-template.md)：归档字段、脱敏规则和目录结构。
- `scripts/acceptance/preflight-device.sh`：设备侧只读元数据与哈希采集。
- `scripts/acceptance/probe-hook-targets.sh`：Issue #2 的 MiLink 入口与 VoiceAssist
  Provider 只读运行时探针。
- `scripts/acceptance/verify-forward-attempt.sh`：仅从标准输入验证一次转发尝试。
- `scripts/acceptance/test-acceptance-kit.sh`：不依赖设备的校验器与 fake adb 行为测试。

## 执行顺序

1. 复制证据模板，填写执行窗口、Issue #10 与 Issue #20 链接；不得填写真实设备 ID、序列号、
   账号、IP 或 `ruleId`。
2. 运行设备预检并保存终端输出。Issue #2 随后运行
   `scripts/acceptance/probe-hook-targets.sh`；任一步失败即停止。
3. 按 [操作手册](operations.md) 确认 LKG 与受控 API 可用。任一条件不满足时，将相关场景标记为
   `BLOCKED`，不得退化为 shell 覆盖生产 JSON。
4. 按 [矩阵](matrix.md) 从 `ACC-01` 顺序执行。每项完成后立即记录结果、脱敏证据和日志哈希。
5. 第 15 条 `#10` 要求是横切证据记录，不另设第 15 个功能场景。
6. 失败、中止或回滚时按操作手册的安全退出流程结束，不以继续重试换取通过。

## 边界

- 本工具包不提供直接改生产规则 JSON、授权绕过、回调写文件、强制写入或删除入口。
- `fake` 单元测试通过不等于真实 VoiceAssist、MiLink 或通知链路完成验收。
- Issue #10 的最终结果由实机执行记录决定；Issue #11 的真实环境工作不在本目录范围内。
