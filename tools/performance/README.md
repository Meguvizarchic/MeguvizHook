# B01/B02/B03/B04/B05/B06 A/A pilot 记录与离线核验

B07 安装包络使用独立单进程日志协议和核验器，见 [B07 安装探针](B07_INSTALL_PROBE.md)；不与
下述调用循环 schema 混用，也不自动评估性能门槛。
两侧安装记录的声明、原日志及执行顺序核对见 [B07 A/A 配对核验](B07_INSTALL_PAIR.md)。
从原始日志和采集声明生成配对清单及证据包，见 [B07 配对输入生产器](B07_COLLECTION_INPUTS.md)。
离线准备及显式目标采集入口见 [B07 采集器](B07_COLLECTOR.md)，命令和日志流记录约定见
[B07 ADB 传输层](B07_ADB_TRANSPORT.md)。

B08 在途关闭使用独立有界事务和日志协议，见 [B08 关闭探针](B08_CLOSE_PROBE.md)。
B09 before／after／原方法异常使用独立事务，见 [B09 异常探针](B09_FAILURE_PROBE.md)。
二者的单场景离线准备和采集见 [B08/B09 采集入口](B08_B09_COLLECTOR.md)。
B10 的独立资源阶段快照、关闭记录与离线 APK 构成观测见 [B10 资源探针](B10_RESOURCE_PROBE.md)。
B10 独立采集和 A/A 配对核验见 [B10 采集入口](B10_COLLECTOR.md)。
同一控制组的原始循环 A/A 噪声描述见 [pilot A/A 噪声工具](LOOP_PILOT_NOISE.md)。
B01–B06 的独立采集和受控 A/A 配对核验见 [循环 pilot 采集入口](LOOP_PILOT_COLLECTOR.md)。
已登记的同控制 A/A 批次执行与全局边界见 [循环 pilot 批次](LOOP_PILOT_BATCH.md)。
冻结声明和离线跨控制组比较的前置约束见 [profile 冻结检查](PROFILE_FREEZE_CHECK.md)与
[loop 比较准备与核验](LOOP_CONTROL_COMPARISON.md)；当前 profile 仍未冻结。

本工具只核对采集者报告的 **B01/B02/B03/B04/B05/B06、A0/A2、101/102 后端** A/A pilot 记录和对应原始日志，
支持既有无计数模式及显式选择的 correctness-observed 模式。
它不执行性能测量，不验证设备或框架的真实身份，不冻结阈值，不评估 `GATE-002`，也不生成正式
`callback_cpu_ns`、p95、p99 或其他门禁指标。A1 需要 BIL 自有适配入口；B07–B10 不在本工具范围内。

B01 目标每次返回 `42L`，B02 以 `(3,4)` 返回 `7`，B03 的 `snapshot(int[] box, int delta)`
每次返回 `42`。B04 将 `(3,4)` 的第一个槽写为 `5`，Hook 下结果为 `9`，卸载后未拦截结果为 `7`。
B05 的无参目标在正常调用时返回 `42L`；Hook 下和关闭后结果均为 `42`。B06 的四层递归目标顶层返回 `4`。
B03/B04/B05/B06 支持离线记录校验，不表示已完成设备采集。pilot 记录的线程 CPU **完整调用包络**还包含
循环、目标方法和框架路径，不能称为 callback CPU。采样中 checksum 累加也有成本；诊断 hook 的预检
不证明随后无计数测量 hook 每次都被 ART 调用。observed 模式在实际测量路径记录原方法与 hook 次数，
但计数器本身改变了被测开销，其结果仍只是带仪表的完整调用线程 CPU 包络；不得将它与无计数模式
混为同一负载，也不得通过简单相减宣称得到了无扰动的 callback CPU。每个样本只核对计数总量；
同一样本内的漏调用和重复调用可能互相抵消，不能据此证明逐次调用均恰好一次。两种模式使用独立
目标方法，比较结果同时包含仪表和 ART 编译等差异，不能归因于纯仪表开销。设备与性能资格另需独立证据。

### B03 浅槽快照负载

