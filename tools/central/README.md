# Central bundle 本地打包

`make_bundle.py` 仅从显式 Maven staging 目录生成单个 ZIP，不签名、不上传。它覆盖七个
SDK 组件，并检查坐标、POM 元数据和项目间传递依赖、主制品／源码／文档归档、Gradle
`.module` 引用及摘要；可选 Ref 桥的 API 依赖还必须精确指向已授权的
`io.github.jichuo1.meguviz:meguviz-ref-core:1.0.0`。它还会为每个主文件生成 MD5 和 SHA-1。

```text
python tools/central/make_bundle.py --staging <staging> --version 1.0.1 \
  --mode unsigned-preview --output <不存在的-unsigned-preview.zip>

python tools/central/make_bundle.py --staging <已签名staging> --version 1.0.1 \
  --mode signed --keyring <仅含公开密钥的keyring> --fingerprint <完整签名密钥或主密钥指纹> \
  --output <不存在的正式bundle.zip>
```

`1.0.1` 仅为示例；中央仓库已有的版本不可覆盖，打包前须确认目标版本未被占用。
预览 ZIP 无签名，明确**不具备上传资格**。正式模式对每个 POM、主制品、sources、
javadoc，以及存在的 `.module`，都调用 `gpgv` 使用显式公钥环独立验签，并核对完整指纹；
缺签、验签失败、指纹不符、坏包或缺少依赖即失败。准备和签名仍由使用者自行控制；
脚本不读取默认私钥库，不生成或导入密钥，也不保证公钥已发布或 namespace 已验证。

输入目录和公钥环必须在整个打包期间保持静止，程序检查常规修改但不锁定文件，不能防御
拥有本地写权限者进行所有竞态攻击。校验是本地检查，不代替 Central Portal 服务端验证、
真实消费者兼容或正式性能资格。输出文件必须不存在；出错时若 ZIP 已开始写入，可能留下
不完整文件供使用者审计，脚本不会覆盖或删除已有数据。
