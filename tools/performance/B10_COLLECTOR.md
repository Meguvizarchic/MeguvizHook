# B10 单进程资源采集入口

`collect_b10.py` 使用独立的 `b10_resource_plan` 和 `b10_resource_capture`，只支持 API101／102、
A0／A2 的 B10 单次事务。它复用已有显式目标 transport、设备／安装包读取和日志保存助手，
不构造 B07 或 B08／B09 计划，也不调用它们的配对生产流程。资源原值交给
[B10 核验器](B10_RESOURCE_PROBE.md)；缺失和 unavailable 保持原样，不能补成 0 或升级成功。

## 只读本机输入的准备

```text
python -B tools/performance/collect_b10.py prepare \
  --root <工作区绝对路径> --audit <新B10签名制品审计.json> --audit-sha256 <独立保存的SHA256> \
  --adb <明确的ADB文件> --backend 102 --serial-sha256 <唯一目标设备摘要> \
  --control A2 --side A --framework-name <预期框架名> --framework-version <预期版本> \
  --reported-api 102 --output <工作区内全新计划目录>
```

prepare 不启动子进程或连接设备，不执行构建、签名或安装。所选 API 必须与 backend 相同。
计划固定 `workload=B10`、`warmup=0`、`samples=1`、`iterations=1`、
`measurement_mode=unobserved`、`measurement_kind=process_resource_snapshots`、用户 0 和 60 秒终态窗口。
不接受自由 workload、failure case、额外 shell 内容或可修改的采集参数。

审计的 `status` 必须是 `signed_artifacts_locally_verified`，顶层须显式包含：

```json
{
  "scenario_support": {
    "B10": {"controls": ["A0", "A2"], "failure_cases": []}
  },
  "fixed_parameters": {
    "warmup": 0, "samples": 1, "iterations": 1, "measurement_mode": "unobserved"
  },
  "measurement_kinds": {"B10": "process_resource_snapshots"}
}
```

支持列表必须无重复且只含 A0／A2，所选 control 必须在其中，failure_cases 必须是空列表。
其他场景可以并存；`prepared_inputs.feature_manifest` 必须与审计的上述三个声明一致。
参数严格区分整数、布尔值和字符串。第四十六轮旧审计不包含 B10 声明，不能直接用于本入口；
第四十七轮未签名构建与静态结果也不能替代新的签名审计。支持字段是审计生产者的静态声明，
不独立证明 APK 内代码、框架加载或真实运行行为。

审计必须精确包含一个选定宿主和一个所选代模块的行；它们的固定包名为 benchmarkhost 与
benchmark101／benchmark102，variant 为 deviceTest，signature_verified、scheme_v2_verified 为 true，
runtime_isolation_gate 为 passed，并使用同一证书声明。工具固定所选 APK 的原始字节数和 SHA，
不读取私钥或重新运行签名器。执行时读取安装包字节进行比对，签名资格仍来自审计声明。

以下路径及对应 SHA 全部固定：verification 中的 build_log、signature_checks、runtime_checks、
static_audit、static_evidence_manifest；若存在 api101_reference_supplement，也必须固定；
prepared_inputs 中的 artifact_record、workload_sources、feature_manifest。引用缺失、空 SHA、
路径越界、符号链接／重解析点或字节变化均拒绝。

工具源码固定完整的实际导入链：collect_b10、collect_b07、b07_adb_transport、
build_b07_pair_inputs、verify_b07_pair、verify_b07_install、verify_aa_pilot 和 verify_b10_resources。
不仅检查工作区副本，也核对当前进程实际加载文件的源码字节。ADB 独立固定绝对路径、字节数和 SHA。
Python 标准库、解释器、操作系统及设备端程序不在这些源码 PIN 的认证范围内。

计划目录必须全新且位于工作区内；先独占保存并回读 `plan.pending.json`，再在同目录通过硬链接
发布 `plan.json`。计划固定其发布路径；复制、移动或从 pending 执行均拒绝。

## 可选的共享配对声明

