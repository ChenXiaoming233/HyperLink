# 验收操作手册

本手册只定义实机恢复后的受控操作。当前工单不执行其中的设备命令，也不发送通知。

## PF 只读预检

预检脚本使用 `adb get-state` fail-fast，只接受已连接的单台设备，并要求 shell UID 为 0。
它只打印设备与包元数据、进程、UID、SELinux、私有文件 `stat` 和 SHA-256，不读取或输出
规则内容，也不复制 APK。

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
- 两个目标进程 PID 与 `getenforce`，非 `Enforcing` 时直接失败。
- `notification-subscriptions.json` 与 `notification-forward-permissions.json` 的存在性、大小、
  修改时间、权限、属主及 SHA-256。

Android `dumpsys package` 的 `PackageSignatures` 内部摘要不是证书 SHA-256，不能拿它冒充
签名指纹。脚本要求执行人提供发布审批记录中的 `VOICEASSIST_SIGNER_SHA256` 和
`MILINK_SIGNER_SHA256`，并同时打印设备签名元数据供人工比对。缺少任一预登记值、取值不是
64 位十六进制、包未安装、进程未运行、私有文件不可访问或哈希命令不可用时，预检失败退出。
为遵守“不复制厂商 APK”，脚本不从设备拉取 APK；若无法取得发布审批记录，相关验收项为
`BLOCKED`，不得用未知摘要继续。

## 受控通知测试

### 日志契约

真实接线必须只在一次实际 A2A 投递开始时写一条不含敏感内容的标记：

```text
HyperLinkAcceptance event=forward_attempt forwardId=<不透明ID> attempt=<正整数>
```

- 载入规则、渲染 UI、预览、排队和普通状态变化不得写该标记。
- `forwardId` 代表一次用户可见的逻辑投递；同一逻辑投递只允许一个值。
- `attempt` 从 1 开始，只有实际发生额外投递时才递增，不得用 UI 重试次数替代。
- 标记不得包含通知正文、应用名、包名、账号、设备标识或 `ruleId`。
- 如果真实接线尚未实现该标记，`ACC-14` 标记 `BLOCKED`，不得凭截图猜测通过。

### 日志过滤

执行人手动生成一条无敏感内容的合成通知，同时只过滤标记行：

```bash
adb logcat -v threadtime | grep --line-buffered 'HyperLinkAcceptance event=forward_attempt'
```

测试结束后将同一过滤结果交给仓库校验器。校验器只从标准输入读取，不连接设备：

```bash
adb logcat -d -v threadtime | bash scripts/acceptance/verify-forward-attempt.sh
```

若需要确认 `forwardId` 与测试记录一致，使用环境变量传入，不在终端输出原值：

```bash
EXPECTED_FORWARD_ID='<测试记录中的不透明ID占位>' \
bash scripts/acceptance/verify-forward-attempt.sh < filtered-forward.log
```

校验器只在标记数为 1、唯一 `forwardId` 数为 1、所有 `attempt=1` 时输出 PASS，并只打印
`forwardId` 的 SHA-256 前缀。归档完整原始日志前必须再做同一过滤与脱敏，禁止归档通知正文。

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

- 预检失败、设备不止一台、SELinux 非 Enforcing、目标进程消失。
- 任何工具要求直接写生产 JSON、绕过认证、强制保存或强制删除。
- 读取失败、哈希不可得、LKG 不可读回、回滚结果无法验证。
- 受控通知出现多个 `forwardId`、多个标记或任何 `attempt` 大于 1。

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
