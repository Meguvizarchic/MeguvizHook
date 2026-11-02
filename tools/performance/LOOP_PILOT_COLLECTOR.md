# B01–B06 单侧循环采集

`collect_loop_pilot.py` 自动采集现有宿主的一次循环 pilot，支持 B01–B06、101／102、A0／A2 和
observed／unobserved。它不更改 Android 计时实现，不进行未经冻结的正式 A/B，也不评价性能门槛。
A0 是当前框架直接调用路径，A2 是相同受控语义的 SDK 路径；单侧采集完成不是相对原生 API 的收益。

## 离线准备

```text
python -B tools/performance/collect_loop_pilot.py prepare \
  --root <工作区> --audit <显式支持loop的新签名审计.json> --audit-sha256 <独立留存SHA256> \
  --adb <固定ADB文件> --backend 102 --serial-sha256 <明确设备摘要> \
  --workload B01 --control A0 --measurement-mode unobserved \
  --warmup <预热调用数> --samples <样本数> --iterations <每样本调用数> \
  --side A --pair-order AB --pair-id <共同PAIR_UUID> --host-clock-token <共同CLOCK_UUID> \
  --framework-name <预期框架> --framework-version <预期版本> --reported-api 102 \
  --output <工作区内全新目录>
```

prepare 只读取本机文件，不连接设备、构建、签名或安装。warmup 为 0..1000000，samples 为
1..200，iterations 为 1..1000000；三项只能是真正整数。循环内实际目标调用总量
`(warmup + samples * iterations) * multiplier` 不得超过 10000000，B06 multiplier=4，其余=1。
这不包括独立 preflight 和它的关闭后检查，也不保证请求能在终态窗口内完成。

pair-id 与 host-clock-token 必须同时提供或同时省略；省略时各自随机生成。单计划的 run、session、
pair、clock 四 UUID 互异，每次生成新的 run/session。side 是独立身份标识；pair-order 的 AB／BA
必须预登记为实际执行顺序。后续 A/A 两侧必须是同一个 workload、control、mode、backend、参数、
输入和设备，不能把 A0 与 A2 当成同控制 A/A。共享 clock 是采集者声明，不独立认证主机时钟。

新签名审计要求 `status=signed_artifacts_locally_verified`。保留既有 host／所选模块的 deviceTest、
签名／v2、runtime isolation gate、同证书及 APK 原字节 SHA／长度要求，verification 和 prepared_inputs
引用逐项固定。另须有如下三个顶层声明，feature_manifest 中三项必须完全一致：

```json
{
  "loop_support": {
    "workloads": ["B01", "B02", "B03", "B04", "B05", "B06"],
    "controls": ["A0", "A2"],
    "measurement_modes": ["observed", "unobserved"],
    "backends": ["101", "102"]
  },
  "loop_limits": {
    "warmup_max": 1000000, "samples_max": 200, "iterations_max": 1000000,
    "total_target_calls_max": 10000000,
    "workload_call_multiplier": {"B01": 1, "B02": 1, "B03": 1, "B04": 1, "B05": 1, "B06": 4}
  },
  "loop_measurement_kinds": {
    "unobserved": "thread_cpu_envelope_ns", "observed": "thread_cpu_envelope_observed_ns"
  }
}
```

支持列表允许合法的非空子集，不允许重复、字符串冒充列表或未知值；所选场景必须被声明。
limits／kinds 是固定宿主合同，不能缩改或用布尔值冒充整数。旧 B10-only 摘要不能直接作为 loop 支持。
签名和支持资格仍来自审计生产者声明，不独立认证代码存在、管理器加载或设备路由。

计划类型为 `loop_pilot_plan`，固定发布路径和 120 秒终态窗口。PIN 覆盖审计、所选 APK、全部审计
引用、prepared inputs、ADB 以及当前实际加载的完整本地源码链：新 collector、build_aa_pilot_session
和 collect_b10 导入链。复用 B10 的严格 process_identity、框架声明与固定输入摘要算法，不使用 B10
的 plan、execute 或硬编码停止合同。common 的原始读取、设备检查和 transport 边界保持不变。

## 单次执行

```text
python -B tools/performance/collect_loop_pilot.py execute <plan.json> \
  --plan-sha256 <独立保存的计划SHA256> --serial <明确唯一设备> --idle-confirmed --scope-confirmed
```

确认参数只记录设备空闲及固定宿主／模块用户 0 范围的声明，不提供授权。PIN／serial／声明检查
在 transport 创建前完成。独占 execution 目录消费计划，失败或中断也不重放。启动前拒绝已有宿主；
设备／boot／用户、两包的安装字节、PM UID、四个 proc UID、PID 与 start ticks 均核对。
完整 cmdline 仅允许固定包名后有一个或多个 NUL，完整读取前后身份一致；拒绝额外非零参数。
完整结构化 start 与计划身份、计数参数、measurement_kind、PID/TID 一致后才能认领 owned 进程。