默认生成独立的 run、pair、session 和 host_clock_token 四个规范 UUID，单侧四者必须互异。
为了后续独立 A/A 核验，可同时提供 `--pair-id <PAIR_UUID>` 与
`--host-clock-token <CLOCK_UUID>`；只提供一个、非规范 UUID 或两者相同都会拒绝。
API 的 prepare 尾部对应 `pair_id=None, host_clock_token=None`。

共享值只绑定后续两个独立计划的 pair 和主机时钟声明；每次 prepare 仍生成新的 run/session。
调用方负责分配共同声明和执行顺序，本入口不创建配对批次，不认证两次采集实际来自同一主机时钟。
单侧成功不代表 A/A 通过，无法核对共同输入、时钟、执行先后或两侧证据时不能评价 A/A。

## 已保存的两侧 A/A 记录

`verify_b10_pair.py` 对两侧的原始计划、capture、result、owned-stop 和 threadtime 日志逐项
重验。命令的 `one`、`two` 只是输入位置；两个 capture 的 side 必须分别为 A 和 B，
`--pair-order` 必须使用采集前登记的真实先后顺序：

```text
python -B tools/performance/verify_b10_pair.py --pair-order AB \
  --plan-one <PLAN_A> --capture-one <CAPTURE_A> --result-one <RESULT_A> \
  --owned-stop-one <STOP_A> --log-one <RAW_A> \
  --plan-two <PLAN_B> --capture-two <CAPTURE_B> --result-two <RESULT_B> \
  --owned-stop-two <STOP_B> --log-two <RAW_B>
```

核验器重算每侧资源报告并检查计划 SHA、共同输入摘要、设备和制品声明、停止确认、进程身份、
主机和设备时钟窗口顺序。返回 `resource_pair_consistent` 只表示两份记录在声明范围内一致；
资源缺失或停止未确认不能通过。阶段差和 B−A 保留负值和零，但不代表 SDK 独占成本、退休驻留
字节、统计稳定性或性能资格。共享时钟 token、设备和进程身份仍是采集者声明。

## 已满足设备授权与协调条件后的执行

```text
python -B tools/performance/collect_b10.py execute <plan.json> \
  --plan-sha256 <准备后独立保存的SHA256> --serial <明确唯一设备> \
  --idle-confirmed --scope-confirmed
```

两个确认参数仅记录调用方对设备空闲、固定模块／宿主用户 0 作用范围的声明，不提供授权。
缺确认、serial 摘要不符或任何固定输入漂移，均不创建 transport。独占创建 `execution/` 后，
该计划即已消费；失败、中断、claim 写入失败和 transport 创建失败都不能自动重试。

执行仅通过明确 `-s` 目标读取设备状态、serial、boot、fingerprint、Android API／ABI、用户及
两包的单一安全 base.apk 路径、stat、SHA 和 PM UID。它不安装、更新或卸载应用，不改变框架
模块作用范围，不清应用数据、profile、缓存或共享日志。root 读取仅使用已有助手的固定脚本，
其中动态路径经过白名单检查；无自由 shell 入口。

启动前拒绝任何现存 benchmarkhost 进程。一次启动只使用固定 Intent 参数和计划中的 UUID。
新进程先核对 PM UID、`/proc` 的四个 UID、PID、start ticks 和包名，再读取同 PID 的完整 cmdline：
只允许固定包名字节后跟一个或多个 NUL，不允许任何非零后续参数。完整 cmdline 读取前后必须
通过同一份完整 PID／UID／start ticks 身份；有效的多 NUL 填充不视为额外参数。
完整命令行字节保留在 transport 原始输出，摘要只记录核对后的固定包名。

只有完整换行、匹配 run/pair/session/side、PID/TID、B10/control、0/1/1 和 measurement_kind 的
结构化 start，才能与上述进程共同建立 owned 身份；字符串包含 start/end 不算证据。
B10 不能混入 failure_case。终态窗口从启动命令正常返回后开始，最多 60 秒；各 transport 命令
另有自己的有界超时，不代表坏内核或整个采集流程都有统一硬实时保证。

