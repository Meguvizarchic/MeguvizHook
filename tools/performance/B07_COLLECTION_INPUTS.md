# B07 配对输入生产器

`build_b07_pair_inputs.py` 从两份原始日志和明确的采集声明生成独立证据目录。它不连接设备、不启动
应用、不安装 APK，也不从日志猜测设备、UID、boot、进程启动 ticks 或主机执行顺序。已有历史采集器
保持原样；实际采集仍须另行完成并保存外部身份记录。

自动产生这些输入的入口见 [B07 采集器](B07_COLLECTOR.md)；其设备操作需先满足对应的空闲、作用范围及制品条件。

```text
python tools/performance/build_b07_pair_inputs.py capture-one.json capture-two.json \
  --log-one raw-one.log --log-two raw-two.log \
  --output <全新本地输出目录>
```

输出目录的父目录须已存在，目标本身必须不存在；不能与任一输入路径相同。位置参数只表示文件对应，
允许先传 B 侧，先后顺序仍由声明及原日志交叉核对。每份声明最多 256 KiB，日志最多 16 MiB，输入
须为普通文件；读取期间观测到的变化会拒绝。工具只复制四个显式文件，不递归复制其父目录。

每份 capture 使用 `schema_version=1`、`record_type=b07_install_capture_side`，完整包含：

```json
{
  "schema_version": 1,
  "record_type": "b07_install_capture_side",
  "pair_id": "4aa4d542-7ca6-4f00-9bed-b50e4199c5de",
  "run_id": "6abefc46-11fe-4f53-8aa6-fba235cb679c",
  "session_id": "132ef2bf-a65d-4ca5-885a-e118b487f003",
  "side": "A",
  "pair_order": "AB",
  "backend": "102",
  "pid": 1234,
  "tid": 5678,
  "host_uid": 10123,
  "process_start_ticks": 100,
  "artifacts": {
    "host_apk_sha256": "1111111111111111111111111111111111111111111111111111111111111111",
    "module_apk_sha256": "2222222222222222222222222222222222222222222222222222222222222222",
    "workload_sha256": "3333333333333333333333333333333333333333333333333333333333333333",
    "feature_manifest_sha256": "4444444444444444444444444444444444444444444444444444444444444444"
  },
  "device": {
    "device_id_sha256": "5555555555555555555555555555555555555555555555555555555555555555",
    "build_fingerprint_sha256": "6666666666666666666666666666666666666666666666666666666666666666",
    "boot_id": "721f4de7-706b-42b3-bb9e-91f23a8915a9",
    "android_api": 33,
    "abi": "arm64-v8a",
    "android_user": 0,
    "framework_name": "test",
    "framework_version": "1",
    "reported_api": 102
  },
  "host_clock_token": "9a6bd7a5-c99d-4e27-8594-c0486158f92b",
  "launch_monotonic_ns": 1000,
  "terminal_monotonic_ns": 2000
}
```

这是合成格式示例，不能作为设备证据。B 侧另用独立 run/session 和实际观察到的进程身份。声明的
字段、类型、独立 UUID、同制品/设备/用户以及 AB/BA 规则沿用 [B07 配对协议](B07_INSTALL_PAIR.md)。
未知字段与重复 JSON 键会拒绝；不能通过 capture 覆盖 B07/A2、无预热、单样本或单次迭代。

工具只补充协议常量 `B07/A2/0/1/1`、计时类型和日志原始字节的 SHA-256。生成的候选 manifest 会
直接送入已有 `verify_b07_pair`，该工具分别调用单侧日志核验并交叉核对实际可观察的 run/pair/session、
PID/TID、配置与框架字段，以及原日志设备墙钟顺序。常量填充本身不证明实际执行了该配置。
PID、UID、制品和主机时钟等仍为采集方声明，不能独立认证安装、设备或时间来源。

## 输出与失败保留

所有文件在新目录内独占创建：可读取的 capture 原始字节保存为 `capture-one.json` / `capture-two.json`，
日志保存为 `raw-one.log` / `raw-two.log`。`verification.json` 保留配对结果、输入 SHA 和可用的单侧
时钟端点。即使一份声明无效或缺失，另一侧仍会核验；未读取或超过上限的输入不会生成伪造副本或完整哈希。

只有 `install_pair_consistent` 或合法 `unresolved` 才写 `manifest-one.json` / `manifest-two.json`。
`invalid` 与 `incomplete` 只保存证据和失败报告，不输出可直接消费的两份 manifest。
零区间不被删除，结果为 `unresolved`，该时钟倍率保持 null。

每个输出先序列化，以独占方式写入并检查写入长度，flush、关闭后读回并核对字节和 SHA。
最后写入并验证 `result.pending.json`，再以同目录独占硬链接发布 `result.json`；不覆盖已有目标，
文件系统不支持硬链接时明确失败，没有覆盖式回退。pending 文件保留，不做自动删除。

**只有 `result.json` 是证据包完成标记。** `result.pending.json` 或单独的 `verification.json`、
manifest 即使看似完整，也不能证明整个包写入成功。消费时还应检查完成标记中的文件清单及哈希。
`bundle_complete=true` 只代表证据包完整写成，包内 `result=invalid/incomplete` 仍是失败数据，
不代表实验通过。写入、关闭、读回或发布异常会令 CLI 非零，并保留 partial 目录、已有输入哈希与
可用单侧报告；它们不会被自动删除或重试。

CLI 退出码：一致为 0；合法零值 `unresolved` 或缺失/报告失败 `incomplete` 为 2；矛盾、非法输入、
既有输出路径或 IO/发布失败为 1。所有结果的 `performance_gate=not_evaluated`，
`device_authentication=not_verified`。检查不是持续锁，也不能防止恶意并发修改输入或输出，
不保证系统崩溃或断电后的目录项持久性。
没有统计稳定性、正式 A/A 合格、置信区间或性能收益结论。
