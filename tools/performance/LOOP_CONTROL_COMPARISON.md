# 实验前冻结后的独立 A0/A2 包络对照合同

`loop_control_comparison.py` 仅提供离线 prepare／verify，没有 execute、ADB 或设备启动入口。
现有全局 `work/performance/performance_profile.json` 仍为 DRAFT_NOT_FROZEN，本工具必须拒绝；
用户认可的建议数值只是草案，代码不读取提案、不填值、不自动冻结。原同控制 A/A 工具保持不变。

## 全局 profile 与独立 loop 冻结工件

必须先由未来 owner 明确填写并冻结实际全局 profile。工具读取该固定路径的原字节，核对完整 PIN，
调用 `check_frozen_profile.check_profile`，只接受 frozen_declaration_structurally_valid 且
evidence_links_checked=true。它仍只是完整声明及直接文件链接检查，ab_authorized 始终 false；
它不证明 A/A 充分、owner 身份、时钟真实性或实际收益。

独立 freeze 严格字段为：

```text
schema_version:1, record_type:"loop_control_comparison_freeze", status:"FROZEN"
freeze_id:<规范UUID>, frozen_utc:<明确时区的ISO时间>
scope:"loop_cpu_envelope_only", formal_gate_enabled:false
profile_pin:<全局profile的{path,bytes,sha256}>
controls:{baseline:"A0",candidate:"A2"}
workloads:<B01/B02的非空无重复子集>, backend:"101"|"102"
parameters:{warmup,samples,iterations,measurement_mode:"observed"|"unobserved"}
device:<完整capture设备/boot/user/framework声明>
artifacts:{host:<完整APK声明>,module:<完整APK声明>}, artifact_audit:<PIN>
workload_sha256, feature_manifest_sha256, fixed_input_manifest_sha256
source_aa_inputs:<全局profile.freeze.baseline_aa_evidence的同一完整PIN>
source_aa_noise_report:<该baseline summary.noise_description的同一完整PIN>
pairs_per_workload:<正偶数，workload数量乘它不得超过32>
analysis_method:"median_pairwise_loop_envelope_fraction_v1"|{method:同字符串,...}
aa_stability_rule:<非空字符串或对象，明确外部声明，不由工具推断>
randomization_policy:<与profile.statistics.randomization_policy完全相同的声明>
thresholds:{minimum_worthwhile_reduction_fraction:<有理数>,maximum_allowed_increase_fraction:<有理数>}
```

两项 loop 阈值须由未来 owner 显式预登记，不能从 callback_cpu、提案或 A/A 最大差自动复制。
每项严格采用 `{numerator:"非负十进制整数",denominator:正整数}`，约分规范、范围 0..1，分母
最多 2^63−1；无默认值。verify 只保留冻结值，不判断门槛通过。analysis_method 与全局 profile
实际值完全一致；字符串无需改成对象。若 method 对象明确另带 aa_stability_rule，则两处还须一致。

全局 statistics.independent_sessions 在本实验中明确解释为“每负载、每控制组的新进程会话数”，
等于 pairs_per_workload；两负载各16对合法，共32对／64进程，不会减半。新进程不等于统计独立，
时间相关尚未证明。samples_per_session 等于参数 samples；本工具支持的 warmup_policy 显式为
`{warmup,iterations,measurement_mode}`。device_cells 必须包含同一完整设备声明；选中 workload
必须在全局 profile 中同时声明 A0/A2，所选 backend 必须在 release_profiles。workload 的
device_cells 非空时也必须包含该设备，空列表表示沿用全局设备范围。

source A/A 只绑定该全局 baseline。其 noise 所选组须覆盖选中 workload×A0/A2，且参数、mode、
APK/workload/feature/旧 session 设备字段一致，不能有零样本或 unresolved；合法未选 workload
可跳过，非法控制组仍拒绝。boot/user 从 baseline 逐侧已 pin capture 核对；noise pair_id 集合
须等于这些 capture 的 pair_id 集合，A/B 的 side 与共同 pair_id 一致。上述检查仍只核生产者
声明与原字节关联，不重算源 A/A 原始 pairs，不认证充分性或统计资格。逐侧原 A/A plan 与
capture 的 SHA／身份必须相符，plan.created_utc 不得晚于 profile.freeze.timestamp；该关系只核
预登记时间声明，不能证明 A/A 实际采集完成在冻结前。未选择的合法 B03–B06 组可以跳过。

## 对照计划

