# Maven Central 发布

正式SDK坐标为 `io.github.wujiiii:huya-connect-sdk:0.1.1`，Java包名保持 `dev.huya.connect.sdk`。Apache-2.0许可证见根目录LICENSE。

只发布父POM与SDK，不发布采集Demo或OrdinaryRoad依赖：

```powershell
mvn -B -pl sdk -am -Pcentral clean deploy
```

`central` profile生成源码包、Javadoc、GPG签名和Central校验文件。使用本机Maven settings中id为`central`的凭据，以及本机GPG签名密钥；凭据和密钥不得放进源码。默认上传后等待验证，不自动发布；验证通过后通过Central Portal或Publisher API发布。

公开源码只包含代码、测试、构建配置与使用文档。录制、观众数据、运行快照、凭据和个人分析导出均不属于发布内容。构建不会更新正在运行的独立采集包，也不会重新启动采集。

版本发布到Central后不可覆盖；后续修改应使用新版本。中央仓库可见性及搜索索引可能稍有延迟。
