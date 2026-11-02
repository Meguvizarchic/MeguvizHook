# 冻结配置声明与证据引用检查

`check_frozen_profile.py` 只读检查当前 schema 1、`MeguvizHook`、
`meguvizhook-profile-draft-v1` 的结构与直接证据引用。它不生成或修改 profile，不选择阈值，
不从提案复制数字，不冻结配置，不运行测试、构建或 ADB，也不授予 A/B 执行权限。

```text
python -B tools/performance/check_frozen_profile.py work/performance/performance_profile.json \
  --root <工作区绝对路径> --profile-sha256 <独立留存的原始文件SHA256>
```

只有外部 SHA 与实际 profile 原字节一致后才解析。读取限长、拒绝重复键和 NaN/Infinity，
路径及全部已有祖先不得为符号链接或重解析点。证据路径必须位于指定工作区，不接受 `..`。
CLI 输出 JSON，不写文件。退出码：结构和直接引用一致为 0，draft 阻塞为 2，无效输入为 1。
退出 0 不代表 GATE-002、噪声、基线真实性、发布资格或正式 A/B 通过。

## API 与结果

`validate_profile(profile_dict) -> dict` 不进行文件 I/O，也不修改输入。当前
`DRAFT_NOT_FROZEN` 始终返回 `blocked_not_frozen`，并列出缺口；仅把 status 改为 FROZEN
会因其他字段缺失得到 `invalid`。结构完整返回 `frozen_declaration_structurally_valid`，
此时 `evidence_links_checked=false`，不能据此跳过文件检查。

`check_profile(root, path, expected_sha256) -> dict` 核对实际 profile 原字节和外部 SHA，
调用结构检查，再读取支持的 baseline 文件及其直接链接。文件或链接不匹配返回 `invalid`；
只有全部关联检查完成才返回上述结构有效状态及 `evidence_links_checked=true`。

结果始终带 `diagnostics`、`performance_gate=not_evaluated`、`ab_authorized=false`、
`release_qualified=false`。`freeze.owner` 和 `FROZEN` 仅是生产者声明，不认证用户身份、
用户批准或声明生成时间，也不表示原始测量先于真实冻结。

## 必填结构

- `freeze.owner` 为非空字符串，timestamp 为含显式时区的 ISO 日期时间；baseline/candidate
  artifact、feature、workload 四项 SHA 必须明确填写为小写十六进制 SHA-256。
- `device_cells` 非空，可使用唯一的非空 cell 标签或对象；本工具不判断设备矩阵覆盖是否充分。
- 两个统计次数必须为正整数；预热、随机化和分析方法必须明确声明为非空字符串或对象；
  confidence 必须为有限数，严格处于 `(0,1)`。
- 五项既有指标必须各出现一次、单位一致，两个 fraction 明确为 `[0,1]` 内有限数。
  工具检查表示形式，不判断这些用户取舍是否适当，也不把 loop 包络填成 callback CPU。
- 五项资源预算必须为非负整数，五项硬约束必须保持整数 0。所有数值字段拒绝 boolean。

完整 profile 的其他现有顶层字段继续保留；未知顶层字段不作为未经版本化的新冻结合同接受。
策略对象的具体统计方法由独立方案定义，本工具不自行补入置信区间算法、样本数或噪声门槛。

## Baseline 文件合同与界限

`freeze.baseline_aa_evidence` 使用完整文件 pin：

```json
{"path":"evidence/baseline-aa.json","bytes":123,"sha256":"<64位小写hex>"}
```

示例中的路径、长度和 SHA 是占位符；文件及其引用由调用方自备，不随仓库发布。当前仅支持既有
schema 1、`status=batch_records_collected_reported_only`、含 `collection_evidence` 和 `pairs`
的同控制组 A/A 摘要。检查声明计数关系、每对 A/B 侧和完整原件引用，并逐项核对摘要中直接
文件 pin 的实际长度与 SHA。摘要本身最多 2 MiB，单个链接最多 64 MiB，引用最多 512 个，
不同直接链接总量最多 256 MiB；冲突 pin、缺文件、路径越界或哈希漂移均拒绝。

这项检查不递归重跑链接中的工具，不执行原始 pair verifier，不认证链接内容是真实测量，
不验证四项冻结 SHA 对应哪种生产制品，也不证明每次 Hook 命中、语义等价、A/A 噪声充分、
统计功效、时钟身份或阈值合适。`reported_only`、`measurement_hook_per_call=not_verified`
和 `noise_sufficiency=not_evaluated` 原样保留。完整签名制品、设备、workload、功能和源 A/A
关联以及正式比较范围，仍须由独立预登记合同与核验器约束。

当前 profile 为 draft；即使某项 loop 阈值提案已获认可为后续草案，也不补齐其他指标、资源
预算或冻结声明。测试里的完整 FROZEN 和数值均为合成夹具，不是用户政策或生产配置。