```text
python -B tools/performance/loop_control_comparison.py prepare --root <ROOT>
  --freeze <FREEZE_JSON> --freeze-sha256 <外部SHA>
  --inputs <INPUTS_JSON> --inputs-sha256 <外部SHA> --output <全新目录>
```

inputs 严格为 `{schema_version:1,record_type:"loop_control_comparison_inputs",randomization_policy,pairs}`。
pairs 每行是 `{position,workload,order:"AB"|"BA",plans:{A:PIN,B:PIN}}`；A 固定为 A0 基线，B 固定为
A2 候选，顺序另外明确。每负载 AB／BA 各半；side plan 必须是原 collector 的规范计划、同输入、
不同 run/session／进程、共同 pair/clock，且在 freeze 后创建、尚无 execution。prepare 固定所有
实际导入项目工具源码，不借用 same-control verify_pair 比较不同控制。

对照 plan 的 record_type 为 loop_control_comparison_plan，状态 prepared_after_freeze，绑定
freeze、profile、inputs 原 PIN、全部 side plan PIN、发布路径和工具源码 PIN。冻结工件、全局
profile 或工具漂移均拒绝。此步骤不会启动实验，也不授权正式 GATE A/B。

## 保存数据后的离线核验

```text
python -B tools/performance/loop_control_comparison.py verify --root <ROOT>
  --plan <PLAN_JSON> --plan-sha256 <外部SHA>
  --evidence <EVIDENCE_JSON> --evidence-sha256 <外部SHA>
```

evidence 为 `{schema_version:1,record_type:"loop_control_comparison_evidence",comparison_plan_sha256,
claim:PIN,pairs:[{position,sides:{A:<side pins>,B:<side pins>}}]}`。每侧 pins 为 capture/result/owned_stop/
raw/session/session_manifest/receipt 七项完整 PIN；原 side plan 从对照 plan 获取。逐侧调用原
`verify_loop_pilot_pair._side` 重建 raw session，保持 preflight=not_verified，不让 reported_only 升格。

必须有未来独立执行器生成的 `loop_control_comparison_claim`：schema_version、comparison_id、
comparison_plan_sha256、freeze_sha256、profile_sha256、created_utc、host_clock_id、
freeze_checked_monotonic_ns、profile_checked_monotonic_ns、claim_monotonic_ns、no_retry=true。
逐侧 receipt 为 loop_control_comparison_side_receipt，绑定上述三SHA和claim_sha256、side_plan_sha256、
run_id/session_id/side/control/host_clock_id/host_clock_token，以及 freeze_checked_monotonic_ns、
profile_checked_monotonic_ns、gate_monotonic_ns、collector_return_monotonic_ns；两种记录都含
schema_version=1。没有这些原始关联证据不能拿历史 capture 事后套 freeze。

时间矛盾检查为：profile.freeze.timestamp ≤ freeze.frozen_utc ≤ side plan.created_utc ≤ comparison
plan.created_utc ≤ claim.created_utc；时区统一转换UTC，不能用mtime代替。主机同一声明时钟下，
冻结/profile检查 ≤ claim，前侧 collector_return ≤ 后侧重新检查 ≤ gate ≤ launch < terminal ≤ return。
原 capture 必须已有 stop 确认，设备/boot/user/UID一致，进程 start ticks 与登记顺序一致。线程 CPU
时钟仅在本线程内相减，不能跨进程比较绝对值。UTC与receipt仍是生产者声明，不独立证明历史冻结
先后或认证时钟；本轮未提供真实执行器，无法由此自行启动 A0/A2。

每进程点估计为 median((cpu_end−cpu_start)/顶层iterations)，每对报告 A2−A0 和 A2/A0−1 的规范
精确分数，并按负载取配对效果中位数。全部有符号差、原样本和零值保留；任意零样本使该对及该组
比率 unresolved，不丢掉零值后重算。raw每侧最多16MiB、全实验累计最多128MiB。

输出仅 comparison_descriptive_only 或 unresolved；保留两项 frozen_loop_thresholds，
confidence_interval、threshold_evaluation、benefit_conclusion、performance_gate 全为 not_evaluated，
ab_authorized=false、release_qualified=false，正式 callback_cpu 等指标全部 null。这是完整循环
线程 CPU 包络点估计，不能叫纯 callback CPU、SDK 净成本、已建立提升或正式性能资格。

测试包含一个全新临时工作区中的 synthetic 正向夹具：自行构造冻结声明与对应 A/A 来源，
创建 B01 两对 AB／BA 的四侧 plan／原日志／session／capture／stop／claim／receipt，调用实际
prepare 后再 verify；它不运行 executor、ADB 或设备，不修改真实 profile，不是设备性能证据。