每段预热或采样循环创建初值为 0 的单槽 `int[] box`，调用目标时 `delta=1`；原方法每次执行
`box[0] += delta` 并返回 `42L`。循环结束检查 `box[0]` 是否等于本段迭代数，返回值累加并由
宿主核对期望 checksum；无计数模式的零次预热不会调用循环。A0 从框架参数列表生成参数数组、复制
槽数组，在原方法前后各读取两个槽；A2 使用 `ENTRY_SNAPSHOT`，由 core 在进入时浅复制参数槽，
before/after 均通过快照访问两个槽。两侧读取仅检查 `box` 为 `int[]`、`delta` 为 `Integer`。
observed 模式另在每段核对 before、after、original 均等于迭代数，跨线程计数为零。

类型检查、聚合计数、最终 box 值和独立预检不能证明每次调用都正确保留槽身份，亦不能证明浅快照
对对象共享或槽隔离的真实语义；漏调与重复调用可能抵消。A0/A2 的完整调用包络还包含各自框架、
回调、检查及仪表等成本，差值不能称为纯槽复制成本。现有记录不代表 B03 已完成设备验证。

### B04 参数写回负载

受控目标以 `(3,4)` 相加；before 将第 0 个参数写为 `5`，原方法收到写回参数时返回 `9`。
独立预检的 Hook 下结果和采样 checksum 按 `9` 校验；预检关闭后再次调用，必须返回未拦截的 `7`。
observed 模式的 before 与原方法次数均须等于迭代次数，after 与跨线程次数均为零。聚合 checksum
与计数不证明每一次调用均正确写回，预检 Hook 与测量 Hook 也是独立的；仍需真实框架运行证据。
该包络包含原方法、框架及写入校验工作，不能把控制组差值称为纯写回成本或性能门槛结果。

### B05 单次续行负载

受控目标无参并返回 `42L`。A0 通过框架直接无参 `proceed()`，A2 使用 `AROUND` 调用一次
`proceed()` 并传递结果；A2 的 SDK 路径仍会物化参数。observed 模式中的 `hook_before_calls`
计数 around 入口，`hook_after_calls` 计数正常续行后的退出，`original_calls` 计数目标方法；
三者均须等于迭代次数，跨线程计数为零。这些名称沿用既有日志字段，不代表 B05 安装了独立的
before/after 回调。预检和关闭后返回值均为 42，样本 checksum 为 `42 × 迭代次数`。

聚合计数和 checksum 不能证明每次调用均恰好续行一次；预检与测量 Hook 独立，尚无设备验证。
该负载只覆盖正常返回，不宣称两种路径在异常处理上等价，也不能将包络差值称为纯续行开销。

### B06 四层同步重入负载

外层以深度 `3` 调用目标，目标依次以 `2`、`1`、`0` 同步重入自身，共四层；每层正常返回
`depth + 1`，因此顶层返回 `4`。A0/A2 的 around 续行前保存本层参数，正常返回后重读并检查
本层参数和结果；观测模式的 before/after 字段分别记录 around 进入及正常退出，不是独立的
before/after 回调。每次外层调用预期四次原方法、四次 around 进入和四次正常退出，跨线程为零。
独立预检的 hook_count 为 4，关闭后该数仍为 4；两次顶层结果均为 4，预检及关闭后检查各另执行
4 次目标调用，不计入下述循环预算。样本 checksum 为
`4 × 外层次数`，每段聚合计数和该检查不能证明每次调用、嵌套顺序或所有 frame 独立，且测量
Hook 与预检 Hook 分离。该负载只覆盖正常返回，不代表真实设备验证或性能门槛结果。

## 受控采集入口

仓库中的 `device-benchmark-host` 与独立的 `device-benchmark-module-101` / `device-benchmark-module-102`
提供两代 pilot 入口。它们是与功能 cell 分开的测试应用，默认不安装、不启用，且只有显式传入有效参数
才启动。预先确认设备操作授权、模块管理器配置和测试包的安装状态；仅启用该 pilot 模块并限定其
作用范围至 `io.github.jichuo1.meguviz.hook.benchmarkhost`。采集前记录 APK 哈希与签名，并从启动前
开始保留 `adb logcat -v threadtime` 原始日志；不要清空共享日志。

