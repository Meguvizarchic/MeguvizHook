# B10 资源快照与 APK 构成观测

本入口观察选定测试夹具的包体构成、单个宿主进程的资源阶段值及实际关闭回执。
它不填充正式 profile 的预算，不判断 `GATE-002`，也不证明相对原生 API 的性能收益。

## 运行事务

宿主和两代 benchmark 模块接受 `workload=B10`、`control=A0|A2`，固定
`warmup=0`、`samples=1`、`iterations=1`、`measurement_mode=unobserved`。
run/pair/session 使用三个互异的小写 UUID，每个新进程只执行一次；桥仍为九参数。
默认未注入的控制桥不能声称已完成事务。目标为独立、禁止 R8 改写的 `B10Target.call()`，
固定返回 42，使用原方法计数核对，不调用 GC、不清缓存、不休眠或增加监测线程。

事件顺序固定为：

```text
start -> resource_before -> installed -> resource_installed -> configured
      -> cleanup -> post_close -> resource_closed -> end
```

共同的目标、反射 controls、计数器和资源 source 在 baseline 前准备；A2 的 environment/spec/
receipt 安装过程在 baseline 后。每次读取按 status、FD 的顺序进行，再记录结束时钟；日志输出
在读取之后。安装后调用一次目标，before/after/original 为 1/1/1；关闭注册和环境后再调用目标，
返回 42，计数为 1/1/2。采样前后不是原子状态，第一次读取、日志、分配、类加载、ART 和框架工作
均可能影响后续值。关闭后 snapshot 时 session 仍强持有本次 handle、registration、receipt、
environment、Method 和 callback；不主动清引用制造释放结果。

A0 使用直接框架句柄与 `framework_unhook_contract`；A2 使用实际 SDK receipt/close report。
两者都只允许选定目标，实际安装/关闭未知、目标错误或收尾失败保持失败并留存原始观察，不重放。
A0 不提供 SDK 专属在途数或 registration state；最终以 `selected_registration_state=UNOBSERVED`
标明不可观测。A2 保留真实的 CLOSED 或 RETIRED_RESIDENT、入口恢复与物理释放声明。
`activeRegistrationCount()` 是环境映射大小，本探针不把它当作活跃/退休对象数。

## 资源字段

三个 resource 事件包含各自的 `resource_start_ns` / `resource_end_ns`、三项原值及状态：

| 原值 | 状态 | 来源与读取边界 |
|---|---|---|
| `rss_bytes` | `rss_status` | `/proc/self/status` 的 VmRSS，核对 Pid 后将 kB 乘 1024；整个进程 RSS |
| `thread_count` | `thread_status` | 同一 status 的 OS Threads，范围 1..2147483647 |
| `fd_count` | `fd_status` | `/proc/self/fd` 有界枚举，关闭枚举目录后逐项读取 NOFOLLOW 属性 |

值是整数或显式 `null`；状态只能是 `observed` / `unavailable`。status 最大 64 KiB，FD 列表
最多 4096 项。字段缺失、PID 不符、重复或错误格式、单位不符、溢出、普通 I/O/权限失败均不造零。
各项独立保留可读结果，读取器与目录使用确定性关闭。FD 不读取链接目标和内容；枚举后已消失的项
跳过，其他属性读取错误使该项计量不可用，不盲目减去 1。枚举与属性读取之间仍存在关闭、复用或
新增 FD 的竞态，这不是原子的内核 FD 总量。真实读到的零和负的阶段差值保留。

资源不可用不跳过安全收尾，但离线核验结果为 `incomplete`。时钟异常、负值或倒退使事务失败，
已有端点、资源值和关闭报告仍保留。RSS 差值不等于 Java heap、PSS、SDK 独占分配或泄漏量。
`resource_closed` 显式报告 `retired_resident_bytes=null`、`retired_bytes_status=not_attributable`；
CLOSED/RETIRED_RESIDENT 或 RSS 升降不能折算为 SDK 退休驻留字节，入口恢复也不证明物理释放。

## 保存日志的独立核验

对完整、未清洗的 threadtime 原始日志使用：

```text
python -B tools/performance/verify_b10_resources.py <RAW_LOG>
  --run-id <RUN_UUID> --pair-id <PAIR_UUID> --session-id <SESSION_UUID>
  --side A --pid <PID> --tid <CONTROLLER_TID> --backend 102 --control A2
```

核验器核对 PID/TID、请求身份、阶段唯一性与顺序、固定正确性计数、控制组字段、资源值/状态和
读取窗口。它保留安装后及关闭后减 baseline 的有符号算术差值，名称是 `phase_deltas`，不填入
`additional_rss_bytes` 等正式归因字段。完整可读事务返回 `resource_record_consistent` / 退出 0；
资源不可用、失败或缺事件返回 `incomplete` / 退出 2；矛盾记录返回 `invalid` / 退出 1。
零时间窗口不是性能失败或性能通过依据。身份与计数仍是生产端报告，`device_authentication`
保持 `not_verified`，不能用此工具独立证明设备运行身份。

本轮没有扩展 B08/B09 采集器的支持声明，也没有操作设备。后续真实采集须先准备含 B10 的签名
制品和支持声明、协调设备空闲，并继续使用显式单设备、完整 owned process 守卫、原日志与
transport 证据；不能把第四十六轮旧 APK 当成已含 B10，也不能直接复用 B08/B09 计划执行 B10。

## 已保存签名 APK 的构成

`observe_b10_apk.py` 只读已 pin 的签名制品审计与保存 APK，不启动子进程、不安装或重新签名。
输出父目录须已存在，输出文件须为新文件。以下审计文件、其引用的制品和证据由调用方自备，
不随仓库发布：

```text
python -B tools/performance/observe_b10_apk.py --root <证据根目录> \
  --audit <根目录内自备签名制品审计JSON> \
  --audit-sha256 <独立保存的审计文件SHA256> \
  --certificate-sha256 <独立确认的签名证书SHA256> \
  --backend 102 --out <NEW_JSON_UNDER_ROOT>
```

同一份稳定读取的字节用于 APK SHA、ZIP 解析和逐项内容 SHA。记录每个条目的压缩/展开字节、
DEX/native/manifest/resources/assets/metadata 分类，以及 APK 总长减压缩 payload 的容器残差。
残差包含容器、对齐和签名相关材料，不归因为签名字节或 SDK 字节。条目不能越界、重复、破损，
DEX 名称和内容 SHA 须与审计声明一致；不解压到文件系统。包名及签名资格来自 pin 的审计生产端
声明，两个制品的声明均须与调用方显式指定的证书摘要匹配；不由此工具重新解析 Android binary
manifest 或证明密码学签名。证书摘要应从独立可信来源确认，不能只从待核验的审计中抄取。

A0/A2 在同一 module APK 中选择代码路径，所以报告 `same_artifact_not_sdk_delta`；
`additional_apk_bytes=null`、增量状态 `unknown`。它不将 round40→round46、101→102 或未签名→
签名包的差值冒充 SDK 包体净增量。未来需要独立、同构、可裁剪且固定构建输入的配对 APK。
运行时 RSS/thread/FD/retired 字段均为 `null/not_observed`；Gradle runtime dependency gate
只说明依赖图隔离，不说明进程资源。所有预算、稳定性、阈值及发布资格仍需独立证据。
