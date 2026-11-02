# 插桩回调体 CPU 区间诊断

`verify_cpu_body_probe.py` 核验一份选定的 `MeguvizHookCpuBody` 原始 threadtime 日志。它只
检查事件和声明的逐调用记录，不认证设备、框架、进程或日志来源，也不评估性能门槛。原始日志可能
包含其他进程信息，应保留在私有证据目录，另外核对实际设备、框架、安装制品和进程身份。

```text
python tools/performance/verify_cpu_body_probe.py <原始-threadtime-日志> \
  --run-id <标准小写UUID> --pid <进程PID> --tid <工作线程TID> \
  --backend <101或102> --control <A0或A2> --workload <B01或B02>
```

事件必须来自同一 PID/TID 与 run；前缀和 JSON 均要一致。同 PID 的其他 run 会被拒绝，避免将
PID 复用或混杂日志误记为一次完整执行。目标标签行必须是完整 UTF-8 和 threadtime JSON，重复
字段、可辨识的损坏标签及编码错误均拒绝；无关日志行的非法字节不会改变原始日志或被当作目标事件。

协议为 `start`、每个 ordinal 一条 `call`、`end`。`warmup` 为 0–100，`samples` 为 1–200；
ordinal 从 0 连续递增，前 `warmup` 项为预热。成功的 B01 逐项要求 before/original/after 计数均为
1；B02 要求 before/original 为 1、after 为 0 且 after 时间为 null。成功的 CPU 起止读数必须为
Java `long` 范围内的非负整数，同阶段、跨阶段及跨调用不倒退；相等合法。结束事件必须有一致的
总数与已确认的 cleanup。缺项、乱序、重复、错误线程、不可用时钟、倒退或失败不得成为成功记录。

失败 `call` 可保留尚未取得时间的 null 和实际读取的负值；见到失败后即使没有 `end` 也报告失败。
`end` 的失败可能表示后续 cleanup 等另一原因，不要求与此前 call 原因相同；不得据此恢复或重放调用。
输出保留逐项原始时间、计数、状态、日志行号及整份原始日志的 SHA-256，不改写输入文件。

返回状态及进程退出码：

- `observed` / 0：完整结束，且每个必需的采样阶段至少观察到一个正的相邻 CPU 间隔。
- `unresolved` / 2：完整结束，但某个必需采样阶段的间隔全部为零；不能称作零开销或零精度。
- `incomplete` / 2：没有所选运行，或没有完整成功结束，且未观察到显式诊断失败。
- `diagnostic_failure` / 1：应用报告的 call/end 失败；即使结束事件缺失也不会降为 incomplete。
- `invalid` / 1：字段、身份、顺序、状态与原始值矛盾，或可辨识的目标行损坏。

这些值是**插桩后的回调方法体 CPU 区间**：A0 分别在 `proceed()` 前后计时；A2 分别在实际
before/after 回调方法体计时。它们不包含 `proceed()`、原方法、框架包装或 SDK dispatcher，也不
代表纯 callback CPU 或整个 SDK 开销。B02 两种控制分别执行框架参数读取和 SDK 参数读取，不声称
生成相同机器码。读钟与记录本身有扰动，不能直接扣除相邻读钟诊断或将 B01 空工作视作通用校准。

该工具不输出正式 `callback_cpu_ns`、逐调用 p95/p99 或阈值，不把自报告或单次测试当作发布资格。
各对照的时间边界、探针影响、设备条件和统计方案仍需独立设计与验证。

## 两侧 A/A 记录的结构一致性

`verify_cpu_body_pair.py` 在两个**独立进程**的原始日志上分别运行上述单侧核验，再核对采集者
提供的两份外部 manifest。输入顺序可以是 A/B 或 B/A，日志参数必须与对应 manifest 同顺序：

```text
python tools/performance/verify_cpu_body_pair.py <manifest-one.json> <manifest-two.json> \
  --log-one <raw-one.log> --log-two <raw-two.log>
```