每侧在新的宿主进程执行。经授权后，可以将下面的占位值替换为独立小写 UUID 和显式参数，再启动：

```text
adb shell am start -n io.github.jichuo1.meguviz.hook.benchmarkhost/.MainActivity \
  --es run_id <RUN_UUID> --es pair_id <PAIR_UUID> --es session_id <SESSION_UUID> \
  --es side A --es workload B01 --es control A0 \
  --ei warmup <WARMUP_CALLS> --ei samples <SAMPLE_COUNT> --ei iterations <CALLS_PER_SAMPLE>
```

另一侧使用同一 pair ID、相同制品和参数、不同 run/session ID 及新进程，`side` 改为 B；实际先后
顺序由采集者记录为 AB 或 BA。每进程只接受一次请求。宿主及模块会检查范围上限，未检测到模块、
预检失败或关闭未报告成功时不会生成完整成功记录。具体次数由操作者显式选择，不是冻结阈值或
推荐样本数。不同控制组分别构成不同的 A/A 配对，不能将 A0 与 A2 误配。

要选择 observed 模式，在启动参数中显式增加 `--es measurement_mode observed`；其记录使用
`measurement_kind=thread_cpu_envelope_observed_ns`。省略该参数或传入 `unobserved` 时仍为原有
`thread_cpu_envelope_ns`。不同模式的制品、配置和结果须分别记录和配对，禁止混合。

## 单侧记录格式

一个 session 是一个 JSON 对象，所有字段均必需，不允许额外字段或重复键。下列摘要是字段说明，
占位符并非可执行证据：

```text
schema_version = 1
record_type = "aa_pilot_session"
data_origin = "device_measurement"             # 仅采集者声明，不由本工具认证
pair_id / run_id / session_id = 独立的标准小写 UUID
side = "A" | "B"
process = {pid: 正整数, start_token: 非空字符串}
thread_id = 正整数
workload = "B01" | "B02" | "B03" | "B04" | "B05" | "B06"
control = "A0" | "A2"
backend = "101" | "102"                     # 所选后端，不自动推断框架代际
config = {warmup: 0..1000000, samples: 1..200, iterations: 1..1000000,
          measurement_kind: "thread_cpu_envelope_ns", pair_order: "AB" | "BA"}
artifacts = {host_apk_sha256, module_apk_sha256, workload_sha256, feature_manifest_sha256}
device = {device_id_sha256, build_fingerprint_sha256, android_api, abi,
          framework_name, framework_version, reported_api}
capture = {raw_log_sha256, format: "threadtime", tag: "MeguvizHookBench", header_pid, header_tid}
preflight = {status, instrumented, observed_return, original_calls, hook_observed, evidence_sha256}
execution_status = "completed" | "failed" | "interrupted"
cleanup_reported = true | false
samples = [{index, iterations, thread_cpu_start_ns, thread_cpu_end_ns, checksum}, ...]
formal_metrics = {callback_cpu_ns: null, task_p95_ns: null, task_p99_ns: null,
                  installation_ms: null, cold_start_ms: null, additional_rss_bytes: null,
                  additional_apk_bytes: null, additional_fd_count: null,
                  additional_thread_count: null, retired_resident_bytes: null}
```

仅 observed 模式另有顶层 `warmup_observation`，包含 `iterations`、`original_calls`、
`hook_before_calls`、`hook_after_calls`、`off_thread_calls`；每个 sample 另含后四项计数。
预热和每个 sample 的原方法与 before 次数，B06 为 `4 × iterations`、其余为 `iterations`；
B01/B03/B05 的 after 为 `iterations`，B06 为 `4 × iterations`，B02/B04 为 0，跨线程计数
必须为 0。即使预热次数为 0 也必须有零计数的预热事件。
这些字段只表明日志记录的计数满足约束，不能独立认证日志来源、设备、框架或 ART 的所有行为。

SHA-256 值均为 64 位小写十六进制。`warmup` 是预热目标调用总次数，`samples` 是样本数，
`iterations` 是每个样本的顶层调用次数；`warmup + samples * iterations` 不得超过 10000000。
B06 的预热和采样循环内，四层目标调用合计不得超过 10000000，即顶层次数不得超过 2500000；
独立预检和关闭后检查不包括在该预算内。
样本从 `index=0` 顺序编号；每项 iterations 与 config 相同。CPU 时间为同一测量线程的非负原始
纳秒值，不能倒退。checksum 为每次返回值累加后模 `2^64`，用 16 位小写十六进制表示。

