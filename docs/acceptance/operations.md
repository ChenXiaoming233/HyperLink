# 验收操作手册

本手册只定义实机恢复后的受控操作。当前工单不执行其中的设备命令，也不发送通知。

## PF 只读预检

预检脚本先枚举 `adb devices`，要求恰好一台状态为 `device` 的设备，且其序列号与
`ANDROID_SERIAL` 完全一致。离线、未授权或多设备状态均 fail-fast。脚本还要求 shell UID
为 0，并显式检查 VoiceAssist 与 MiLink PID 非空。它只打印设备与包元数据、进程、UID、
SELinux、私有文件 `stat` 和 SHA-256，不读取或输出规则内容，也不复制 APK。

```bash
HYPERLINK_ACCEPTANCE_DEVICE_OK=1 \
ANDROID_SERIAL='<设备序列号占位>' \
VOICEASSIST_SIGNER_SHA256='<64位十六进制占位>' \
MILINK_SIGNER_SHA256='<64位十六进制占位>' \
bash scripts/acceptance/preflight-device.sh
```

覆盖范围：

- `ro.product.model`、Android release、SDK、build fingerprint。
- `com.miui.voiceassist` 与 `com.milink.service` 的 versionName、versionCode、UID 和 APK SHA-256。
- 两个目标进程 PID 与 `getenforce`；任一 PID 为空或非 `Enforcing` 时直接失败。
- `notification-subscriptions.json` 与 `notification-forward-permissions.json` 的存在性、大小、
  修改时间、权限、属主及 SHA-256。

Android `dumpsys package` 的 `PackageSignatures` 内部摘要不是证书 SHA-256，不能拿它冒充
签名指纹。脚本先要求 `VOICEASSIST_SIGNER_SHA256` 和 `MILINK_SIGNER_SHA256` 均存在且为
64 位十六进制，缺失时在任何设备命令前 fail-fast。

若 `dumpsys` 明确提供 `certificateHex=` 或 `signingCertificate=` 证书字节，脚本在本地计算
SHA-256 并与预登记值比较，输出 `SIGNATURE_VERIFICATION=VERIFIED` 或
`SIGNATURE_VERIFICATION=MISMATCH`。若无法提取明确证书字节、本地缺少转换工具或无法比较，
脚本输出 `SIGNATURE_VERIFICATION=UNVERIFIED` 并以非零退出，不会输出最终 PASS。

无需复制 APK 的人工审批证据路径：

1. 发布审批记录提供审批人确认的 APK SHA-256 和证书 SHA-256，并绑定包名与版本。
2. 预检输出设备侧 APK SHA-256、签名元数据和本地实际证书 SHA-256，供审批人与记录逐项核对。
3. 没有可提取证书字节时，人工审批不能把脚本的 `UNVERIFIED` 改写成通过；该验收项标记
   `BLOCKED`。禁止为补齐证据而拉取、复制、反编译厂商 APK。

## 受控通知测试

### 日志契约

真实接线必须只在一次实际 A2A 投递开始时写一条不含敏感内容的标记：

```text
HyperLinkAcceptance forward_attempt runId=<本次唯一不透明ID> timestampEpoch=<Unix秒> forwardId=<通知唯一不透明ID> attempt=<正整数>
```

- 载入规则、渲染 UI、预览、排队和普通状态变化不得写该标记。
- `runId` 每次验收运行重新生成且不复用；历史日志通过 `runId` 或 `timestampEpoch` 排除。
- `timestampEpoch` 在真实 A2A 投递开始时生成，用于 `--since` 过滤历史日志。
- `forwardId` 代表一次用户可见的逻辑投递；同一逻辑投递只允许一个值和一个 attempt 标记。
- `attempt` 从 1 开始，只有实际发生额外投递时才递增，不得用 UI 重试次数替代。
- 标记不得包含通知正文、应用名、包名、账号、设备标识或 `ruleId`。
- 如果真实接线尚未实现该标记，`ACC-14` 标记 `BLOCKED`，不得凭截图猜测通过。

### 日志过滤

执行人手动生成一条无敏感内容的合成通知，同时只过滤标记行：

```bash
adb logcat -v threadtime | grep --line-buffered 'HyperLinkAcceptance forward_attempt'
```

测试结束后将同一过滤结果交给仓库校验器。校验器只从标准输入读取，不连接设备，且必须同时
提供 `--run-id`、`--since`、`--forward-id`；任一缺失或为空值都会非零退出：

