# 已登记循环 A/A 批次的单次执行

`collect_loop_pilot_batch.py` 执行既有 `loop_pilot_aa_offline_batch_plan`，包括 round49 的 16 对／32 侧。
只接受同控制 A/A：两种已支持 workload × A0／A2 四组，2 或 4 个区组；每区组四组各一对，每组
AB／BA 各半，最多 16 对、32 侧。不做跨控制 A0/A2 配对，不修改预登记计划或自动补跑。

## 先离线检查

```text
python -B tools/performance/collect_loop_pilot_batch.py --dry-run \
  --root <工作区> --batch <batch-plan.json> --batch-sha256 <独立保存的批SHA> \
  --wrapper-sha256 <collect_loop_pilot_batch.py的独立固定SHA> \
  --pair-helper-sha256 <verify_b10_pair.py的独立固定SHA>
```

dry-run 只读本机文件，不创建 claim、transport、ADB 或设备进程。省略 --execute 也只做 dry-run。
工具复核每侧 `collector.check_plan`、原 pair.validate_plan、所有实际导入源码和制品／审计／ADB PIN，
以及 batch 自带的 pair_verifier／noise_analyzer 原件与实际加载字节。新增两个外部 SHA 补齐原 batch
尚未包含的本 wrapper 和 verify_b10_pair 依赖。不是把现场计算的摘要自动视为独立固定值。

批次路径、每侧路径、顺序、group、共享 pair／clock、全局独立 run／session，以及参数、serial／
框架预期、全部固定输入必须一致。每个计划必须在批次目录下，无 execution；固定 batch-execution
目录也必须不存在。现存 claim 或中断目录不允许换输出目录绕过重试边界。

## 预先固定只读预检查证据

在已协调设备空闲、范围确认后，由单独只读预检保存并独立固定 JSON 的 SHA，至少包含：

```text
schema: 1
record_type: "loop_pilot_batch_preflight"
batch_sha256: <同一预登记批次SHA>
read_only: true
host_process_absent: true
host_and_module:
  device: {device_id_sha256, build_fingerprint_sha256, boot_id, android_api, abi, android_user:0}
  host:   {package, path, stat, sha256, uid}
  module: {package, path, stat, sha256, uid}
battery_deci_celsius: <0..420的整数>
framework_sha256: <预检时读取框架文件的SHA256>
```

device 使用 common.target_state 实际的六项字段；host/module 为当前安装的受控签名 APK，包含
安全 base.apk 路径、原 stat 的设备号／inode／字节数、字节 SHA、PM UID。旧包、其他 boot、
其他 UID 或不同安装位置不能自动兼容。framework_sha256 固定该份预检声明，本 wrapper 不新增
自由框架路径读取，也不将该摘要当成每次回调都加载了该框架的独立认证。可额外保留原 transport
引用与采集说明，整个 preflight 原文件仍通过 SHA 固定。

## 明确执行

```text
python -B tools/performance/collect_loop_pilot_batch.py --execute \
  --root <工作区> --batch <batch-plan.json> --batch-sha256 <批SHA> \
  --wrapper-sha256 <wrapperSHA> --pair-helper-sha256 <helperSHA> \
  --serial <明确唯一设备> --preflight <只读预检JSON> --preflight-sha256 <预检SHA> \
  --idle-confirmed --scope-confirmed
```

两项确认只是调用方声明，不是授权来源。首个 ADB 前完成所有离线检查、适用上限、标识与工具
PIN 检查，并独占创建固定 `batch-execution/claim.json`，记录原批／预检／源码 PIN、步骤、T0 和
无重试策略。长生命周期进程按 batch 的 position，再按每对 order，逐侧调用原 collector.execute。
每侧前重新核对批原文、全部计划原文、预检原文与实际加载工具。

本脚本在该进程中临时包装 collect_b07.target_state 和 observe_power，finally 恢复原绑定：每次
设备／安装边界必须与 pinned preflight 完全一致；每次 before／after power 先运行原有超过 42°C
及单侧升温规则，再与整个批次 T0 比较，升温达到 5°C 即抛错。若此时已有 owned 进程，由原
单侧采集器按身份守卫收尾一次。claim 明确记录 wrapper 来源；源码 PIN 不认证全部运行内存。

只使用明确 serial，不安装、更新、卸载应用，不修改模块范围、其他应用或其他设备，不清数据、
profile、缓存或日志。未知启动／停止、poison、异常、未确认 stop 或非预期结果立即停批。
无计数 `reported_only` 是正常完整的采集状态，继续保留 not_verified；它不会升级为性能通过。
observed 的正常单侧结果是 observed_session_consistent。任一零 CPU 样本、unresolved、缺失或矛盾
都会停批，原零值不删除，不制造后续比例。

停止确认后，批工具重新读取该侧原 plan/capture/result/owned-stop/raw/session/session-manifest，
调用原单侧 bundle 校验；每对再次调用原 verify_loop_pilot_pair。只有同控制 reported_only 或
observed_pilot_data_complete 才继续。全批完成后，原 noise analyzer 只描述各组噪声；原始输入
累计上限为 128 MiB，各侧 raw 上限仍为 16 MiB。

batch-execution 保留 claim、每侧 attempt 和原单侧核验、逐对 report、最终 result、噪声描述；
单侧原件保留在其原 execution 下。最终 result 明确列出未执行项；失败、中断或写入错误可能
留下部分目录，不能删除后重跑或将文件存在解释为成功。batch_records_collected 只表示登记记录
收集完成；batch_stopped 表示立停。CLI 对离线检查／收集完成退出 0，其他退出 1。

性能门槛、噪声资格和设备认证始终 not_evaluated／not_verified。不冻结阈值、不评定稳定性或
置信区间，不把完整循环线程 CPU 包络称为纯 callback CPU，也不声称 SDK 相对原生 API 的收益。