`preflight.status` 有三种：

- `not_verified`：其余五项全部为 null；结果最多为 `reported_only`。
- `reported_pass`：采集者额外报告预检通过，`instrumented` 为布尔值，`observed_return` 为
  对应负载的预期返回值（42、7、9 或 4），B06 的 `original_calls=4`、其余为 1，`hook_observed=true`，
  `evidence_sha256` 为独立预检证据的 SHA-256。
  这只是采集者声明，本工具不读取或认证该外部预检证据。
- `failed`：不完整，不能计为完成。

模块自带的单独诊断 hook 可报告命中一次和返回值，但未验证原方法次数及正式测量 hook 命中，
因此仅凭该模块预检必须填 `not_verified`。`reported_pass` 需要额外独立证据，不能根据 checksum、
框架返回值或临时诊断 hook 推断。每侧执行完成且 teardown 报告成功也不证明物理释放。

## 原始日志到记录的映射

采集 `logcat -v threadtime` 的原始字节，不清空共享日志；保留原文并计算 SHA-256。
非目标日志行出现不可解码的 UTF-8 字节不妨碍核验；可辨识的目标标签行含非法编码或标签/格式
损坏时必须失败，不能通过忽略损坏事件制造完整记录。原始字节和其哈希不变。
只选定目标进程/线程、同一 run 的事件；同 run 各行前缀 PID/TID 必须与记录及 JSON 的 PID 一致。
工具重新读取两份原始日志并核对其哈希和顺序：

1. `start/start`：共同身份字段及 `warmup/samples/iterations/measurement_kind` 对应 session。
2. `preflight/pass`：仅临时诊断 hook，要求 B06 的 `hook_count=4`、其余为 1，以及对应 `result`、
   `original_count="unverified"`、`hook_scope="separate_preflight"`、
   `measurement_hook_verified=false`、`preflight_restoration="RESTORED"`。
3. `preflight_post_close/pass`：诊断 hook 关闭后再调用目标，要求 `result` 为 42、7 或 4、
   B06 的 `hook_count=4`、其余为 1，以及 `entry_restoration="RESTORED"`；即本次后置调用没有再次触发诊断回调。
   该检查只覆盖一次顶层调用，不证明物理释放或正式采样期间的框架／ART 行为。对 A2，框架入口即使
   仍然残留，已关闭的 SDK channel 也会透传，使诊断计数不再增长；该入口仍可能带来额外开销。
4. `configured/pass`：backend、框架名、版本及 reported_api 与 session 一致；reported_api 可能只是
   库常量，不得用它单独推断框架实际 API 代际。
5. observed 模式在 sample 前输出 `warmup_observation/pass`，按上述规则核对预热次数；按 index
   输出 `sample/pass` 并核对各项计数。无计数模式保持原有顺序和格式。两种模式均核对 iterations、
   CPU 起止及 checksum。
6. `cleanup/pass`：入口恢复声明为 RESTORED、物理释放为 NOT_CLAIMED；来源按 A0 框架卸载合同或
   A2 SDK 关闭报告区分。随后必须有 `end/pass` 且 `cleanup_reported=true`。

相同 run 的任何 fail、丢失、重复、顺序错误或字段矛盾都不能成为完整记录。PID/TID 与日志内容的
一致性不是来源真实性证明。设备 ID 和系统指纹应由采集者读取并脱敏哈希；APK、负载和功能声明的
哈希、进程启动标识、实际框架身份、采集过程与额外预检证据都必须由采集者另外核对并保留。

## 从原始日志生成单侧记录

采集者先提供一个 **外部身份 manifest**，每侧单独填写。下面的占位符必须替换成实际证据，
本示例不是设备测量数据：

