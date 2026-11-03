# Maven Central 发布准备

Maven Central 已有 `io.github.jichuo1.meguviz.hook` 的 `1.0.0` 制品。该版本不可覆盖，
也不包含当前源码的后续更改。正式发布当前代码前，须选择尚未占用的新版本、固定源码并
重建、复核与签名；下方的 `1.0.1` 仅为本地命令示例，不代表已经选择或发布该版本。
API 兼容性检查不能代替运行时、框架及设备兼容性验证；`HookEnvironment.close()` 的
生命周期合同尤其需要结合调用方同步方式评估。
这里的暂存、消费检查和打包均在本地执行；没有自动上传、
创建远端部署或发布。版本一旦在 Central 正式发布便不可覆盖，请在发布前固定源码提交，
确认目标坐标未被占用，并完成项目自己的设备、框架及性能资格判断。

源码中的七组件配置不代表这些源码已签名或发布，也不能证明真实框架、ART 或设备运行兼容。

发布要求以 Sonatype 官方文档为准：[制品要求](https://central.sonatype.org/publish/requirements/)、
[namespace 注册](https://central.sonatype.org/register/namespace/)和
[Bundle 上传](https://central.sonatype.org/publish/publish-portal-upload/)。

新的本地候选配置 group 为 `io.github.jichuo1.meguviz.hook`，包含以下七个独立组件；
这不表示第七组件或当前源码已发布到 Central：

| artifactId | 制品 | 用途 |
| --- | --- | --- |
| `hook-api` | JAR | API 和合同类型 |
| `hook-core` | JAR | 执行核心和显式诊断 |
| `hook-dsl` | JAR | 可选 Kotlin DSL |
| `hook-testing` | JAR | JVM fake backend 和测试工具 |
| `hook-ref-bridge` | JAR | 可选 MeguvizRef 目标定位桥 |
| `backend-libxposed-101` | AAR | API101 后端 |
| `backend-libxposed-102` | AAR | API102 后端 |

两个后端分别使用对应的 `io.github.libxposed:api:101.0.0` 或 `102.0.0` 作为
`compileOnly`。实际 Android 消费者也须显式声明与运行框架匹配的一代 API 为
`compileOnly`；不要把两代后端或框架类一起打入应用。DSL 使用 Kotlin 标准库；可选桥
将已授权的 `io.github.jichuo1.meguviz:meguviz-ref-core:1.0.0` 声明为传递 API 依赖，
使用者仍须核对自身 Java／Android 运行环境。该依赖不进入其他组件的默认依赖图。

## 生成本地候选

使用仓库现有的 JDK、Android SDK 与 Gradle wrapper。下面的 staging 目录应为新目录；
不要覆盖需要保留的旧候选。常规构建版本默认仍是 `0.1.0-SNAPSHOT`，只有显式传入
`mavenVersion` 才切换发布候选版本。

```sh
./gradlew \
  :hook-api:publishMavenPublicationToCentralStagingRepository \
  :hook-core:publishMavenPublicationToCentralStagingRepository \
  :hook-dsl:publishMavenPublicationToCentralStagingRepository \
  :hook-testing:publishMavenPublicationToCentralStagingRepository \
  :hook-ref-bridge:publishMavenPublicationToCentralStagingRepository \
  :backend-libxposed-101:publishMavenPublicationToCentralStagingRepository \
  :backend-libxposed-102:publishMavenPublicationToCentralStagingRepository \
  -PmavenVersion=1.0.1 -PcentralStagingDir=build/central-candidate-1.0.1 --no-daemon

python tools/central/make_bundle.py \
  --staging build/central-candidate-1.0.1 --version 1.0.1 \
  --mode unsigned-preview --output build/central-1.0.1-unsigned-preview.zip
```

预览包没有签名，不能上传为正式发布包。`hook-dsl` 的文档 JAR 包含使用指南和说明页面，
不包含逐符号生成的 Kotlin API 文档。构建和候选校验不等于实际中央仓库部署验证。

通过 [独立消费者检查](../verification/central-consumer/README.md)，分别使用 Gradle
module metadata 和 Maven POM 解析同一 staging，并在每种模式下检查两代示例 APK 的 DEX
定义。这个检查不安装 APK，也不证明真实框架或设备上的行为。

## 发布者签名和打包

发布者自行检查 Central Portal 对目标 namespace 的权限、管理 PGP 私钥和 agent，并将公钥
发布到可由 Central 查询的位置。不要把私钥、口令或 Portal token 写入仓库、命令历史、
日志或聊天。Gradle 默认不签名；需要正式签名时，在受控环境中以新的 staging 目录执行
上述七个任务，并额外传入 `-PsignPublications=true`。此选项使用本机 GPG/agent；签名
后请保持制品不变，避免重新构建覆盖已签内容。签名构建应与已验证候选逐文件比对；若
字节有任何变化，应对签名后的 staging 重新执行消费者和制品检查，不能沿用旧候选的结果。
Windows 上若使用 Git for Windows 自带的 MSYS `gpg`，`GNUPGHOME` 需采用该程序可识别的
`/c/...` 路径形式；原生 Gpg4win 使用原生 Windows 路径。使用者应先在自己的隔离环境中
确认 GPG 和 agent 的实际配置，不要把私钥复制进项目。

将签名者的**公钥**导出为新的公开 keyring 文件，然后指定其完整主密钥或签名子密钥
指纹运行：

```sh
python tools/central/make_bundle.py \
  --staging <已签名staging目录> --version 1.0.1 --mode signed \
  --keyring <公开keyring文件> --fingerprint <完整指纹> \
  --output <不存在的正式bundle.zip>
```

`gpgv` 必须可执行；若不在 PATH，可使用 `--gpgv <可执行文件>`。打包器会对每个 POM、
主制品、sources、javadoc 和存在的 `.module` 验签并核对指纹，再生成 MD5 和 SHA-1；
它不访问私钥、不上传，也不会覆盖已有输出。详细选项和限制见
[打包器说明](../tools/central/README.md)。正式上传前还需由发布者独立检查包内容、密钥
有效性和 Central Portal 的命名空间权限；服务端验证通过也不等同于已正式发布。
