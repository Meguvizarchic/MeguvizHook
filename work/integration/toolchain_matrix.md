# INT-001：集成环境与真实构建任务核实

本文件消费 INIT-001/002 的骨架与版本锁，不重复初始化工程。所有条目来自实际执行。

## 1. 已验证矩阵

| 组件 | 版本/状态 | 证据 |
|---|---|---|
| JDK | Azul Zulu 20+36（本环境唯一 JDK；BIL CI 用 temurin 27，未对齐，见偏差） | `java -version`，构建日志 |
| Gradle | 9.8.2，发行版 SHA-256 与 BIL wrapper 锁定值一致 | `work/evidence/logs/gradle-tasks-all.log` |
| AGP | 9.4.1（`com.android.library`/`application`） | 构建日志、release 产物 |
| Android platform | `platforms;android-37.0`，ApiLevel=37.0，revision 2 | `source.properties` |
| Build tools | 37.0.0 | SDK 安装记录 |
| Kotlin | 未使用；`android.builtInKotlin=false` | 运行时依赖图无 kotlin-stdlib |
| libxposed api | 102.0.0（compileOnly / testImplementation） | AAR SHA-256 见 baseline_inventory.json |
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

- JDK 20 vs BIL CI 27：字节码目标（api/core=8，Android=17）已固定，但 JDK 27 的编译/R8 一致性未验证。
- AGP 内建 Kotlin 默认注入 `kotlin-stdlib` 与 `kotlin/**` 元数据：本 Java-only 切片用
  `android.builtInKotlin=false` + packaging excludes 消除，已复查依赖图与 APK 条目。
- 未执行：`lint`、instrumented test、签名打包、真实框架装载。
