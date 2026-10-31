# INT-001：集成环境与真实构建任务核实

本文件消费 INIT-001/002 的骨架与版本锁，不重复初始化工程。所有条目来自实际执行。

## 1. 已验证矩阵

| 组件 | 版本/状态 | 证据 |
|---|---|---|
| JDK | Azul Zulu 20+36（本环境唯一 JDK；BIL CI 用 temurin 27，未对齐，见偏差） | `java -version`，构建日志 |
| Gradle | 9.8.2，发行版 SHA-256 与 BIL wrapper 锁定值一致 | `work/evidence/logs/gradle-tasks-all.log` |
| AGP | 9.4.1（`com.android.library`/`application`） | 构建日志、release 产物 |
| Android platform | `platforms;android-37.2`，compileSdk 37 minor 2（BIL `dba9ed9`） | `source.properties`、第四轮 release 构建 |
| Build tools | 37.0.0 | SDK 安装记录 |
| Kotlin | JVM DSL 使用 KGP 2.4.21 / JVM 1.8；Android Kotlin102 使用 AGP 内建 Kotlin / JVM 17 | Kotlin102 选择 stdlib/DSL；Java-only release 排除 stdlib/metadata |
| libxposed api | 102.0.0（BIL 默认）+ 101.0.0（独立 backend）；均 compileOnly / 对应 stub 测试 | AAR SHA-256 见 baseline_inventory.json / baseline.json |
| NDK/Rust/KSP | 未选择，未安装要求 | 无对应模块 |
| 设备/模拟器 | 无 | 设备 cell 全部 not_run |

## 2. 真实执行的任务（非推测）

```text
gradle -p . tasks --all                                   -> rc=0，672 行任务清单
gradle -p . :hook-testing:test :sample-java-contracts:run \
    :backend-libxposed-102:testDebugUnitTest :sample-module-102:assembleRelease  -> rc=0，59 tests，0 failure
gradle -p . :backend-libxposed-102:assembleRelease \
    :sample-module-102:assembleRelease                                           -> rc=0，AAR/APK 产出
gradle -p . :sample-module-102:dependencies \
    --configuration releaseRuntimeClasspath                                      -> rc=0，无 libxposed/kotlin-stdlib
javap -public -cp <module jar> <each public class>                               -> work/hook/api_dump_hook-{api,core}.txt

# 第二轮
gradle -p . :hook-testing:test :sample-java-contracts:run \
    :backend-libxposed-101:testDebugUnitTest :backend-libxposed-102:testDebugUnitTest \
    --no-daemon --console=plain                                                   -> rc=0，67 tests，0 failure
# 加入 RETIRING 并发夹具后的最终复跑（另含双代 assembleRelease）
gradle -p . :hook-testing:test :sample-java-contracts:run \
    :backend-libxposed-101:testDebugUnitTest :backend-libxposed-102:testDebugUnitTest \
    :backend-libxposed-101:assembleRelease :backend-libxposed-102:assembleRelease \
    :sample-module-101:assembleRelease :sample-module-102:assembleRelease \
    --no-daemon --console=plain                                                   -> rc=0，68 tests，0 failure
gradle -p . :backend-libxposed-101:assembleRelease \
    :backend-libxposed-102:assembleRelease :sample-module-101:assembleRelease \
    :sample-module-102:assembleRelease --no-daemon --console=plain               -> rc=0，双 AAR/APK
gradle -p . :sample-module-101:dependencies \
    --configuration releaseRuntimeClasspath                                      -> rc=0，仅 backend-101
gradle -p . :sample-module-102:dependencies \
    --configuration releaseRuntimeClasspath                                      -> rc=0，仅 backend-102
apkanalyzer dex packages [--defined-only] <101/102 release APK>                  -> DEX 定义/引用审计通过

# 第三轮（JDK 17）
gradle -p . :hook-dsl:test :hook-testing:test :sample-java-contracts:run \
    :backend-libxposed-101:testDebugUnitTest :backend-libxposed-102:testDebugUnitTest \
    :backend-libxposed-101:assembleRelease :backend-libxposed-102:assembleRelease \
    :sample-module-101:assembleRelease :sample-module-102:assembleRelease \
    --no-daemon --console=plain                                                   -> rc=0，80 tests，0 failure，双 AAR/APK
javap -public -cp <hook-dsl jar> <each public class>                               -> work/hook/api_dump_hook-dsl.txt，major=52
# 本机无 apkanalyzer，使用 build-tools 37.0.0 dexdump 对 DEX 定义和引用分别复核
# 初版定义检查误匹配类型引用；按 Class descriptor 行修正后全部通过
```

日志：`work/evidence/logs/`。首次失败记录（wrapper 下载超时、Kotlin DSL 扩展、ThreadDeath lint、
测试夹具缺陷）保留在 `jvm-test-first.log`、`jvm-test.log`、`full-build.log` 的演进过程中。

## 3. 环境事实与修复

- 预置全局 `~/.gradle/init.gradle` 存在语法错误（未引用 URL、拼写错误的 `mavelCentral`），
  且与 `FAIL_ON_PROJECT_REPOS` 冲突；已备份为 `init.gradle.bak.meguvizhook` 后停用，构建仅使用
  settings 中声明的仓库。
- `services.gradle.org` 在本环境 10s 内不可达；Gradle 9.8.2 发行版改由镜像下载并用 BIL 的
  SHA-256 校验后安装到 `$GRADLE_HOME`。仓库内 wrapper 配置与 BIL 完全一致，常规网络可直接使用。
- JDK 27 两次下载失败；本切片全部构建在 JDK 20 完成。JDK 27 一致性仍是未验证项。
- Android SDK 的平台包名是 `android-37.0`（非 `android-37`），需 channel≥1 的仓库索引。

## 4. 差异与未决

- 本地曾用 JDK 20/17；第八轮 CI run 41208912796 已在 Temurin 27 完成测试、apiCheck、
  完整 lint 与全部 release，JDK 27 parity 通过。旧 consumer 二进制链接兼容仍未验证。
- AGP 内建 Kotlin 默认注入 `kotlin-stdlib` 与 `kotlin/**` 元数据：Java-only variants 仅在
  releaseRuntimeClasspath 排除 stdlib，并保留 APK metadata excludes；Kotlin102 显式选择二者。
- 第二轮纠正：真实 101 源码同样是 Hooker/Chain，不新增 classic SPI；101 不使用 102 的 setId/replaceHook。
- 一次依赖报告把 `--configuration` 与两个 dependencies task 同用，导致其中一个输出全配置；已拆成
  `round2-sample-101-release-runtime-deps.log` 与 `round2-sample-102-release-runtime-deps.log` 重新核实。
- DEX 原始字节包含诊断文案 setId/replaceHook，曾被宽扫描误报；后续均按定义行与实际引用行分别审计。
- 完整 `lint` 已通过；未执行 instrumented test、签名打包、真实 101/102 框架装载。
