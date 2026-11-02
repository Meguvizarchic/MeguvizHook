# B08 在途关闭：单进程离线核验

`verify_b08_close.py` 只核对一份 B08 事务原始日志，不连接设备、不修改输入、不评价性能门槛。
B08 仅使用 A2、无计数模式标识、零预热／单样本／单次事务；它包含专用正确性计数，不能与
B01–B06 的调用循环或 B07 的安装包络 schema 混用。

计划准备和原始日志采集见 [B08/B09 采集入口](B08_B09_COLLECTOR.md)。

```text
python tools/performance/verify_b08_close.py <原始threadtime日志> \
  --run-id <RUN_UUID> --pair-id <PAIR_UUID> --session-id <SESSION_UUID> \
  --side A --pid <宿主PID> --tid <controller的Linux TID> --backend 102
```

run、pair、session 均为标准小写 UUID 且互不相同，side 为 A／B，backend 为 101／102。
它们以及 PID、controller TID 须由调用方明确提供，工具不会把日志中的身份自动升级为独立认证。
所有所选日志的 threadtime 前缀 PID／TID 和 JSON 声明 PID 必须匹配；若额外声明 tid 或 backend，
也必须一致。JSON 共同字段为 schema=1、run_id、pair_id、session_id、side、pid、B08、A2。

## 正常事件合同

所有事件由 controller 在同步观察后按下表顺序发出，使用 `MeguvizHookBench` tag。
第一条 status 为 `start`，其余正常事件 status 为 `pass`。重复、逆序或未知事件不能被忽略。

| 事件 | 必需观察 |
|---|---|
| start | warmup=0、samples=1、iterations=1、measurement_kind=`close_with_inflight_wall_ns` |
| installed | receipt_status=INSTALLED、正 generation、target_count=1 |
| old_entered | before_count=1、after_count=0、old_original=1、new_original=0；worker_tid；worker_completed=false、gate_released=false、worker_alive=true |
| close_returned | close_wall_start_ns/end_ns、closer_tid；new_callbacks_rejected=true、entry_restoration=RESTORED、in_flight_at_close=1；registration_state=CLOSED 或 RETIRED_RESIDENT、physical_release 实际枚举；before_count=1、after_count=0、old_original=1、new_original=0；worker_completed=false、gate_released=false、worker_alive=true |
| post_close | result=42、before_count=1、after_count=0、old_original=1、new_original=1、post_tid；worker_completed=false、gate_released=false、worker_alive=true |
| old_completed | result=42、before_count=1、after_count=1、old_original=1、new_original=1；worker_completed=true、gate_released=true；worker_alive、closer_alive、post_alive 均为 false |
| configured | backend 与外部声明相同；非空 framework_name/version、正 reported_api |
| cleanup | worker_alive=false、closer_alive=false、post_alive=false、cleanup_alive=false、cleanup_confirmed=true |
| end | detail=completed、cleanup_reported=true |

worker 是被原方法内部 release gate 持住的旧调用，post 是关闭后发起的新调用；二者原方法
各一次，旧调用释放后仍完成旧计划的 after。before／after 是 SDK 回调的专用正确性计数。
进程内的计数、gate 和线程结果是生产者报告，不是工具从返回 42 推导出的事实。

worker、closer、post 的 TID 是各线程实际 Linux TID，不能用 Java `Thread.getId()` 替代。
worker 必须不同于 controller；closer 和 post 也不能是 controller 或仍持有 gate 的 worker。
已完成的 closer TID 可能被后启动的 post 线程重用，因此工具允许二者数值相同，不据此认定
两个时间段属于同一实际线程。重复声明的同名线程字段须保持一致。

当前核心即使在关闭时观察到一条在途调用，关闭报告的状态也可能已是 CLOSED 或
RETIRED_RESIDENT，不能强制要求 RETIRING。`in_flight_at_close=1` 是关闭时快照，旧调用排空后
同一报告不会自动变 0；核验器不要求它变化。`physical_release` 接受 RELEASED、NOT_CLAIMED、
UNKNOWN 三种实际回执值，仅保留声明，不认证物理释放或任意机器码返回地址已经离开被 Hook 区域。

## 时钟、失败与证据

关闭墙钟由 closer 在其单次 `close()` 前后读取，单位 ns。两端为非负、有符号 64 位范围内的
真正整数，结束不得早于开始；不会把 controller CPU 当作 closer CPU，也不做跨线程 CPU 相减。
零关闭区间保留为 `unresolved`，不估算或扣除读钟成本。该区间包含实现、驱动及线程调度等影响，
不能单独解释为纯关闭成本、原生 API 差值或正式性能收益。

失败阶段可省略尚未读到的时钟／线程字段，不得以 0／−1 伪造；真实负时钟仍须原样保留。
核验器对已匹配的记录保留已知标量观察的 presence 和数值，不回显任意额外字段。
关闭报告的 registration_state、entry_restoration、physical_release 仅按已定义枚举白名单保留，
失败时的 RETIRING、UNKNOWN、NOT_RESTORED 等仍可见，任意字符串不会回显。
相同时钟端点或线程字段在不同事件中相互矛盾会被拒绝；失败不能掩盖无效时钟。
`InvalidRecord.partial_report` 与 CLI 的 invalid JSON 都保留已有观察和原始日志 SHA-256。

超时、线程仍存活、清理未确认或报告失败不能返回一致；即使释放 gate，也不能直接推断旧调用
已排空。生产者应有界等待自己创建的线程；若 closer 仍活着，不应在 controller 再同步重入
环境关闭。离线工具只能核对报告的这些结果，不验证实际线程或有界控制流。

结果优先级为 `invalid` > `incomplete` > `unresolved` > `close_record_consistent`。
矛盾、非法字段、重复／逆序、负时钟或倒退为 invalid（退出 1）；缺事件／字段或明确失败为
incomplete（退出 2）；完整合法而关闭时钟差为零则 unresolved（退出 2）；完整一致为
close_record_consistent（退出 0）。`outcome_unknown` 或 `timed_out` 若出现在成功事件中，只能是
严格的布尔 false，不能以额外矛盾字段继续报告成功。

所有结果均为 `performance_gate=not_evaluated`、`device_authentication=not_verified`。
原始日志文件不改写，失败、超时、零值和部分记录均应保留；结果 JSON 不能替代原始证据。
没有设备加载、ART、管理器范围、统计稳定性、置信区间或性能门槛结论。本轮工具无需设备操作。