```json
{
  "schema_version": 1,
  "data_origin": "device_measurement",
  "backend": "102",
  "process_start_token": "<独立进程启动标识>",
  "pair_order": "AB",
  "artifacts": {
    "host_apk_sha256": "<64位小写SHA-256>",
    "module_apk_sha256": "<64位小写SHA-256>",
    "workload_sha256": "<64位小写SHA-256>",
    "feature_manifest_sha256": "<64位小写SHA-256>"
  },
  "device": {
    "device_id_sha256": "<64位小写SHA-256>",
    "build_fingerprint_sha256": "<64位小写SHA-256>",
    "android_api": 33,
    "abi": "<实际ABI>",
    "framework_name": "<实际框架名>",
    "framework_version": "<实际框架版本>",
    "reported_api": 102
  }
}
```

用明确指定的 run UUID 和原始日志生成新文件：

```text
python tools/performance/build_aa_pilot_session.py raw-a.log \
  --run-id <UUID> --manifest manifest-a.json --output session-a.json
```

工具从原始日志提取 start、样本和 end，计算原文哈希，并使用同一单侧校验器复核所有事件、
PID/TID、字段和顺序；设备、制品、进程启动标识及数据来源仍是采集者提供的信息，不自动猜测或
认证。内建临时诊断 hook 不足以证明测量期间的行为，因此生成记录的 preflight 固定为
`not_verified`，正式指标全部为 null。`written` 仅表示已生成记录，并非设备质量或性能通过。
失败、截断、字段缺失或矛盾时不会生成成功结果；目标已存在时拒绝覆盖，原始日志不会被修改。
独占写入并非原子发布：磁盘错误或进程中断可能留下空文件或部分输出。非零退出或中断时，
不得把留下的目标文件当作成功记录；应保留原始证据并选择新的输出路径重新生成。

## 核对本地制品哈希

对已生成且完整的 session，可以显式指定四个本地文件，核对其读取字节的 SHA-256 是否与记录一致：

```text
python tools/performance/verify_local_artifacts.py session-a.json \
  --host-apk <HOST_APK> --module-apk <MODULE_APK> \
  --workload-file <WORKLOAD_FILE> --feature-manifest <FEATURE_FILE>
```

`workload_sha256` 和 `feature_manifest_sha256` 分别是采集者选择并保存的负载声明文件和功能声明文件的
**原始字节**哈希；工具不会自动选择文件，也不会认证文件内容是否正确描述实际负载或功能。两个 APK
同样只按调用者所给本地文件的字节哈希核对，不检查签名或安装状态。读取前后会检查文件身份、大小和
时间戳是否发生可观察变化；无法获得非零文件标识时拒绝核验。Windows 的句柄状态与路径状态可能对
`ctime` 使用不同语义，因此各自比较读前读后 `ctime`，句柄和路径之间则比较文件标识、大小及修改时间。
这些检查不是防止所有并发修改的锁或来源认证。

四项全部匹配时输出 `local_hashes_match`，不修改任何输入；字段缺失、文件不存在、读取期间观测到变化
或哈希不符均不返回成功。该结果不证明设备身份、设备中已安装的 APK、框架来源或性能通过，
`performance_gate` 始终为 `not_evaluated`。仍需另行保留并核验设备证据和两侧日志、配对记录。

## 两侧配对与命令

两侧须有相同 pair ID、workload、control、backend、完整 config、artifacts 和 device，但各自 run ID、
session ID 和 `(pid, start_token)` 不同。`pair_order` 必须在两侧相同，表示采集者报告的实际顺序；
工具不能仅凭这两个线程 CPU 时钟证明进程间先后顺序。

```text
python tools/performance/verify_aa_pilot.py session-a.json session-b.json \
  --log-one raw-a.log --log-two raw-b.log
```

`--log-one` 与第一个 session 对应，`--log-two` 与第二个对应，输入顺序可以为 B/A。退出码 0 的
`pilot_data_complete` 只表示无计数模式结构和日志一致且采集者额外报告预检通过；
`observed_pilot_data_complete` 表示 observed 模式两侧结构、原始日志与记录的计数一致，二者均不
代表设备/框架真实性或性能达标。退出码 2
包括 `reported_only` 或 `incomplete`；退出码 1 为 `invalid`。所有输出的 `performance_gate` 均为
`not_evaluated`。保留原始负面、失败和中断记录，不应筛除或将其改写成设备通过结果。
