# B08／B09 单场景采集入口

`collect_b08_b09.py` 使用独立的单场景计划，复用 B07 的显式目标 transport 和只读设备、APK、进程
守卫；不会构造 B07 计划，也不调用 B07 配对输入生产器。`prepare` 只读本机文件，`execute` 才可能
发设备命令。两者不安装、更新、卸载应用，不清数据、profile 或共享日志，不改变管理器作用范围。

## 离线准备

```text
python tools/performance/collect_b08_b09.py prepare \
  --root <工作区绝对路径> --audit <已签名制品摘要.json> --audit-sha256 <独立留存的SHA256> \
  --adb <明确的ADB文件> --backend 102 --serial-sha256 <唯一目标摘要> \
  --workload B08 --control A2 --side A \
  --framework-name <预期名称> --framework-version <预期版本> --reported-api 102 \
  --output <工作区内全新计划目录>
```

B09 改为 `--workload B09 --control A0` 或 `A2`，另明确给出
`--failure-case before`、`after` 或 `original`。B08 只接受 A2，不允许 failure case。
两类场景都固定无计数模式标识、零预热、单样本／单事务，三个运行 UUID 和主机时钟 UUID 均独立。
侧标识只是与原日志关联，不意味着生成了 A/A 配对或统计结果。

制品摘要使用 `status=signed_artifacts_locally_verified`，固定包名为现有 benchmarkhost 与所选的一代
benchmark101／benchmark102；所选行必须报告 `deviceTest`、签名、v2 和 runtime isolation gate 通过。
两个 APK 的字节数、SHA 与证书声明必须一致于摘要，各验证引用及 prepared-input 引用也逐项固定。
摘要、证书和源码声明不是设备安装或实际加载认证。

旧 round40 B07 制品不能仅靠同包名进入本入口；新摘要须声明以下静态支持集合，所选控制和 case
必须存在于相应的无重复列表中：

```json
{
  "scenario_support": {
    "B08": {"controls": ["A2"], "failure_cases": []},
    "B09": {"controls": ["A0", "A2"], "failure_cases": ["before", "after", "original"]}
  }
}
```

`prepared_inputs.feature_manifest` 的原始字节也被固定，内容须与摘要的 `scenario_support` 一致，且含：

```json
{
  "fixed_parameters": {"warmup": 0, "samples": 1, "iterations": 1, "measurement_mode": "unobserved"},
  "measurement_kinds": {
    "B08": "close_with_inflight_wall_ns",
    "B09": "failure_call_wall_and_thread_cpu_ns"
  }
}
```

三个计数必须是真正整数，不接受布尔值。完整 `verification` 引用为 build_log、signature_checks、
runtime_checks、static_audit、static_evidence_manifest 及各自 SHA；若有 api101_reference_supplement，
也必须固定。prepared_inputs 的 artifact_record、workload_sources、feature_manifest 仍使用既有三个
路径／摘要字段。工具不读取私钥，也不重新执行签名器。

计划固定发布路径，独占写入 pending 并核对后以同目录硬链接发布 `plan.json`。移动、复制到其他
位置或从 pending 执行会拒绝。计划还固定 ADB 以及实际加载的 collector、共享 B07 助手、transport、
builder 和两类 verifier 源码字节。PIN 不符时不得靠修改某个副本继续运行；新计划须重新准备。
PID、TID、UID、boot 与真实启动／结束时间留到采集时观察，不用零值预测。

## 满足授权范围后的单次执行

```text
python tools/performance/collect_b08_b09.py execute <plan.json> \
  --plan-sha256 <准备后独立保存的SHA256> --serial <明确唯一目标> \
  --idle-confirmed --scope-confirmed
```

两个参数只记录调用方对空闲和固定模块／宿主用户 0 作用范围的声明，不是授权来源。当前仅支持
用户 0。执行前复核所有固定输入及真实已加载工具；缺确认、设备摘要不符或任一 PIN 漂移，不创建
transport、不发设备命令。独占创建 `execution/` 后，无论失败或中断都不允许同计划再次执行。

安装检查只读取 PM 单一安全 base.apk 路径、stat、SHA 和 UID；不匹配就失败，不自动更新应用。
设备 serial、在线状态、boot、fingerprint、API、ABI 和用户均留存边界记录；这些是观察，不是持续锁。
受限 APK 和 `/proc` 读取使用共享助手的固定 root 读命令，不接受自由 shell 内容。

启动前拒绝任何现存 benchmarkhost 进程。一次启动包含完整 UUID、side、B08／B09、A0／A2 和固定
参数；B09 的 Intent 是 `workload=B09` 加独立小写 `failure_case`，内部 bridge 编码由宿主负责。
新进程必须同时满足精确 cmdline、PM UID 和 `/proc` 四个 UID、前后相同的 PID／start ticks，
以及完整匹配的结构化 start 事件。B08 记录不能混入 B09 专属 failure_case。

日志流仅捕获固定 `MeguvizHookBench` threadtime 记录，不清日志；活动读取允许追加，拒绝文件身份
变化或超限。B08 终态等待上限 90 秒，B09 为 60 秒，均从启动命令返回后开始观察；transport 命令
另有自己的超时。这不代表整个系统或坏内核下的所有步骤都有一个统一硬实时上限。
仅解析完整换行的事件；字符串包含 end 不算终态。

关闭自己持有的 logcat Popen 后，原件稳定读取且与 transport 终态长度／SHA 一致，才保存为
`raw-threadtime.log`。B08 交给 [关闭核验器](B08_CLOSE_PROBE.md)，B09 交给
[异常核验器](B09_FAILURE_PROBE.md)。所选 configured 中的框架字段还须与准备期预期逐项对照。

一致或合法零时钟区间才生成 `capture.json`；失败时保留原日志、单侧 partial 和错误说明。
捕获报告不是 B07 的 side manifest，也不能交给 B07 pair builder。

停止前再次核对计划、设备／boot／用户、两包字节和完整进程身份，只对本次已认领的固定宿主执行
一次 force-stop。若 B08 报告 helper 仍活但完整 start 已证明本次进程归属，失败收尾仍可在这些守卫
通过后停止整个本次宿主；不会调用 Thread.stop、重试 close 或停止未知线程。没有完整归属、启动或
停止超时、身份变化或 transport poisoned 时，不会猜测重放或杀未知进程。

电池／thermal 边界原始输出保留；超过 42°C 或相对初始边界升温至少 5°C 会停止。它不是连续
采样、无降频证明或 CPU 频率认证。stdout 中可能包含设备标识，私有原件须按原样保存而不能宣称已脱敏。

## 结果与限制

`execution/result.json` 的 `collection_failed` 覆盖采集或收尾失败，即使其中一个日志核验器曾报告
一致，也不能改读为成功采集。合法零区间维持 unresolved。B08 一致结果为 close_record_consistent，
B09 为 failure_record_consistent；CLI 的 prepare／一致退出 0，unresolved／incomplete 退出 2，其他失败退出 1。
写入或中断可能留下部分目录；文件存在不代表成功，不能删除、覆盖或自动重放。

每次进程只执行一个场景。计数、线程、异常对象和框架观察是生产者报告；工具不能认证管理器加载、
ART、物理释放或 OS 实际路由。它不冻结性能阈值，不构成统计稳定性、置信区间或原生 API 性能收益。
真实设备执行仍须满足用户的协调、更新和测试范围；Fake transport 回归不能替代手机证据。
