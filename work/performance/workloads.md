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

## INT-011：BIL 只读消费夹具与 A0/A1/A2 对照设计

机器可读夹具：`work/integration/bil_readonly_consumer_fixture.json`。上游固定为 BIL `dba9ed9`，
5 个相关源码均记录 SHA-256；BIL 未修改，夹具和设计均为 `not_run`，不是性能数据。

- B01：A0 直接 backend、A1 `ModernMemberHookCreator`、A2 `NO_ARGS` 均为空 before/after，
  原方法恰好一次且返回 42L；禁止参数访问、日志、配置和业务工作。
- B02：A0 读取 `chain.args[0/1]`，A1 读取 `argOrNull(0/1)`，A2 以 `READ_ARGS` 读取两槽；
  输入 3、4，返回 7，原方法恰好一次且参数/结果不变。
- A1 的 lazy/no-copy、常量快路、配置 snapshot 和成员缓存均为 BIL 基线，不计作 A2 新收益；
  常量 replacement 和安装期配置不得混入上述 callback CPU 对照。
- BIL 默认 `PROTECT_HOST` 与 SDK `PROPAGATE` 不同，因此这里只对无异常 B01/B02 声明同语义；
  B03–B10 不自动获得 A1 对照，异常路径不得用来宣称性能等价。
- A1 facade 是 BIL internal 实现，未来执行需 BIL 自有只读 adapter/cell；本任务不复制其源码、
  不创建 BIL 开发任务。A/A、设备 cell 和阈值仍未执行/冻结，GATE-002 保持 blocked。

夹具源码与结构校验：`work/evidence/logs/round7-int011-fixture-check.log`。
