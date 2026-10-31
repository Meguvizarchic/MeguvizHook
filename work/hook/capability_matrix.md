# 能力矩阵（HOOK-001）

层级说明：源码/编译期事实 ≠ 设备功能 ≠ 设备性能。UNKNOWN 不视为支持。
cell 命名：`jvm-fake`（JVM fake backend）、`libxposed-102-stub`（接口 stub 映射夹具）、
`libxposed-102-device`（真实运行环境，本切片 unavailable）。

| 能力 | fake backend | backend-libxposed-102 | 设备 cell |
|---|---|---|---|
| FRAMEWORK_CHAIN | SUPPORTED_IN_DECLARED_CELL（jvm-fake） | SUPPORTED_IN_DECLARED_CELL（102 Hooker/Chain） | not_run |
| MODULE_LOCAL_CHAIN（core 组合 before/after） | SUPPORTED_IN_DECLARED_CELL | SUPPORTED_IN_DECLARED_CELL | not_run |
| CLASSIC_CALLBACKS | UNSUPPORTED | UNSUPPORTED（102 无独立 classic 回调） | not_run |
| ATOMIC_REPLACE | UNSUPPORTED | SUPPORTED_IN_DECLARED_CELL（102 setId/replaceHook；未实现调用） | not_run |
| HOT_RELOAD | UNSUPPORTED | UNKNOWN（本切片未实现） | not_run |
| MatchPolicy.FIRST / UNIQUE | 已实现（稳定签名序/歧义拒绝） | 同 core | not_run |
| MatchPolicy.ALL | UNSUPPORTED（明确拒绝，后续任务） | 同 core | not_run |
| 非 FRAMEWORK_CHAIN backend dispatch | UNSUPPORTED（明确拒绝） | — | not_run |
| service / 配置 snapshot / owned FD | 未创建模块 | 未创建模块 | unavailable |
| native / Rust / JNI 边缘 | 未创建模块（新增 so=0） | 未创建模块（新增 so=0） | unavailable |
| R8 release 构建 | — | 已执行（见 release_artifact_audit.json） | 构建证据，非运行时 |
| API101 adapter | 未实现 | 未实现（下一批 HOOK-003 剩余部分） | not_run |

结论：本切片只声明 JVM 级合同与 102 适配的**源码/构建级**能力；任何“真机可用/性能达标”
结论都需要对应 device cell 证据，当前一律为空。
