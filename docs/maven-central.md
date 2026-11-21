# Maven Central 发布与制品说明

Maven Central 已正式发布 `io.github.jichuo1.meguviz.hook` 的 `1.0.0` 与 `1.2.0` 版本，且 `1.2.0` 全部 8 个制品统一纳入 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 管理。由于 Maven Central 同版本不可覆盖，正式发布后续版本源码前须选择新的未占用版本号、固定源码提交并完成签名与复核（文中 `1.2.1` 等示例版本号仅作本地打包演示）。

> **适用范围与版本边界**：仓库 `main` 分支当前包含除 `hook-dex-bridge` 外的 7 个模块，`hook-dex-bridge` 位于 `1.2.0` 发布分支（已作为第 8 个制品随 `1.2.0` 上线 Maven Central）。API 兼容性检查不能代替真实设备框架与 ART 运行时验证。

发布要求以 Sonatype 官方文档为准：[制品要求](https://central.sonatype.org/publish/requirements/)、[namespace 注册](https://central.sonatype.org/register/namespace/)与 [Bundle 上传](https://central.sonatype.org/publish/publish-portal-upload/)。

## `1.2.0` 已发布制品列表（groupId: `io.github.jichuo1.meguviz.hook`）

| `artifactId` | 制品类型 | 用途 |
| --- | --- | --- |
| `hook-api` | JAR | 公共接口与合同类型 |
| `hook-core` | JAR | 执行核心、生命周期与显式诊断 |
| `hook-dsl` | JAR | 可选 Kotlin DSL |
| `hook-testing` | JAR | JVM `FakeBackend` 与契约测试工具 |
| `hook-ref-bridge` | JAR | 可选 MeguvizRef 目标定位桥（传递依赖 `meguviz-ref-core:1.2.0`） |
| `hook-dex-bridge` | AAR | 可选 DEX 扫描 + Ref 绑定桥（传递依赖 `dexmeguviz-android:1.2.0`、`meguviz-ref-core:1.2.0`；位于 `1.2.0` 发布分支） |
| `backend-libxposed-101` | AAR | libxposed API 101 后端（`compileOnly io.github.libxposed:api:101.0.0`） |
| `backend-libxposed-102` | AAR | libxposed API 102 后端（`compileOnly io.github.libxposed:api:102.0.0`） |

实际 Android 消费方必须按运行框架只选一代后端，并显式将对应的 `io.github.libxposed:api` 声明为 `compileOnly`，不要把两代后端或框架 API 打入同一个应用。

## 从源码生成本地候选包

使用仓库现有的 JDK、Android SDK 与 Gradle Wrapper，在新的独立 staging 目录生成（避免覆盖旧候选）：

```sh
./gradlew \
  :hook-api:publishMavenPublicationToCentralStagingRepository \
  :hook-core:publishMavenPublicationToCentralStagingRepository \
  :hook-dsl:publishMavenPublicationToCentralStagingRepository \
  :hook-testing:publishMavenPublicationToCentralStagingRepository \
  :hook-ref-bridge:publishMavenPublicationToCentralStagingRepository \
  :backend-libxposed-101:publishMavenPublicationToCentralStagingRepository \
  :backend-libxposed-102:publishMavenPublicationToCentralStagingRepository \
  -PmavenVersion=1.2.1 -PcentralStagingDir=build/central-candidate-1.2.1 --no-daemon

python tools/central/make_bundle.py \
  --staging build/central-candidate-1.2.1 --version 1.2.1 \
  --mode unsigned-preview --output build/central-1.2.1-unsigned-preview.zip
```

> 若在包含 `hook-dex-bridge` 的发布分支打包，需一并加入 `:hook-dex-bridge:publishMavenPublicationToCentralStagingRepository`。预览包无签名，不可用于正式上传。`hook-dsl` 的文档 JAR 包含使用指南与合同文档，不含逐符号生成的 Kotlin API 文档。

打包后请按[独立消费者检查](../verification/central-consumer/README.md)分别以 Gradle module metadata 与 Maven POM 模式验证本地 staging 与示例 APK 的 DEX 类隔离。

## 签名与正式打包

1. 发布者须自行确认 Central Portal 对目标 namespace 的权限并管理 PGP 私钥与 agent；严禁将私钥、口令或 Portal token 写入仓库、命令历史或日志。
2. 正式签名时在受控环境传入 `-PsignPublications=true` 重新输出到新的 staging 目录，并与已验证候选逐文件比对（如有字节变化需重新执行消费者校验）。Windows 若使用 Git for Windows 自带 MSYS `gpg`，`GNUPGHOME` 须写成 `/c/...` 形式；原生 Gpg4win 使用原生路径。
3. 导出公钥环文件并校验生成正式 bundle：

```sh
python tools/central/make_bundle.py \
  --staging <已签名 staging 目录> --version 1.2.1 --mode signed \
  --keyring <公开 keyring 文件> --fingerprint <完整指纹> \
  --output <不存在的正式 bundle.zip>
```

`gpgv` 须可执行（不在 `PATH` 时传入 `--gpgv`）。打包器说明见[打包器文档](../tools/central/README.md)。上传前仍需确认包内容、密钥有效性与命名空间权限。
