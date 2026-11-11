# HyperLink 开发与测试

当前阶段包含可离线验证的模块骨架、领域接口、RPC 纯层、fake 只读面板和 fake 安全写入
事务层。它不包含真实 LSPosed Hook、VoiceAssist Provider 接线或设备级兼容性验收。

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

## fake 安全规则写入事务

`domain.write` 提供目标选择、首次目标授权确认和离线警告流程，并通过
`SafeNotificationRuleWriter` 创建“所有符合条件的通知”独占规则。规则按来源设备和目标
设备 upsert；同一组合重复创建时复用原 `ruleId`，不会生成重复规则。创建结果会保留系统
通知使用权、目标授权和在线/可达能力相关的限制说明。

能力探测模型分别检查类、构造器、方法、字段、状态值和 DSL 结构。版本不在已验收集合中
但所需能力全部通过时仍允许写入，同时返回“兼容性未经完整验收”；能力缺失时拒绝写入。
兼容性提示同时参考能力探测结果和快照的 `compatibilityUnverified`，任一为未验收都会
显示“兼容性未经完整验收”。写入前还会逐条检查完整规则快照中的状态值、DSL 根结构、
规则分支、条件字段、操作符和值结构；只要存在未知字段或不可表达 DSL，整次写入即进入
只读，不会执行部分写入；超出嵌套深度上限的 DSL 同样按不可表达处理。gateway 的汇总
布尔只作为额外保护，不替代实际结构校验。

每次写入前先保存完整 LKG，包括原始规则顺序、`ruleId`、筛选 DSL、状态、目标授权和
系统通知使用权。写入通过进程内共享锁串行执行，同一 fake gateway 的多个 writer 实例
也不会互相覆盖。写入后同时校验 fake store 回读与序列化结果中的目标规则、未修改规则、
数量、`ruleId` 唯一性、顺序和 DSL，并比较每个目标的授权状态及系统通知使用权。写入期间
权限发生变化时视为校验失败。写入、回读、权限或安全结构校验失败时，通过 LKG 恢复规则
与权限并再次读取验证；无法验证回滚时，将只读故障态写入 fake store。同一底层
`FakeRuleStore` 上新建 gateway 或 writer 仍会读取该故障态并 fail-closed；显式 restore
不会自动清除故障态，并在故障态下同样失败。fake 写入会在模拟 I/O 延迟后、store
monitor 内基于最新状态原子提交，避免覆盖并发产生的权限变化或持久故障。

该事务层只使用 `FakeRuleStoreGateway`、`FakeLastKnownGoodStore` 和 Demo/demo 合成
标识，不访问真实 VoiceAssist 规则文件、权限 store、Provider 或设备，不提供真实写入
降级、任意反射入口或生产环境文件写入。`FakeRuleStore` 只模拟持久 store 的共享故障态，
不模拟文件格式、进程重启后的磁盘持久化或真实原子性；真实持久化仍属于 #5/#9。

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
安全写入测试额外覆盖首次授权确认、离线创建、source+target upsert、未知字段与不可表达
DSL 禁写、能力缺失、快照读取失败、LKG 保存失败、回读失败、写入失败、校验失败回滚、
无法验证回滚的持久只读故障态、目标授权与系统通知使用权回读变化、权限恢复验证、实际
DSL/状态结构校验、兼容性标记合并、LKG 保存异常、系统通知使用权安全文案、并发栅栏，
以及并发写入不互相覆盖。

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
- `RuleStoreGateway`、`LastKnownGoodStore` 目前只有 fake 实现；尚未接入 VoiceAssist
  同进程 store 或应用私有目录的持久化 LKG。`FakeRuleStore` 的共享故障态只在 JVM 内
  模拟持久化，重启恢复仍需 #5/#9 的真实持久化设计。
- fake 状态、单元测试和编译成功均不代表真实 MiLink/VoiceAssist 集成完成。
