# B07 A/A 配对离线核验

`verify_b07_pair.py` 把两个独立进程的 B07 原始日志与采集方声明配对。它分别复用
`verify_b07_install.py`，然后交叉检查制品、设备/框架声明、进程、用户和执行顺序。
不会连接设备、修改输入或评估性能门槛。B01–B06 的记录和核验器不变。

从原始日志及采集声明生成下述 manifest 和证据包，见 [B07 配对输入生产器](B07_COLLECTION_INPUTS.md)。

```text
python tools/performance/verify_b07_pair.py <第一份manifest.json> <第二份manifest.json> \
  --log-one <第一份manifest对应原始日志> --log-two <第二份manifest对应原始日志>
```

位置参数表示文件对应关系，允许先传 B 侧；真正的先后顺序由两侧相同的 `pair_order` 声明。
输出只写到标准输出。每份 manifest 上限 256 KiB，原始日志上限 16 MiB；清单和日志均按读入的
原始字节计算 SHA-256。保留原始文件，不能把输出 JSON 当作原始日志的替代品。

以下是一份 **合成 A 侧格式示例**，不是设备记录。B 侧需使用不同 run/session UUID 和后启动的
进程信息，同时保持配对、制品与设备等声明一致，并填写各自原始日志的真实 SHA-256。

```json
{
  "schema_version": 1,
  "record_type": "b07_install_probe_side",
  "pair_id": "4aa4d542-7ca6-4f00-9bed-b50e4199c5de",
  "run_id": "6abefc46-11fe-4f53-8aa6-fba235cb679c",
  "session_id": "132ef2bf-a65d-4ca5-885a-e118b487f003",
  "side": "A",
  "pair_order": "AB",
  "pid": 1234,
  "tid": 5678,
  "host_uid": 10123,
  "process_start_ticks": 100,
  "backend": "102",
  "control": "A2",
  "workload": "B07",
  "warmup": 0,
  "samples": 1,
  "iterations": 1,
  "measurement_kind": "install_wall_and_thread_cpu_ns",
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
  "terminal_monotonic_ns": 2000,
  "raw_log_sha256": "7777777777777777777777777777777777777777777777777777777777777777"
}
```

manifest 必须完整包含上述字段，顶层及 `artifacts`、`device` 不允许未知字段或重复键。
标识使用标准小写 UUID；五个 pair/run/session UUID 必须彼此不同。SHA-256 必须为 64 位小写
十六进制。所有计数字段为真正的整数，不接受布尔值；进程 PID/TID、UID、start ticks 为正数，
Android user 为非负数。时钟值为 0 到有符号 64 位最大值内的整数，结束须严格晚于启动。
`workload_sha256` 和 `feature_manifest_sha256` 应对应独立留存的负载源码/配置清单字节；工具
只比较摘要声明，不读取 APK、验证其签名或证明设备安装的正是这些字节。

两侧要求同一 pair、AB/BA、backend、A2/B07、固定 0/1/1、measurement kind、四份制品/负载摘要、
完整 `device`、`host_uid` 和 `host_clock_token`。`android_user` 和 `host_uid` 是显式声明，日志
没有 UID，因此只能交叉检查声明；工具不会由 UID 推算用户，也不能认证实际安装实例。

在同一 boot/device 声明下，进程 start ticks 必须按 AB/BA 严格增长；允许 PID 被系统重用，但
`(PID, start ticks)` 不能相同。主机单调时钟的第一侧结束须严格早于第二侧启动；同一个
`host_clock_token` 声明两侧使用相同主机单调时钟。两侧日志的设备 elapsed realtime 墙钟安装
区间也须按 AB/BA 严格先后。不会比较跨进程 CPU 绝对值，也不会把设备墙钟与主机时钟相减。
这些都是采集方记录，不能独立证明物理设备、boot 或主机时钟的真实性。

每侧原日志 SHA 须与对应 manifest 匹配，原始事件的 run/pair/session、side、PID/TID 与 B07/A2
须通过单侧核验。所选 `configured` 事件中的框架名称、版本和 API 还会与该侧 manifest 对照；
仅让两份 manifest 彼此相同不足以通过这项检查。

两输入分别以 `inputs.one` / `inputs.two` 留存原始日志 SHA 和可核验侧结果，避免错误 side 声明
覆盖另一份证据。即使一侧 manifest 缺失、非法或 JSON 不可解析，另一侧仍尽量核验；CLI 保留
已读入的 manifest SHA 与逐输入错误。身份和摘要匹配的单侧失败结果会保留已有时钟端点及其
presence，负值不会删掉、缺失值不会补造。摘要不匹配的日志不能作为该侧已绑定的时钟证据。

结果优先级为 `invalid` > `incomplete` > `unresolved` > `install_pair_consistent`。
明确矛盾、非法字段、错误顺序或单侧 invalid 为 invalid（退出 1）；缺少必需文件/字段/事件或
报告失败为 incomplete（退出 2）；任一单侧合法时钟区间为零则 unresolved（退出 2）；两个单侧
与声明全部一致才为 install_pair_consistent（退出 0）。所有结果的 `performance_gate` 均为
`not_evaluated`、`device_authentication` 均为 `not_verified`。

只有两侧都完整且无矛盾时才输出 `descriptive_comparison`：原始 A/B 耗时、B−A 和精确有理数
B/A；任一侧该时钟为零时比率为 null，零值仍保留并标 unresolved。这只是本次单对安装包络的描述，不是
噪声估计、置信区间、纯 SDK 开销、A/B 收益或正式 A/A 合格。原始失败、超时和零值记录必须保留。
