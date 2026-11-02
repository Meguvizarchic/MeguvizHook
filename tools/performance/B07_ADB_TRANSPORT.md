# B07 显式设备传输记录层

`b07_adb_transport.py` 供 B07 采集器调用，不提供任意命令 CLI。构造
`TargetAdb(adb_path, serial, run_dir, host_clock_token)` 时必须提供具体设备序列号、普通 ADB 文件、
已存在的独立运行目录和标准小写时钟 UUID；它在该目录中独占创建 `transport/`，拒绝复用既有账本。
本模块的测试使用模拟子进程，不会连接任何设备。

`run(category, args, timeout=30, accepted=(0,))` 返回 `CommandResult` 的原始 stdout、stderr、
实际 returncode 和 command_index。仅允许 `get-state`、`get-serialno`、`shell`、`exec-out` 根命令，
拒绝隐式设备列表、额外全局选择器、server 控制及通过该方法清理 logcat。每次调用实际构造
`[adb, "-s", serial, *args]`，不使用主机 shell。**ADB shell 仍由设备端解释**；采集器必须只使用
固定命令，并校验动态包名、PID、路径和 UUID，不能把任意用户文本作为 shell 片段。

命令启动前先独占写入并读回 `adb-N.intent.json`。它保存类别、时钟 token、主机开始时刻、ADB
字节哈希、显式 `-s` 选择声明、序列号 SHA、完整 argv 的 SHA 和将选择值替换为摘要的 argv 模板。
实际序列号只在调用 argv 中使用；原始命令输出可能包含序列号等私有设备数据，必须按本地证据保管。
这些记录不能独立认证操作系统实际路由或设备身份。

stdout、stderr 分别保存在新文件中，不重新编码或截断。命令完成后稳定读取的同一份 bytes 用于
账本哈希和返回给采集器，避免后续重读产生不一致。结果保存真实退出码、主机 PID、终止时刻和
原始文件长度与哈希。显式允许的非零退出码仍保留原值，采集器必须继续检查其语义。

意图记录失败时不启动命令。超时、输出超过上限、非预期退出、捕获或结果记录失败都会抛
`TransportError` 并 poison 该实例，之后拒绝任何新设备命令；没有自动重试或回放。异常的
`outcome_unknown` 表示设备副作用不能据此确定，`command_index` 关联对应账本。无法启动子进程
和启动后失败分别保留，不把 ADB 的失败当作可回滚。

`start_logcat(label)` 固定启动 `logcat -v threadtime -T 1 MeguvizHookBench:I *:S`，不清共享日志。
返回的 `OwnedCapture` 提供 `raw_path`、`stderr_path`、`poll()` 和 `stop(reason="completed")`。
启动与终止记录均绑定原始意图；启动后记录失败会停止自己创建的 Popen。后台监控与停止检查
观察 stdout 16 MiB、stderr 1 MiB 上限；超限即失败并停止该子进程，完整保留已写文件，不截断。
这是观察式上限，终止存在调度延迟，文件可能略超过上限。主机 capture 自行退出不视为正常完成。

停止只使用该 Popen 句柄，先 terminate、有界等待，再必要时 kill 和有界等待；不按名称终止其他
进程、不调用 kill-server 或设备端 kill。即使 transport 已 poison，仍允许关闭已经拥有的本机
capture。`stop` 幂等，失败记录不会被后续停止重写成成功。采集器仍须在 finally 调用它；中断或
进程崩溃可能留下部分文件，没有终态账本的 capture 不能当作完成。

`stop` 的调用方原因只描述本机 capture 为何关闭；例如 `collector_finally` 逻辑失败仍可正常关闭
健康 capture，且不会额外 poison 传输层，便于采集器继续执行已授权的身份守卫清理。采集器的失败
结果必须独立保留，不能因本机 capture 正常关闭而升格。内部启动／监控／上限故障、提前自行退出、
实际停止失败或账本失败仍会 poison。

记录采用独占文件及写入后读回检查，不是持续文件锁或崩溃事务。原始输出和记录可能在后续被外部
修改，消费时仍须重新核对哈希。不得把目录存在、意图文件存在或某个 returncode 单独解释为
设备测试成功、安装恢复或性能门槛通过。
