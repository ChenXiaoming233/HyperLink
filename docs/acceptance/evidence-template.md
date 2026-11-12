# 验收证据归档模板

> 本文件只含占位值。复制后也不得写入真实设备标识、序列号、账号、IP 或 ruleId。

## 运行摘要

- 验收 ID：`<ACC-00>`
- Issue 链接：`https://github.com/ChenXiaoming233/HyperLink/issues/<Issue编号>`
- 执行人：`<执行人代号>`
- 证据时间：`<YYYY-MM-DD HH:MM:SS UTC+08:00>`
- 结果：`<PASS|FAIL|ABORTED|BLOCKED>`
- 设备型号：`<设备型号>`
- 系统版本：`<HyperOS 版本 / Android 版本 / Build>`
- VoiceAssist 版本：`<versionName / versionCode>`
- MiLink 版本：`<versionName / versionCode>`
- APK SHA-256：`<HyperLink APK 64位十六进制>`
- VoiceAssist APK SHA-256：`<64位十六进制>`
- MiLink APK SHA-256：`<64位十六进制>`
- VoiceAssist 签名 SHA-256：`<来自发布审批记录的64位十六进制>`
- MiLink 签名 SHA-256：`<来自发布审批记录的64位十六进制>`
- 签名验证状态：`<VERIFIED / UNVERIFIED / MISMATCH>`
- 前置规则文件哈希：`<64位十六进制>`
- 前置授权文件哈希：`<64位十六进制>`
- 恢复后规则文件哈希：`<64位十六进制或不适用>`
- 恢复后授权文件哈希：`<64位十六进制或不适用>`
- 日志归档：`<相对路径与日志 SHA-256>`
- 剩余风险：`<无或风险摘要>`

## 执行记录

| 步骤 | 预期 | 实际 | 证据引用 | 结果 |
| --- | --- | --- | --- | --- |
| `<步骤编号>` | `<预期结果>` | `<脱敏后实际结果>` | `<screenshots/文件名>` | `<PASS / FAIL / BLOCKED>` |

## 转发尝试证据

- 测试通知生成方式：`<执行人手动生成的合成通知说明>`
- runId SHA-256 前缀：`<12位十六进制>`
- forwardId SHA-256 前缀：`<12位十六进制>`
- forwardAttempts：`<1>`
- attempt：`<1>`

## 恢复检查

- LKG 是否在读回中可见：`<是|否>`
- 回滚方式：`<受控 API|恢复界面|不适用>`
- 回滚结果：`<ROLLBACK_VERIFIED|ROLLBACK_UNVERIFIED|不适用>`
- 恢复后规则与授权读回：`<脱敏摘要>`
- 是否存在持久只读故障：`<否|是>`

## 归档目录

```text
evidence/<run-id>/
  README.md
  preflight.txt
  matrix-result.md
  forward-attempts.txt
  screenshots/
```

日志和读回文件必须已过滤、脱敏并附 SHA-256。禁止归档完整通知内容、原始 ruleId、设备
序列号、账号、IP、签名私钥或厂商 APK。