```bash
adb logcat -d -v epoch \
  | grep 'HyperLinkAcceptance forward_attempt' \
  | bash scripts/acceptance/verify-forward-attempt.sh \
      --run-id '<本次唯一 runId 占位>' \
      --since '<本次开始 epoch 占位>' \
      --forward-id '<预期 forwardId 占位>'
```

校验器先按 `runId` 和 `timestampEpoch` 时间窗选出 current-run 的全部 `forward_attempt`
标记，包含不同 `forwardId` 的标记；不得先按 `--forward-id` 过滤。随后按标记出现次数统计：

- 当前 run 总数必须恰好为 1，且该唯一标记的 `forwardId` 必须等于 `--forward-id`。
- 先读取每个标记的 `runId`。`runId` 与 expected 不同的标记即使 `forwardId` 相同也可忽略。
- 一旦 `runId == expected`，任何缺失 `timestampEpoch`、`forwardId` 或 `attempt`，以及
  timestamp 非数字或早于 `--since`，都必须直接 FAIL，不得忽略后继续。
- expected-run 的旧 timestamp 不能当作历史日志忽略；所有 expected-run 标记都必须参与总数
  统计，避免旧标记掩盖当前重复投递。
- 同一行出现多个标记、任一字段重复、`attempt` 不为 1，或 current-run 同时出现 expected 与
  其他 `forwardId` 都会失败。
- 标记 token 必须精确等于 `forward_attempt`；`forward_attempted` 等相似 token 不计数。
- 所有三项 scope 必须显式提供；没有 current-run 标记或字段不完整同样失败。

只有满足上述条件才输出 PASS，并只打印 `runId` 与 `forwardId` 的 SHA-256 前缀。归档完整
原始日志前必须再做同一过滤与脱敏，禁止归档通知正文。

## BR 规则与授权备份恢复

### 前置检查

- 记录 `notification-subscriptions.json` 与 `notification-forward-permissions.json` 的
  `PF` 前置哈希、大小、时间和受控 API 读回摘要。
- 确认模块已创建可读回的 LKG，且 LKG 属于本次操作前的完整状态。
- 确认受控 API 可读取规则数量、顺序、状态、目标和 DSL 摘要，并支持验证回滚。
- 当前仍有 pending 恢复、持久只读故障或无法读取完整快照时，不得开始写入验收。

### 允许的备份与恢复

只允许通过模块 LKG 或受控 API 备份和恢复。禁止 shell 直接覆盖生产 JSON，禁止用复制、
重定向、`sed -i`、`tee`、临时 JSON 或 Provider 之外的路径替换规则和授权文件。

恢复步骤：

1. 停止产生新写入和通知。
2. 从恢复界面或受控 API 选择回滚到本次 LKG，不手工拼接 JSON。
3. 重新读取 store，核对规则数量、顺序、脱敏 ruleId、状态、目标、DSL 结构和授权。
4. 记录恢复后哈希，与前置哈希比较。
5. 只有读回和哈希均符合预期才标记恢复完成；否则进入失败退出流程。

## 失败退出

以下任一情况立即把当前场景标记为 FAIL 或 ABORTED：

- 预检失败、在线设备不是恰好一台、`ANDROID_SERIAL` 不匹配、SELinux 非 Enforcing、
  目标进程消失或 PID 为空。
- 任何工具要求直接写生产 JSON、绕过认证、强制保存或强制删除。
- 读取失败、哈希不可得、LKG 不可读回、回滚结果无法验证。
- 受控通知没有当前 run 标记、出现多个标记、current-run 出现多个 `forwardId`、重复字段、
  expected-run 字段不完整或 timestamp 越界、`forward_attempted` 冒充标记、重复 attempt
  标记或任何 `attempt` 大于 1。

安全退出顺序：

1. 停止新的 UI 写入、通知测试和并发操作。
2. 记录中止原因、证据时间和最后成功步骤。
3. 若已有写入，只通过 LKG 或受控 API 执行回滚。
4. 重新读取并比较恢复后哈希与受控 API 读回。
5. 回滚或验证失败时保持持久只读，不强制写入、不直接修复文件、不继续测试。
6. 归档脱敏证据并在 Issue #10 记录失败、中止或回滚结论；不关闭 Issue #10 或 Issue #11。

## 回滚判定

- `ROLLBACK_VERIFIED`：受控 API 读回与 LKG 一致，规则和授权前置哈希与恢复后哈希一致。
- `ROLLBACK_UNVERIFIED`：任一读回或哈希无法验证；保持持久只读并结束本次验收。
- `ABORTED`：尚未发生写入但前置条件破坏；不尝试“修复后继续”。
- `FAIL`：功能结果不符合预期；保留证据，由后续修复工单处理，不在验收会话临时改代码或 JSON。
