# GATE-001：测量协议草案（未冻结）

唯一 profile 工作副本：`work/performance/performance_profile.json`（`DRAFT_NOT_FROZEN`）。
原始记录结构：`work/performance/raw_schema.json`。本切片**没有**任何性能测量数据。

## 对照组

- **A0**：直接使用所选框架 backend 的等价实现（测封装成本的下界）。
- **A1**：既有等价 facade 的固定测试负载（BIL 现有快路属于基线，不重算收益）。
- **A2**：MeguvizHook 同语义实现。A2−A1 才是封装代价，A3−A2 才是可选优化净值。
- **A3**：独立可选优化，默认不启用；无净收益时允许判定“不值得启用”。

## 协议（GATE-002 前的约束）

1. 先 A/A：同一实现重复测量，估计噪声与系统偏差，再冻结阈值、样本数、设备 cell、ABI、
   manager/artifact 与配置；冻结后才允许 A/B。
2. 正确性优先：原方法重复执行、异常身份改变、语义回归直接判失败，不能用平均速度抵消。
3. 指标：callback CPU、p95/p99、安装延迟、冷启动、RSS、APK 字节、线程/FD、RetiredResident 预算。
4. 归因标签：`source_count` / `design_projection` / `host_model` / `device_measurement` 不混用。
5. 硬约束：普通回调本 SDK 新增 JNI=0、IPC=0；未选 native 新增 so=0；隐式重复原方法=0。

## B01–B10 负载

| ID | 负载 | 主要对照 |
|---|---|---|
| B01 | NO_ARGS before+after（静态无参目标） | A0/A1/A2 |
| B02 | READ_ARGS 两个 primitive 槽读取 | A0/A1/A2 |
| B03 | ENTRY_SNAPSHOT 浅槽快照 | A0/A2 |
| B04 | MUTABLE_ARGS 单次校验写回 | A0/A2 |
| B05 | AROUND 单次 proceed 与结果传递 | A0/A2 |
| B06 | 深度 4 同步重入，独立 frame | A0/A2 |
| B07 | 安装期裁剪/能力/绑定/发布延迟 | A2 |
| B08 | 在途调用期间 close，不重复原方法 | A2 |
| B09 | before/after/original 失败路径（含异常身份） | A0/A2 |
| B10 | 包体、RSS、线程/FD、退休驻留预算 | A0/A2 |

校验证据：`work/evidence/logs/profile-draft-check.log`（draft 通过）、
`profile-frozen-check-expected-reject.log`（未冻结时 frozen 校验按预期拒绝）。