日志从启动前开始，不清共享日志，固定标签／threadtime，保留 transport 原件。终态窗口从启动命令
正常返回后开始，各命令另有超时。只有完整 end 后才关闭自身 logcat 子进程，固定稳定原文长度／SHA，
交给既有 `build_session`，由它复核事件顺序、样本、checksum、计数及框架字段。
日志原文最多 16 MiB，成功 JSON 最多按现有 2 MiB 读取边界固定。

未知启动／停止、transport poisoned 或身份变化不重放；只有完整 owned 宿主可在 PIN、设备、安装包
和严格进程身份再次匹配后被停止。停止只有一次守卫尝试，不操作其他设备，不更新或卸载应用，
不修改模块范围，不清数据、profile 或缓存。电池／thermal 边界原件保留，沿用超过 42°C 或升温至少
5°C 的失败规则；边界检查不是连续温控或无降频证明。

## 计时和证据

计时仍是同一线程 `threadCpuTimeNanos()` 包围整段 loop 的原始端点。区间包含循环、目标方法、
框架路径、校验和累加及该模式实际执行的检查；observed 计数操作也在区间内。日志输出、样本后
计数快照在区间之外。B03 的 box 分配／最终检查和 B06 四层递归均属于原有包络，不做扣除。
它不是 wall time、纯 callback CPU、纯 dispatcher 开销或任务 p95／p99。

原始 `raw-threadtime.log` 和 `single-verification.json` 在停止前留存。build_session 生成的
`preflight.status=not_verified` 原样保留；独立临时 preflight 不能证明正式无计数测量 hook 每次命中。
observed 聚合计数虽加强正确性观察，也不能排除同样本中的漏调与重复抵消，更不能与 unobserved
混成同一负载或通过相减得到无扰动回调成本。所有 formal_metrics 继续为 null。

只有停止确认后才发布 `session-manifest.json`、`session.json` 和 `capture.json`。
session 原样使用 `aa_pilot_session`；外部 manifest 的 process_start_token 来自真实
`boot_id:pid:start_ticks`，不是随机占位身份。强 capture 另保留用户／boot／UID／ticks、计划和原日志
SHA、完整制品 PIN、框架、pair_order、host_clock_token、主机 launch／terminal 单调时间，以及
session、manifest、单侧报告的原字节 SHA。capture 类型为 loop_pilot_capture，状态为
collected_and_owned_stop_confirmed。固定输入摘要算法与 B10 相同：将按 path 排序的 plan.pins 和
adb_sha256 组成对象，按 sorted keys、紧凑分隔符、ensure_ascii、UTF-8 编码后计算 SHA256。

single-verification 的 timing 保存原顺序 intervals_ns、sample_count、zero_intervals、positive_intervals。
零值不删除、不补正、不造成比率；全零返回 unresolved。有部分零时单侧仍保留完整记录，后续配对
应将有零样本的该对标记 unresolved、比值置 null。缺失时钟／样本不是 0：它们产生 incomplete 或
invalid，总采集失败，保留原日志和可得 partial observations，不发布成功 session／capture。

`result.json` 使用 loop_pilot_collection_result：collection_status 为 collected／failed；
无计数完整采集 result 保持 reported_only，带计数为 observed_session_consistent，全零 unresolved，
其他采集或收尾错误为 collection_failed。CLI prepare／observed consistent 退出 0，reported_only／
unresolved 退出 2，失败退出 1。stop_attempted 包括一次守卫收尾尝试，不保证 stop 命令已发出。
应同时核对 result、owned-stop.confirmed、capture、session 与 raw，不能只看文件存在或退出 0。

`verify_loop_pilot_pair.py --help` 列出两侧各自的 plan、capture、result、owned-stop、raw log、
session 和 session-manifest 输入，以及预登记的 `--pair-order`。它逐侧重建 session、重验 raw、
确认 owned stop 与绑定身份，再核对共同工作负载、控制、制品、设备及进程和主机顺序。
无计数的 `reported_only` 保持原状态并返回 2；任一零样本使配对未决且比率为 null。配对结构
一致不认证设备或主机时钟，也不评估性能门槛。同控制组历史 session 的描述性汇总可另用
[`analyze_pilot_aa_noise.py`](LOOP_PILOT_NOISE.md)。

下一步先对每个 control 分别做 AB／BA 平衡顺序 A/A，保留所有失败、零值和长尾，按进程／配对
汇总噪声，不把样本迭代当独立设备实验。采样参数、设备 cell、模式和噪声／排序规则应在 A/A 后、
正式跨控制比较前冻结；达不到分辨率或稳定性时报告 unresolved，不挑选顺序或删慢样本制造收益。
本工具不自动冻结阈值，不进行 A0/A2 正式 A/B，不将记录完整性、仪表计数或包络差称为 SDK 收益。
