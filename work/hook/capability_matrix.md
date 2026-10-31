# 能力矩阵（HOOK-001 / HOOK-003 / HOOK-005）

层级说明：源码/编译期事实 ≠ 设备功能 ≠ 设备性能。UNKNOWN 不视为支持。
cell：`jvm-fake`、`libxposed-101-stub`、`libxposed-102-stub`；真实设备 cell 均 unavailable。

| 能力 | fake backend | backend-101 | backend-102 | 设备 cell |
|---|---|---|---|---|
| FRAMEWORK_CHAIN | SUPPORTED（jvm-fake） | SUPPORTED_IN_DECLARED_CELL（真实 101 Hooker/Chain 源码 + stub） | SUPPORTED_IN_DECLARED_CELL（102 Hooker/Chain + stub） | not_run |
| MODULE_LOCAL_CHAIN | SUPPORTED | SUPPORTED（同一 core） | SUPPORTED（同一 core） | not_run |
| CLASSIC_CALLBACKS | UNSUPPORTED | UNSUPPORTED（101 也是链模型） | UNSUPPORTED | not_run |
| ATOMIC_REPLACE | UNSUPPORTED | UNSUPPORTED（无 setId/replaceHook） | 源码提供；core 替换流程未实现 | not_run |
| HOT_RELOAD | UNSUPPORTED | UNSUPPORTED（无 102 热重载入口） | UNKNOWN（本切片未实现） | not_run |
| MatchPolicy.FIRST / UNIQUE | 已实现 | 同 core | 同 core | not_run |
| MatchPolicy.ALL | 已实现：逐项回执、部分成功、全失败、epoch 回滚 | 同 core | 同 core | not_run |
| 非 FRAMEWORK_CHAIN dispatch | 明确拒绝 | — | — | not_run |
| service / 配置 snapshot / owned FD | 未创建模块 | 未创建模块 | 未创建模块 | unavailable |
| native / Rust / JNI 边缘 | 未创建模块 | 未创建模块 | 未创建模块 | unavailable |
| R8 release + DEX 定义审计 | — | 已执行 | 已执行 | 构建证据，非运行时 |

结论：本轮具备 JVM 合同、101/102 源码适配与独立 release 构建证据；任何“真机可用/性能达标”
结论仍需要对应 device cell，当前为空。
