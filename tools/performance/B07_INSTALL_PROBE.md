# B07 安装包络：单进程离线核验

`verify_b07_install.py` 检查一条 B07 受控安装日志，不连接设备、不修改输入，不复用 B01–B06
调用循环记录格式，也不执行性能门槛。调用方需提供分别独立的 run/pair/session UUID、side、
backend，以及从其他证据确定的 PID/TID：

两份单进程记录的配对核验见 [B07 A/A 配对核验](B07_INSTALL_PAIR.md)。

```text
python tools/performance/verify_b07_install.py <threadtime日志> \
  --run-id <UUID> --pair-id <UUID> --session-id <UUID> \
  --side A --pid <PID> --tid <TID> --backend 102
```

仅接受 B07、A2、预热 0 次、单样本、单次迭代；成功记录依次包含 `start`、`install`、
`hook_check`、`configured`、`cleanup`、`post_close`、`end`。JSON 声明的 PID 与每条
threadtime 前缀 PID/TID 均需匹配调用方声明，额外 JSON 字段被容忍但不自动认证；JSON 若额外
声明 TID 或 backend，也须一致。每阶段的必需回执、结果、计数、框架信息和关闭字段均逐项检查，
重复或逆序事件不能被忽略。原始日志的 SHA-256 随结果输出；仅对保存的日志做一致性检查，不能
独立认证设备、运行进程、框架、制品来源、安装时间或模块作用范围。

身份匹配的 `install` 事件即使失败，也会在 `install_observations` 中保留四个已知端点的存在标记
及实际存在的整数值；不会回显未知字段。仅同一时钟两端均为非负且未倒退的有效整数时计算差值。
重复安装事件会分别留存但使结果无效，不会跨事件拼接端点。CLI 的 `invalid` 结果也保留此前已匹配的
端点；调用 Python API 时对应信息附在 `InvalidRecord.partial_report`。这些记录仍是生产者自报。

`install` 记录原始 `wall_start_ns`、`cpu_start_ns`、`cpu_end_ns`、`wall_end_ns`；前两次
读取发生于 `HookEnvironment.install(spec)` 前，后两次读取在返回后。墙钟是单调 elapsed realtime，
CPU 是调用线程时间；二者保留独立端点与差值，不互相相减、不扣计时器。端点为负、倒退或类型错误为
`invalid`，任一差值为零时整个结果为 `unresolved`。缺字段、缺事件及报告的失败为 `incomplete`；
完整成功为 `install_record_consistent`。退出码依次为 1、2、2 和 0，失败不会被转为成功。
这些测量是一次 install 包络，包含框架调用及计时影响，不证明每个安装阶段耗时、稳定性、完整系统
CPU、性能提升或性能门槛通过。

计时外的首次调用须报告返回 42、before 和 original 计数各为 1。关闭报告须声明拒绝新回调、
在途数为 0、入口恢复为 `RESTORED`；`physical_release` 可报告 `RELEASED`、`NOT_CLAIMED` 或
`UNKNOWN`，均只是回执声明。关闭后的相同调用须报告返回 42、before 仍为 1、original 为 2。
这些结果仍是生产者报告，不能据此独立认证物理卸载、每次调用或设备行为。原始失败日志应保留，
不得通过删去失败或零值把一次未完成测试重写为成功。
