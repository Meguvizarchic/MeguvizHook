# ADR-0002：模块职责、可拆装配与 Java 消费边界（INT-002）

状态：Accepted（2026-10-31）
实现边界：HOOK-002 拥有公开 API；本 ADR 只约束装配与消费边界，不重复定义 API。

## 1. 依赖方向

```
hook-api  ←  hook-core  ←  backend-libxposed-101  ←  sample-module-101
   ↑            ↑  ├────  backend-libxposed-102  ←  sample-module-102
   ↑            ↑  ↑              (各代仅 compileOnly 对应 io.github.libxposed:api)
   │            │  └────  hook-testing  ←  sample-java-contracts
   └────────────┴───────  (hook-testing 依赖 api+core，测试用，不是真实后端)
```

- 编译依赖单向；运行时 backend 通过 `core.spi.HookBackend` 反向被 `HookEnvironment` 调用，
  不构成编译环。`hook-core` 不依赖任何 backend，`hook-testing` 不进 consumer APK。
- `hook-dsl` 独立可选，只依赖 api+core；`sample-kotlin-contracts` 仅直接选择 dsl+testing，
  证明公开传递依赖。现有 Java/backend/Android sample 不依赖它，Kotlin 运行时不得渗入 Java-only variant。
- 未创建的模块（service/codegen/doctor/native/bridge）不进入 settings，不留空壳。
  后续添加时各自独立可选，不被 BOM 或默认装配强拉。

## 2. 互斥装配

- 一个 consumer variant 只选择一个 backend 制品。`sample-module-101` 仅依赖 backend-101，
  `sample-module-102` 仅依赖 backend-102；两个 artifact 不得同时出现在同一 configuration。
- 两版官方 API 同 FQCN，禁止同时进入编译类路径；`sample-module-101/102` 是分别验证的独立产物，
  不做单 APK 双代 bootstrap（后续独立资格）。101 产品不得引用 102 的 setId/replaceHook/热重载类型。
- 框架 API 一律 `compileOnly`：不得打包进 AAR/APK。验证方式：解析依赖图 + 检查最终 APK 条目
  （见 `work/hook/release_artifact_audit.json` 的实际检查结果）。
- 单测例外：`testImplementation libs.libxposed.api` 只进入测试类路径，用于 stub 映射夹具，
  与发布产物无关。

## 3. 默认预算与关闭行为

| 未选择的功能 | 默认行为 |
|---|---|
| native / Rust | 无对应模块、无 `.so`、无 JNI 注册（本仓库该切片不含 native 源码） |
| service | 不连接、不反射私有 IBinder、不创建会话 |
| reload / trace / codegen / doctor | 不存在，不初始化 |
| 诊断 | `DiagnosticsSink.NONE`；显式选择时使用有界实现 |
| 线程/IPC | 普通回调路径本 SDK 新增 0；框架自身边界不计入“新增” |

这些是结构约束。实证项（APK 内容检查）见审计 JSON；性能数值一律不在本切片声明。

## 4. Java/Kotlin 稳定消费边界

- `hook-api`/`hook-core` 为 Java 8 字节码，纯 JVM 可消费（`sample-java-contracts` 证明）。
- `hook-dsl` 的 api/core 公开传递依赖由独立 `sample-kotlin-contracts` 编译与运行证明。
- Android 模块使用 Java 17 语言级，与 BIL 一致；不改变 api/core 的字节码目标。
- Kotlin DSL、KSP、协程不是 Java 消费的前置。
- 回退：任何新公开面若需收缩，在 0.x 阶段以 ADR 撤回并保留旧制品；不在运行中切换 backend
  重放已发生的副作用。
