# INT-002：Java 消费编译/签名边界报告

对象：HOOK-002 产出的公开 API（本报告不重复实现 API）。

## 1. 签名审计（基于实际字节码）

证据：`work/hook/api_dump_hook-api.txt`（383 行）、`work/hook/api_dump_hook-core.txt`（170 行），
由 `javap -public` 对构建产物逐类生成。

| 检查 | 结果 |
|---|---|
| 公开签名包含 libxposed 类型 | 无（dump 全文检索 0 命中） |
| 包含 `android.*` / `androidx.*` 类型 | 无 |
| 包含 Binder / JNI / Rust / Kotlin 类型 | 无 |
| 包含其他 Meguviz 家族类型 | 无 |
| 框架类型出现位置 | 仅 `backend-libxposed-101` / `backend-libxposed-102` 各自实现内部 |

## 2. Java 独立消费验证

- `hook-api`、`hook-core` 以 Java 8 字节码构建，不依赖 Android。
- `sample-java-contracts` 在纯 JVM 上以 Java 消费并运行成功：输出
  `SAMPLE_OK backend=fake-chain close=CLOSED`（日志 `round2-jvm-tests.log`），证明 Java 路径不需要
  Kotlin DSL、KSP、协程或 Android 运行时。
- `sample-module-101` 与 `sample-module-102` 都使用手工 Java 入口（`XposedModule` 子类 +
  `java_init.list`），不依赖生成器；R8 DEX 中均审计到对应入口类定义。
- `MatchPolicy.ALL` 新增 `TargetInstallReceipt` 与 `PARTIALLY_INSTALLED`，保持纯 Java 公开合同；
  `hook-api`/`hook-core` API dump 无框架类型泄漏。

## 3. 互斥与装配

- 两个 sample 的 `releaseRuntimeClasspath` 分别只含所选 backend；均不含 libxposed、
  kotlin-stdlib 或另一代 backend。DEX/AAR 也无框架类定义，101 无 102 专有 API 引用。
- `testImplementation` 中对应代际的 libxposed api 只用于 stub 映射夹具，不进入任何发布产物。

## 4. 默认关闭项核查

无 service、native、reload、trace、codegen、doctor 模块与初始化代码；诊断默认 `NONE`；
APK 无 `.so`、无框架类。普通回调路径在源码层面无 JNI/IPC 调用点（热路径仅读 `BoundPlan`
冻结字段并执行业务回调）。

## 5. 未覆盖

二进制兼容（旧 consumer 链接新库）、ABI 检查工具、lint、真实 101/102 运行时：均为后续任务，
本切片不声明通过。
