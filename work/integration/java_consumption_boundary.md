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
| 框架类型出现位置 | 仅 `backend-libxposed-102` 实现内部 |

## 2. Java 独立消费验证

- `hook-api`、`hook-core` 以 Java 8 字节码构建，不依赖 Android。
- `sample-java-contracts` 在纯 JVM 上以 Java 消费并运行成功：输出
  `SAMPLE_OK backend=fake-chain close=CLOSED`（日志 `full-build.log`），证明 Java 路径不需要
  Kotlin DSL、KSP、协程或 Android 运行时。
- `sample-module-102` 使用手工 Java 入口（`XposedModule` 子类 + `java_init.list`），
  不依赖生成器。

## 3. 互斥与装配

- 当前只存在 `backend-libxposed-102`；`releaseRuntimeClasspath` 不含 libxposed、不含
  kotlin-stdlib、不含第二 backend。101 加入后必须维持同一 configuration 单 backend。
- `testImplementation libs.libxposed.api` 只用于 stub 映射夹具，不进入任何发布产物。

## 4. 默认关闭项核查

无 service、native、reload、trace、codegen、doctor 模块与初始化代码；诊断默认 `NONE`；
APK 无 `.so`、无框架类。普通回调路径在源码层面无 JNI/IPC 调用点（热路径仅读 `BoundPlan`
冻结字段并执行业务回调）。

## 5. 未覆盖

二进制兼容（旧 consumer 链接新库）、ABI 检查工具、lint、真实 101/102 运行时：均为后续任务，
本切片不声明通过。
