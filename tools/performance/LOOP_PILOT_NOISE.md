# 循环 pilot 的 A/A 噪声描述

`analyze_pilot_aa_noise.py` 只处理同一 workload、control、backend、模式、参数、制品和设备声明的
A/A 原始记录。每对先通过 `verify_aa_pilot.verify_pilot` 校验 session 与原日志，再按这些身份分组；
`AB` 与 `BA` 保留并分别计数。不同控制组和观测模式不会合并，也不生成 A0/A2 性能结论。

输入是调用者明确创建且不覆盖旧文件的 JSON，最多 256 对、总输入 128 MiB：

```json
{
  "schema_version": 1,
  "record_type": "pilot_aa_noise_inputs",
  "pairs": [{
    "left": {"path": "relative/session-A.json", "sha256": "<sha256>"},
    "right": {"path": "relative/session-B.json", "sha256": "<sha256>"},
    "left_log": {"path": "relative/raw-A.log", "sha256": "<sha256>"},
    "right_log": {"path": "relative/raw-B.log", "sha256": "<sha256>"}
  }]
}
```

所有路径必须位于指定工作区内，文件必须稳定读取且与 SHA 一致；重复 pair/run/session ID、
不匹配的配置、已损坏原日志及所选运行中带非有限数的 JSON 均拒绝。执行后 JSON 输出到标准输出：
所选运行若声明结果未知、超时，或混入其他负载的 `failure_case`，也会拒绝。

```text
python -B tools/performance/analyze_pilot_aa_noise.py --root <WORKSPACE> \
  --manifest <INPUT_JSON> --manifest-sha256 <INPUT_SHA256>
```

每侧保留所有 `(thread_cpu_end_ns - thread_cpu_start_ns) / iterations` 值及中位数，数字以
有符号、无精度损失的分子/分母字符串表示；这仍是完整 loop 的线程 CPU 包络，不是逐次调用
延迟或纯 callback CPU。样本不少于 10 时另比较互不重叠的首五与末五中位数。单侧任一零样本
保留，整对相对比例为 `null/zero_sample_unresolved`；首五中位数为零时漂移比例同样未决。
组级的中位数和范围仅覆盖可计算比例，并报告未决数，不过滤原始对次。

设备、进程启动、执行顺序及预检仍是采集者声明；`reported_only` 原样保留，不能升级为设备
认证、噪声合格或性能资格。工具不输出阈值、不冻结 profile、不将 A0 与 A2 作 A/B 比较，
正式性能字段均保持 null；如需更强的 owned stop/时间与设备边界，还须另用受控采集与配对记录。