日志流只监听固定标签和 threadtime 格式，不清日志。活动读取允许追加，但拒绝身份变化、缩短
或超过 16 MiB。只停止自己持有的 logcat 子进程；固定终态的长度／SHA 与稳定读取原件一致后，
才保存 `raw-threadtime.log`。启动结果未知、transport poisoned、身份变化或停止结果未知均不重放。
没有完整 owned 身份时不停止设备进程。

完整 end 后运行 `verify_b10_resources.verify`，保存原始 `single-verification.json`，并逐项比较
configured 框架元数据与准备期预期。只有 `resource_record_consistent` 可以继续发布成功 capture。
资源 unavailable、缺事件或失败均保留核验器的 incomplete；矛盾记录保留 invalid 和已有 partial
observations；这些情况的采集总结果都是 collection_failed，不生成成功 capture。

停止前再次复核计划、全部 PIN、设备／boot／用户、两包字节和严格完整进程身份；只向本次已
认领的固定宿主发送一次用户 0 force-stop。失败事务也可在所有守卫仍成立时收尾整个本次宿主。
不停止未知进程，不重试 close、不操作其他设备或未知线程。停止未确认时不会写成功 capture。
电池和 thermal 边界原件留存；超过 42°C 或相对初始升温至少 5°C 则失败，它们不是持续温控或
无降频证明。

## 证据结构与结果

execution 中保留 claim、transport 记录、设备前后边界、进程观察、owned-start、原日志、单次
核验与 result；确认停止后另有 owned-stop。早期失败可能只有部分文件，日志终态摘要不匹配时
transport 原件仍保留，不能把文件存在本身读作成功。

只有核验通过且停止确认后，才发布 `capture.json`：

- `schema_version=1`、`record_type=b10_resource_capture`、`status=collected_and_owned_stop_confirmed`；
- 计划 SHA、B10/control/backend、固定参数、measurement kind、run/pair/session/side、主机时钟声明和 serial 摘要；
- process 的 pid/uid/start_ticks/cmdline、controller_tid、device 及框架元数据、所选两包的路径／SHA／字节数／包名／证书声明；
- artifact_audit 完整 PIN、workload_sha256、feature_manifest_sha256、固定输入摘要、主机 launch/terminal 单调时钟；
- raw_log_sha256、raw_log_bytes、single-verification 原始字节的 verification_sha256；
- `performance_gate=not_evaluated`、`device_authentication=not_verified`。

`fixed_input_manifest_sha256` 是以下对象按 ensure_ascii、sort_keys、紧凑分隔符和 UTF-8 编码后的
SHA256：`{"pins": <按path排序的plan.pins>, "adb_sha256": <plan.adb.sha256>}`。
它不含 side/run/session/pair/clock，可以用于后续比较两个独立采集的共同固定输入。
verification_sha256 则使用原报告文件的字节，报告保存格式为 sorted keys、两空格缩进和末尾换行。

`result.json` 的 record_type 是 b10_resource_collection_result；成功值为 resource_record_consistent，
采集、核验、收尾或证据写入失败为 collection_failed。result 同时记录 verification、owned、
stop_attempted 和 stop_confirmed；stop_attempted 包括一次守卫收尾尝试，不保证 force-stop 已发出。
仅成功 result 与 owned-stop.confirmed=true、完整 capture 和 raw 同时成立才可用于后续配对。
CLI prepare／成功采集退出 0，其他情况退出 1；内层 incomplete 不会变成采集成功。

观测包含整个进程、探针、日志、分配、ART 和框架影响；阶段差值不是 SDK 独占增量、泄漏或
退休驻留字节。合法观测零值保留；null 不转为零，RETIRED_RESIDENT 不转换为字节数。
本入口不冻结阈值、不评价性能门槛、不建立原生 API 性能提升或发布资格。
Fake transport 测试不能替代真实设备、ART、框架加载或性能证据；原始输出也不等于已脱敏公开材料。
