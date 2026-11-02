# B07 有界采集入口

`collect_b07.py` 把已审计的签名测试制品、显式设备选择、两侧采集和现有 B07 输入生产器接在一起。
`prepare` 只读取本机文件；`execute` 才会发设备命令。该入口不安装、更新、卸载应用，不清数据、
profile 或共享日志，不修改管理器作用范围，不停止未知进程，也不自动重放失败或超时的操作。

## 本机准备

```text
python tools/performance/collect_b07.py prepare \
  --root <工作区绝对路径> \
  --audit <根目录内自备签名制品审计JSON> \
  --audit-sha256 <独立留存的摘要SHA256> \
  --adb <明确ADB可执行文件路径> \
  --backend 102 --serial-sha256 <唯一目标serial的SHA256> --pair-order AB \
  --framework-name <预期框架名称> --framework-version <预期版本> --reported-api <预期API> \
  --output <工作区内全新计划目录>
```

审计文件及其引用的制品和证据需由调用方自行准备，不随仓库发布。只接受
`signed_artifacts_locally_verified` 摘要形态。所选宿主和单代模块必须是
已报告签名、v2 和依赖隔离通过的 `deviceTest` 制品；APK 字节数、SHA、证书声明、摘要引用的
签名／依赖／静态证据及负载配置输入均固定。工具不会读取或复制私钥。

准备还固定 ADB 可执行文件与实际已加载的采集、transport、builder、single、pair 和共享解析器
源文件哈希。计划包含五个独立 pair/run/session UUID、一个主机时钟关联 UUID、显式 AB 或 BA、
预期框架声明和固定 B07/A2/0/1/1。PID、TID、UID、boot、实际设备信息与时间窗口在执行时观察，
不会在准备阶段用零值或预测值占位。计划只表示 `prepared_not_executed`，本地时间和随附哈希
不是独立可信的预登记证明。

目录独占创建，核对 `plan.pending.json` 后以硬链接发布 `plan.json`。准备返回其原字节 SHA，
执行者必须显式提供该 SHA；原计划及 pending 均保留，不覆盖旧目录。计划还固定自己的发布路径，
同字节复制到另一目录也不能作为同一计划再次运行。

## 明确确认后的执行

```text
python tools/performance/collect_b07.py execute <plan.json> \
  --plan-sha256 <准备时独立保存的SHA256> --serial <明确唯一目标> \
  --idle-confirmed --scope-confirmed
```

两个确认参数只是调用方声明：设备已经空闲，并且只启用计划所选模块、作用于固定 benchmark
宿主的用户 0。它们不是授权来源或管理器状态认证。没有这些显式声明、serial 摘要不匹配、任一
输入 pin 不符时，不会创建 transport 或发出设备命令。执行前独占创建计划目录下的 `execution/`，
同计划中断或失败后也不能再次运行；未知命令结果不能靠换目录或自动重试恢复。

执行只支持当前 Android 用户 0、固定宿主
`io.github.jichuo1.meguviz.hook.benchmarkhost` 与选中的 `benchmark101` 或 `benchmark102` 模块。
逐命令和流式日志均通过 [显式目标 transport](B07_ADB_TRANSPORT.md) 留证，实际 argv 使用
`-s <serial>`。元数据保存目标摘要；`get-serialno` 等原始 stdout 本身可能含 serial 明文，属于
本地私有证据，不能声称原始日志已脱敏。

采集先核对 serial 观察、在线状态、用户、boot、fingerprint、Android API、ABI，以及两个包唯一
安全 `base.apk` 的路径、stat、大小和 SHA。UID 来自精确 PM 包记录。没有匹配的已安装包就失败，
不会自动安装新包；包名与现有 pilot 相同，不能把本机准备解释为获得覆盖许可。读取受限 APK 和
`/proc` 使用固定 `su -c` 脚本，动态内容仅为验证过的路径、正整数 PID 和固定包名。

每侧启动前必须没有现存宿主进程。启动只发生一次，参数完整包含计划的 run/pair/session/side、
B07/A2、0/1/1 和 unobserved。新进程通过精确 cmdline、PM UID 与 `/proc` 四个 UID、前后相同的
start ticks 和 PID，以及计划 start 事件的 UUID／PID／TID／配置绑定。终态必须由完整换行的
结构化事件识别，字符串包含 `end` 不算完成。活动日志允许追加，拒绝换 inode、缩短或超过
16 MiB；只解析完整前缀。关闭本机 logcat 后，稳定读取的原日志还须与 transport 终态的长度和
SHA 一致，再独占保存为 `raw-threadtime.log`。

两侧边界保留 battery 和 thermalservice 原始输出；电池边界温度高于 42°C 或较初始边界升高
至少 5°C 时停止后续采集。这不是采样期间的连续温控或无降频证明。

只有本次计划 start 已绑定的宿主才可能被停止。停止前再次核对计划 pins、设备／boot／用户、
两份安装 APK 和完整进程身份；任一不一致就拒绝 force-stop。停止超时或不能确认退出时不再
尝试。日志流关闭仅作用于本工具持有的本机 Popen 子进程。身份观察不是原子锁，也不证明任意
并发系统变化均可被排除。

## 结果和中断

每侧保留前后状态、进程观察、launch intent、原始日志、single 核验和自身停止记录。第一侧失败
就停止后续侧；部分证据仍送入 [B07 输入生产器](B07_COLLECTION_INPUTS.md)，缺失的另一侧不会
伪造。完整两侧调用已有 single/pair 核验，日志中的框架字段必须与准备时的预期一致。

`execution/result.json` 的 `collection_failed` 表示采集失败；即使内部某个原日志检查一致，也
不能将它升格为成功采集。零时钟间隔保持 `unresolved`；一致记录仅为 `install_pair_consistent`。
CLI 退出 0 表示准备完成或记录一致；退出 2 表示 unresolved/incomplete；其他失败为 1。
若写入失败或进程被打断，保留部分目录和 transport intent，不清理、不恢复重放，不以文件存在
推断采集成功。stdout 或最终记录失败时不会自动继续第二侧。

所有设备、框架、作用范围、时间及安装声明仍是采集方观察；签名和字节检查也不等于管理器加载、
ART 执行或性能门槛。该工具不冻结阈值，不宣称统计稳定、物理卸载或 A/B 收益。真实设备执行
必须另行满足用户的设备空闲与操作范围约束；本地 Fake transport 测试不能替代真机证据。
