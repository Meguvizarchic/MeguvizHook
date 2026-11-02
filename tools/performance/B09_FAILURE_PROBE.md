# B09 固定异常路径：单进程离线核验

`verify_b09_failure.py` 核对一份 B09 原始 threadtime 日志，不连接设备、不改输入，也不评价
性能门槛。B09 仅支持 A0／A2、unobserved 模式标识、零预热／单样本／单次事务；事务含专用
正确性计数。每个新进程只选择 before、after 或 original 中的一项固定普通异常场景。

计划准备和原始日志采集见 [B08/B09 采集入口](B08_B09_COLLECTOR.md)。

```text
python tools/performance/verify_b09_failure.py <原始threadtime日志> \
  --run-id <RUN_UUID> --pair-id <PAIR_UUID> --session-id <SESSION_UUID> \
  --side A --pid <宿主PID> --tid <controller的Linux TID> \
  --backend 102 --control A2 --failure-case original
```

三个标识为互不相同的标准小写 UUID；side 为 A／B，backend 为 101／102。选中事件的
threadtime 前缀 PID／TID 和 JSON pid 必须与外部声明匹配；共同字段为 schema=1、run_id、
pair_id、session_id、side、pid、workload=B09、control、failure_case。额外 JSON tid 或 backend
若出现也须一致。工具仅交叉核对声明与保存字节，不认证实际设备、进程、框架或模块范围。
每份 CLI 原始日志上限 16 MiB；超限时不把截取前缀的摘要冒充完整文件摘要。

## 场景与成功事件

宿主在计时前准备一个 `RuntimeException` 对象；callback 或原方法应抛出该同一对象。
这不是异常实例分配／栈捕获开销测量，也不包含真实 VM 错误注入。A0 使用框架直连拦截器，
A2 使用 SDK 的 PROPAGATE 语义；不能将这三种固定普通异常外推到不同异常策略或多重失败。

成功事件顺序固定为：

`start → installed → failure_call → configured → cleanup → post_close → end`

使用 `MeguvizHookBench` tag，均由同一个 controller 线程输出。首条 status 为 start，其余正常
记录为 pass；重复、逆序或未知事件不能被丢弃。**预期抛异常是测试成功**：failure_call 必须是
pass、outcome=threw、identity_preserved=true。诊断本身发生错误才是 status=fail。

| failure_case | failure_call 的 before／after／original | after 看到的结果 | post_close |
|---|---|---|---|
| before | 1／0／0 | after_outcome=not_called | outcome=returned、result=42，original 累计 1 |
| after | 1／1／1 | after_outcome=returned、after_result=42 | outcome=returned、result=42，original 累计 2 |
| original | 1／1／1 | after_outcome=threw、after_identity_preserved=true | outcome=threw、identity_preserved=true，original 累计 2 |

post_close 的 before_count／after_count 均保持首次调用值，不能因为第二次原方法调用再次增加。
before_count、after_count、original_count 均是真正的非负整数，不接受布尔值。

after 所见结果由 producer 在回调内记录，并在调用返回后单独核对；不能仅在 after 内抛一个断言，
因为原方法已经失败时 SDK 会按合同保留原异常，after 自身的普通失败可能只进入诊断。
异常身份也是 producer 在进程内按引用比较后报告，不是离线工具从类名或返回值推导的结论。

互斥结果不应同时出现：failure_call 始终抛错，不得附 result；before 的 after 未运行，不得附
after_result 或 after_identity_preserved；after 场景只带 after_result，original 场景只带
after_identity_preserved。post_close 返回时只带 result，抛错时只带 identity_preserved。

start 还必须声明 warmup=0、samples=1、iterations=1，且
measurement_kind=`failure_call_wall_and_thread_cpu_ns`。configured 须报告所选 backend、非空
framework_name／framework_version 和正 reported_api。end 为 detail=completed、cleanup_reported=true。

## 原生与 SDK 安装／关闭回执的区别

| 控制组 | installed | cleanup |
|---|---|---|
| A0 | install_source=framework_handle、handle_returned=true | close_source=framework_unhook_contract、entry_restoration=RESTORED、physical_release=NOT_CLAIMED |
| A2 | install_source=sdk_receipt、receipt_status=INSTALLED、正 generation、target_count=1 | close_source=sdk_close_report、entry_restoration=RESTORED、new_callbacks_rejected=true、in_flight_at_close=0、registration_state=CLOSED 或 RETIRED_RESIDENT、physical_release 实际枚举 |

A0 的 void unhook 只有 API 合同来源，不能伪造 SDK generation／receipt_status、在途数、
new_callbacks_rejected 或 registration_state；这些字段若出现在 A0 记录中会被拒绝。
成功记录若额外声明 target_count，必须为 1。A2 的 physical_release 可为 RELEASED、NOT_CLAIMED
或 UNKNOWN，均仅是实际回执声明。两组关闭后均再验证目标行为和不增长的 Hook 计数；这仍不
证明任意未来调用、物理释放或所有机器码返回地址已经离开目标区域。

cleanup_confirmed 若出现在成功的 cleanup／post_close 中只能为 true；早期记录不据此假定清理
已经发生。任何成功阶段额外出现 outcome_unknown 或 timed_out 时只能是严格布尔 false。

## 原始时钟、失败与证据保留

failure_call 保留 `wall_start_ns`、`cpu_start_ns`、`cpu_end_ns`、`wall_end_ns`。读取顺序为
墙钟开始 → 同线程 CPU 开始 → 一次调用／预期异常捕获 → CPU 结束 → 墙钟结束；墙钟区间包含
CPU 读钟调用。反射、驱动和异常解包也可能包含其中，不能称为纯 callback CPU 或纯异常成本。
工具不跨时钟相减、不扣计时器，不比较两个时钟绝对值大小。

端点必须为非负有符号 64 位整数，结束不得早于开始。零区间保留为 unresolved；真实负值、倒退
或非整数判 invalid，不通过补值或删去记录修复。失败事件可省略尚未读取的端点，不得伪造 0／−1。
同一端点若在不同事件重复出现，数值必须一致。

已有的匹配记录会保存到 observations，包含已知标量的 presence 和原始值，以及白名单的 outcome、
after_outcome、安装／关闭来源和 CloseReport 枚举。部分有效时钟仍可描述各自差值；未知字段、
任意异常消息与任意字符串不会回显。后续 invalid 或解析失败时，原始日志 SHA 和已有观察仍保留
在 CLI JSON 或 `InvalidRecord.partial_report`；递归解析错误也不会生成一致结果。

结果优先级为 invalid > incomplete > unresolved > failure_record_consistent：

- 矛盾、非法时钟、错误身份、重复／逆序和不适用的回执字段为 invalid，退出 1。
- 缺失字段／事件或 producer 报告诊断失败为 incomplete，退出 2。
- 完整合法但任一调用时钟区间为零，结果 unresolved，退出 2。
- 所有固定观察一致才为 failure_record_consistent，退出 0。

所有输出均保留 performance_gate=not_evaluated、device_authentication=not_verified。
原始日志不修改，失败、超时、零值及部分记录必须保留；输出 JSON 不能替代原始证据。
没有真机运行、统计稳定性、置信区间、原生 API 性能收益或正式门槛结论。