每份 manifest 必须恰有以下字段（示意值须由采集者依据实际记录提供）：

```json
{
  "schema_version": 1,
  "record_type": "cpu_body_probe_side",
  "pair_id": "<标准小写UUID>",
  "side": "A",
  "pair_order": "AB",
  "run_id": "<与pair_id及另一侧不同的标准小写UUID>",
  "pid": 1234,
  "tid": 1235,
  "backend": "102",
  "control": "A0",
  "workload": "B01",
  "warmup": 20,
  "samples": 100,
  "clock": "thread_cpu_ns",
  "measurement_kind": "instrumented_callback_body_cpu_interval",
  "artifacts": {"host_apk_sha256": "<64位小写SHA-256>", "module_apk_sha256": "<64位小写SHA-256>"},
  "device": {"device_id_sha256": "<64位小写SHA-256>", "build_fingerprint_sha256": "<64位小写SHA-256>",
             "boot_id": "<标准小写UUID>", "framework_name": "<实际框架名称>", "framework_version": "<实际框架版本>"},
  "process_start_ticks": 123456,
  "host_clock_token": "<同一采集端单调时钟域的标准小写UUID>",
  "launch_monotonic_ns": 1000000,
  "terminal_monotonic_ns": 2000000,
  "raw_log_sha256": "<64位小写SHA-256>"
}
```

两侧须分别为 A、B，且 pair ID、顺序、backend、control、workload、预热/样本数、clock、
measurement kind、制品、设备和采集端时钟域一致；run ID 不同、进程 `(pid,start_ticks)` 不同。
顺序要求前一侧的启动 tick 与采集端终止时间分别早于后一侧的启动 tick 与启动时间。工具检查
这些声明与两份原始日志、每侧显式参数及哈希的**内部一致性**，并不独立认证设备、进程、来源或
采集端时钟；相同 host_clock_token 也不证明采集期间采集端没有重启。

仅两侧均为 `observed` 才返回 `body_cpu_pair_consistent` / 0；任一 `incomplete` 或 `unresolved`
分别返回对应状态 / 2，诊断失败或无效输入返回失败 / 1。所有结果的 `performance_gate` 都是
`not_evaluated`；不把它们混为 A0/A2 对比、不计算正式 callback CPU 或 task p95/p99，也不
将一次配对解释为稳定性证明。原始日志、采集环境、真实 APK 和进程证据应另行保留。

## 相邻采样之间的线程 CPU 间隔

`analyze_cpu_body_gaps.py` 先对两个 manifest 和原始日志执行上述完整配对核验，仅在两侧均完整
且为 `observed` 时计算每侧采样区间之间的间隔；输入及原始字节不被修改：

```text
python tools/performance/analyze_cpu_body_gaps.py <manifest-one.json> <manifest-two.json> \
  --log-one <raw-one.log> --log-two <raw-two.log>
```

仅使用样本内部相邻序号：B01 是下一次 before 起点减本次 after 终点，B02 是下一次 before 起点
减本次 before 终点。预热内部和最后一次预热到第一个样本的边界均不纳入。每项保留两个 ordinal、
原始端点和非负差值；min/max 为整数纳秒，中位数用十进制字符串 `numerator` 与整数
`denominator` 表达，避免大数在部分 JSON 消费者中丢失精度。
样本少于两个时返回 `unresolved` / 2；源配对未解析、未完成或失败也不会提升成成功。

这些间隔是同一线程 CPU 时钟读数之间的时间，包含计时端点之外的账本、循环、框架和探针等工作。
在验证通过的正常账本中，B01 的受控 `proceed` 与原方法发生在本次 after 终点之前，不在该间隔内；
B02 的受控 `proceed` 与原方法发生在本次 before 终点之后、下一次 before 起点之前，位于该间隔内。
其他未测工作仍可能发生；上述区分不能排除它们。间隔不是墙钟时间、单独的 dispatcher 成本或原因
诊断，不能与空读钟间隔机械相减，也不评估性能门槛。
