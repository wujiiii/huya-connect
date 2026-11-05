# 虎牙数据采集 SDK（Java 17）

独立的匿名、只读 WebSocket SDK，可作为 Maven 依赖引入。默认不写盘，不启动浏览器、不播放视频、不进入 P2P、不需要账号 Cookie。旧 Demo 保留在 `legacy-demo` 模块，仍使用它原有的 OrdinaryRoad 依赖。

## 构建和引入 SDK

需要 JDK 17 和 Maven。从仓库根目录运行以下命令，仅构建、测试并安装 SDK 和父 POM，不需要旧参考工程：

```powershell
mvn -pl sdk -am clean install
```

业务项目添加依赖：

```xml
<dependency>
    <groupId>io.github.wujiiii</groupId>
    <artifactId>huya-connect-sdk</artifactId>
    <version>0.1.1</version>
</dependency>
```

SDK 运行时仅依赖 Jackson；HTTP/WebSocket 使用 JDK。SDK 不携带日志绑定或全局日志配置。全仓 `mvn clean verify` 同时验证旧 Demo，需要其原有依赖。

## 房间数据查询（0.1.1新增）

以下API自0.1.1提供，0.1.0不包含这些接口。Maven依赖使用上方的`io.github.wujiiii:huya-connect-sdk:0.1.1`，Java包名不变。

核心组注册成功后，SDK自动执行以下查询；每次重连重新查询。`start()`仍只等待核心组注册，不等待这些查询完成。

| 方法 | 结果口径 |
| --- | --- |
| `refreshFanClubMembers()` | `getFansScoreRankPanel.lTotalFansNum`：主播粉丝团成员总数，不是榜单长度或在线人数 |
| `refreshGuardianCount()` | `getGuardianCount.iCount`：当前有效守护总数，后续URI6249以iCount整体替换，不累加开通通知 |
| `refreshFollowerCount()` | 匿名只读HTTP关注人数查询，不等待URI3102；后续3102整体替换缓存 |
| `refreshLiveStatus()` | `getLivingInfo`：直播/未播/未知、回放标记、场次ID及开播时间原值 |
| `refreshGiftCatalog()` | 复用`getResourceInfo`完整资源响应，解析礼物ID、iPropsType、名称和目录价格原值；不重复发初始化目录请求 |

这些方法均返回`CompletableFuture<HuyaQueryResult<T>>`，同一连接上同类查询同时进行时复用在途请求。默认10秒超时，可用builder的`queryTimeout(Duration)`调整；查询失败不会导致SDK为了查询而重连，也不会阻塞实时接收。future续接任务不在SDK保活/控制线程完成，但耗时业务仍应自行使用业务执行器。

```java
try (HuyaClient client = HuyaClient.builder(roomId)
        .presenterUid(verifiedPresenterUid)
        .listener(listener)
        .queryTimeout(java.time.Duration.ofSeconds(10))
        .build()) {
    client.start().get(60, TimeUnit.SECONDS);

    var fans = client.refreshFanClubMembers().get(15, TimeUnit.SECONDS);
    if (fans.successful()) {
        Long memberCount = fans.value(); // 真实0保留为0；失败/缺字段不填0
        System.out.println(memberCount);
    } else {
        System.err.println(fans.outcome() + ": " + fans.detail());
    }

    var guardians = client.refreshGuardianCount().get(15, TimeUnit.SECONDS);
    var followers = client.refreshFollowerCount().get(15, TimeUnit.SECONDS);
    if (guardians.successful()) System.out.println("当前守护总数：" + guardians.value());
    if (followers.successful()) System.out.println("关注总人数：" + followers.value());

    var live = client.refreshLiveStatus().get(15, TimeUnit.SECONDS);
    if (live.successful()) {
        System.out.println(live.value().state()); // LIVE / OFFLINE / UNKNOWN
        System.out.println(live.value().replay()); // true / false / null
        System.out.println(live.value().liveId()); // 十进制字符串；离线可能为"0"
        System.out.println(live.value().startTimeEpochSeconds()); // 原始整数秒，缺失为null
    }

    var directory = client.refreshGiftCatalog().get(15, TimeUnit.SECONDS);
    if (directory.successful()) {
        HuyaRoomInfo.GiftItem gift = directory.value().gifts().get(giftId);
        Integer propsType = gift == null ? null : gift.iPropsType(); // 未知不能当作类型0
        System.out.println(propsType);
        System.out.println(directory.receivedAt());
        System.out.println(directory.responseSha256());
        // directory.evidence()为原始HuyaEvent.Snapshot，保留请求ID、完整响应及raw()
    }
}
```

`roomInfo()`返回最近观察的不可变快照，包括`fanClubMembers`、`guardianCount`、`followerCount`、`liveStatus`和`giftCatalog`，各项均为`HuyaQueryResult`。新建、断连、重连及关闭时清为明确未知，避免把旧连接的数据当作新状态。

每个结果包含`presenterUid`、`connectionId`、`requestId`、`requestedAt`、`receivedAt`、`outcome`、`value`、`detail`、`responseSha256`和`evidence`。`SUCCESS`表示该查询值可读取；`UNKNOWN`或`FAILED`的value为null。尚无响应时receivedAt为null；requestedAt仍保留实际请求发起时间。未连接或已关闭时主动刷新直接返回UNKNOWN，不悬挂。HTTP关注数保留响应字节SHA-256，evidence为空；WUP查询保留原Snapshot。推送更新的requestId为空，evidence为原Message。

直播查询的`LiveInfo`中，`state`为`LIVE/OFFLINE/UNKNOWN`，`replay`为独立可空标志。回放不报告为LIVE；缺回放标记且协议表示正在播时，state为UNKNOWN。`liveId`保持十进制字符串，`startTimeEpochSeconds`保持协议整数秒；离线响应中的`"0"`场次ID和0开播时间原值不伪装成有效新场次，缺字段为null。实时开/下播仍读取既有Live事件的`message().fields()`，不增加重复业务事件。该查询缓存通过启动、重连或主动刷新更新。

旧连接的迟到WUP/HTTP响应不能更新新连接缓存；查询超时后的迟到响应不覆盖结果。在途查询之前已经发起、但之后收到更新的关注/守护推送时，该查询的迟到结果仍可供其future读取，但不覆盖较新的推送缓存。

目录只接受`iRetCode=0`且`iAllResource=1`的完整响应；失败或增量响应不冒充完整目录。礼物ID使用十进制字符串，iPropsType缺失时为null；保留每个礼物的原始JCE字段以及整份响应证据和哈希。SDK不按名称猜类型，不实施梦想值、收益、业务去重、每日汇总或提醒规则，超粉查询不在本次范围。

## 持续采集1995和童锦程【798】

`collector-demo` 是单独依赖SDK的Java应用，固定采集1995（主播UID `2272316519`）和527988（童锦程【798】，主播UID `1199568157971`）。798是标识，不作为房间号发起连接。它不读取推荐页来替换目标，也不改动研究项目的Python采集器。

在本仓库根目录使用PowerShell：

```powershell
# 构建并后台启动；重复start返回已有实例，不重复采集
.\collector-demo.ps1 start
# 查看状态、即时健康检查（失败时退出码2）
.\collector-demo.ps1 status
.\collector-demo.ps1 healthcheck
# 运行一次每小时监测使用的检查，保存记录并按条件恢复意外退出
.\collector-demo.ps1 monitor
# 为已有采集附加独立守护，不重启采集；start也会自动附加
.\collector-demo.ps1 watchdog
# 正常停止并保留停止意图，每小时监测不会重新拉起
.\collector-demo.ps1 stop
```

默认目录为 `captures/sdk-demo-independent`，可用 `-DataDirectory` 指定独立目录。原 `captures/sdk-demo` 在重叠采集验证后退役，保留历史文件和STOP标志，不再作为默认启动或监测目录。启动通过Windows WMI独立入口创建隐藏宿主，并验证进程不属于Windows Job；单纯隐藏窗口不足以摆脱Codex生命周期。独立运行包使Maven后续构建或清理不影响已启动进程。SDK负责网络保活、重连和所有原始报文录制；应用每2秒更新原子状态快照，并持有目录进程锁防止重复写入。创建根目录的 `STOP` 文件也可以请求正常停止；显式再次执行 `start` 会清除旧停止标志并创建新会话。

状态文件替换遇到Windows文件占用时，先在1秒内重试；仍被拒绝则保留上次完整状态、记录警告并在下一轮继续发布，不再因此关闭两个房间。持续失败仍由监测的 `stale_status` 告警；恢复后公布 `statusWriteFailures` 和最近失败时间。原始录制失败、SDK致命错误和磁盘不足仍停止采集，不按网络故障重试。

```text
captures/sdk-demo-independent/
  status.json                  当前进程身份、固定目标、注册状态、协议/写盘时间和计数
  monitor-last-check.json      最近一次监测、同会话增量、恢复动作及通知类型
  launch.json                 已部署运行包位置和启动进程信息
  watchdog.json               独立守护的身份、心跳和恢复次数
  watchdog-events.ndjson      退出、已观察退出码及恢复事件
  supervisor/<启动标识>/     守护代码快照及日志
  diagnostics/               重启前状态和诊断，不覆盖旧raw
  collector.lock              Java实例持有的目录锁
  runtime/<启动标识>/          独立JAR和stdout/stderr日志
  runs/<UTC时间-随机标识>/
    1995/raw-*.ndjson          完整原文，按64MiB分段
    1995/events.ndjson         派生协议和业务事件
    527988/raw-*.ndjson
    527988/events.ndjson
```

目录名时间为UTC；每条记录保留UTC时间。状态只含运行指标，不包含弹幕、昵称或原始载荷。`gifts`等计数表示观察到的广播次数，不自动业务去重，不是购买笔数或收益。原始和派生文件会持续增长，不自动删除历史数据。

健康检查同时验证PID、创建时间、Java入口和数据目录身份、两房间核心注册RUNNING、100秒内有效协议响应与SDK完成写入的回调，以及实际打开文件句柄读出的文件字节数。Windows上写入中的文件，其目录枚举大小和修改时间可能滞后，不能据此误报零字节；PowerShell解析状态时也会保留UTC时间字符串，避免UTC时间被当作本地时间。监测按同会话比较文件字节增长；没有弹幕或下播不影响20/21保活健康判断。解析错误单列数据质量信息。

磁盘低于2GiB警告，低于512MiB或任一SDK出现致命错误时停止当前Demo，不删除原文。独立守护每2秒检查，仅在进程已意外退出、没有主动停止标志/原因、没有明确SDK或存储致命错误且磁盘足够时，复用已部署运行包恢复；不自动编译当前源代码，也不依赖Codex打开。活进程失联、身份不明或停止超时需要处理，控制脚本不会按名称批量结束Java或强制删除数据。SIGINT/JVM正常关闭保守记录为 `shutdown_signal` 并尊重停止；没有安装开机启动服务。

本聊天另配置了名为“虎牙SDK双房间每小时健康检查”的Codex自动化，每小时运行 `monitor`。正常、主动停止或问题无变化时不重复通知；新增故障、恢复、首次数据质量问题及磁盘警告才通知。已有Python研究采集及其原监测保留。采集进程不依赖聊天保持打开；Codex本地定时监测需要电脑和Codex应用处于可运行状态，关机、休眠或离线期间不保证采集和补漏。

验证：`mvn verify` 验证三个模块；`.\tests\collector-demo-selfcheck.ps1` 验证控制脚本的身份、时间解析、文件长度、停止意图、恢复策略和通知判定。Demo实跑还验证了双房间注册、目录/资源响应、3102关注数、保活、真实文件增长、重复启动幂等及正常停止后监测不拉起。

## 实时接入

```java
import dev.huya.connect.sdk.*;
import java.util.concurrent.TimeUnit;

try (HuyaClient client = HuyaClient.builder("1995")
        .presenterUid("2272316519") // 可省略；省略时从公开页面解析并校验房间身份
        .listener(new HuyaListener() {
            @Override public void onEvent(HuyaEvent event) {
                if (event instanceof HuyaEvent.Gift gift) {
                    System.out.println(gift.giftName() + ": " + gift.payTotalRaw());
                }
            }
            @Override public void onError(Throwable error) {
                System.err.println(error.getMessage());
            }
        })
        .build()) {
    client.start().get(60, TimeUnit.SECONDS);
    Thread.sleep(120_000); // 示例：业务在此期间继续运行
}
```

一个实例负责一个房间；多个房间创建多个实例。`start()` 幂等，首次核心组 ACK=0 才完成；网络错误会在后台退避重试，调用方的 `get` 超时不会自动关闭客户端。客户端关闭后不能重启，未完成的启动请求会异常结束。`status()` 返回不可变状态快照；启动 future 的用户续接任务在公共异步线程执行，不阻塞协议保活。

房间号和主播 UID 是不同标识。显式 UID 表示调用方已经核实身份，直接订阅该 UID；SDK 不内置房间映射。不指定 UID 时读取公开页面，严格核对 `profileRoom`，推荐页和缺失资料会报告错误并重试，不切换到推荐主播。成功解析后的 UID 在本实例内固定。

`onRawFrame` 提供完整 WS 应用消息；`onEvent` 提供命令、消息、快照、常用业务模型和解析错误。每个推送先交付一个 `HuyaEvent.Message`，识别出常用业务后再交付一个对应的 `Business` 事件，两者代表同一个推送，不能一起重复计数。统一消息包含 URI、分组、messageId、载荷 Base64、通用 JCE 和已知字段；`raw()` 包含时间、连接、会话与原始序号。

常用模型为 `Danmu`、`Gift`、`Entry`、`Guardian`、`Live`；入场和守护的完整命名字段通过 `message().fields()` 获取。所有其它 URI、未知命令也会交付。业务模型缺失的金额/数量字段为 `null`，不填成零。ID 使用十进制字符串；JCE 通用字段的64位整数保留有符号原值，业务 ID 按完整无符号位解释。

URI 3102 的消息体使用 JSON，SDK在统一 `Message` 中将它命名为 `SubscribeCountNotice`，`fields()` 提供 `pid`（主播UID字符串）和 `subscribe_count`（关注人数整数），`jce()` 为空。它不是礼物、付费订阅或收益消息。这里只为该已核对URI选择JSON解析，其它URI仍沿用JCE；缺字段、非法数值、重复JSON键、尾随内容或超出资源限制仍报告错误并保留原始载荷。

核心组和辅助组分别按请求 ID 确认。核心组注册完成后自动查询活动横幅（请求100）和全量资源目录（请求101），结果以独立 `Snapshot` 事件交付，包含请求标识、函数名、返回码（若结构含该字段）、WUP头、属性和响应 JCE。辅助组注册失败独立报告；它的 ACK 不代表核心组成功。重连会重新注册与初始化，不补发离线期间的历史消息。

## 回调、错误和停止

回调按房间顺序在该实例的独立线程执行；保活、注册和重连不等待业务回调。默认缓冲256个交付任务，可用 `callbackCapacity(int)` 调整。回调应快速返回，耗时业务应自行交给业务队列。回调抛出的异常交给 `onError`，后续事件继续交付；`onError` 自身抛错会被隔离，避免递归。

缓冲溢出或录制失败会停止当前实例，状态变为 `FAILED`，不静默丢弃后继续假装健康。队列已有回调排空后交付最终错误和状态；业务回调一直阻塞时，最终通知也会等待，`status()` 可立即查看故障。`close()` 幂等，关闭网络与录制，并在非回调线程最多等待5秒排空回调；回调中关闭不会等待自身。所有自有线程均为后台线程，业务进程需要自行保持运行。

网络断连、核心注册拒绝或30秒未确认、90秒无有效协议响应会重试；30秒保活，两个公开端点轮换，指数退避最多60秒并附加少量抖动。SDK 不开放任意协议发送接口。

## 可选录制和离线回放

builder 增加 `.recording(Path.of("captures/new-session"))` 即可录制。必须使用空目录，已有会话不会被覆盖。`raw-*.ndjson` 按64 MiB分段，每行保留完整 Base64、SHA-256、字节数、会话、连接和序号；`events.ndjson` 中派生事件引用相同原始序号。原文写入并 flush 后才解码，派生记录完成后才交付回调。flush 不等于断电持久保证。为限制异常报文资源，WS应用消息最多64 MiB，JCE派生解析最多16 MiB、32层、10万个容器项、25万个节点；超出WS上限明确失败，超出解析上限仍保留已接收原文并交付解析错误。

```java
long frames = Replay.read(Path.of("captures/new-session"), listener);
```

回放同步运行在调用方线程，支持 SDK 新格式和旧 Demo 的 `raw-*.ndjson`；核对已有字节数和哈希，不访问网络、不修改输入。回放中的回调异常直接终止回放并报告异常，不使用实时客户端的异步隔离策略。`out` 原文记录提交发送的应用载荷；发送失败另行报告，不证明服务器已收到。原始文件可能包含公开观众资料，分享前另做脱敏。

旧 Demo 若将消息拆成 `ContinuationWebSocketFrame` 保存，其旧格式没有记录结束碎片标志，无法可靠重组；回放仍交付每段原文并明确报告解析限制。新 SDK 在接收端先组装完整消息，不受此旧格式限制。

```powershell
# 独立SDK示例：省略OutputDirectory则不写盘；DurationSeconds=0持续运行
.\run-sdk.ps1 -Room 1995 -PresenterUid 2272316519 -DurationSeconds 120 -OutputDirectory captures/sdk-session
# 527988曾返回推荐页，可显式使用已核实UID
.\run-sdk.ps1 -Room 527988 -PresenterUid 1199568157971 -DurationSeconds 120
.\run-sdk.ps1 -ReplayDirectory captures/sdk-session
```

示例支持 Ctrl+C；开启录制时也可在录制目录创建 `STOP` 文件。相对输出路径按仓库根目录解析。新 SDK 示例和旧 Demo 的运行脚本彼此独立。

## 协议来源与边界

实现参考研究项目2026-11-03的纯WS链路，随包固化 `schemas.json` 中133个 URI 和767个结构的字段映射；来源已发布脚本 SHA-256 为 `90777c1fb1fac341329b8fe596348aecc8a4ba1a8baf4be76d8d6cd1fc760372`。映射包含字段名、tag和种类，嵌套对象仍保留通用 JCE，并不声称767个结构都有完整递归业务模型。协议标识 `webh5&2611021517&websocket` 来自同批证据。编码测试与保存的官方codec样本逐字节比较；运行不读取研究项目文件。

`payTotalRaw`、数量和支付类型均为协议原值，不等于用户实际现金支出或主播收益。SDK 不自动去重、不按收礼对象过滤、不套用研究项目核算候选规则。不得将统一消息与业务事件重复累加。公开协议可能变化，平台未下发和断网期间的消息不保证完整或可补齐。

本地测试、独立消费项目和两个房间的同窗对照结果见[首版验证报告](docs/sdk-validation-20261103.md)。

以下保留原有旧 Demo 使用说明；其 Java 源码、资源和测试现在位于 `legacy-demo`。

# 旧版虎牙匿名房间消息采集 Demo

这是一个**只读**的 Java demo：不需要虎牙账号 Cookie 或开放平台 `appId`，连接指定房间并保存匿名 WebSocket 收到的应用数据帧，同时把已认识的弹幕、礼物和其他命令写成便于查看的 JSON Lines 文件。它依赖本机的 `D:\Development\ordinaryroad-live-chat-client-main` 项目完成网页协议连接和解码。

## 运行

需要 JDK 17、Maven，以及上述参考项目目录。在 PowerShell 中进入本仓库后运行：

```powershell
.\run.ps1 -Room 1995
```

默认持续运行，输出目录会打印在终端，通常是 `captures/1995-<UTC时间>`。也可以指定输出和运行时长：

```powershell
.\run.ps1 -Room 1995 -OutputDirectory captures/my-session -DurationSeconds 120
```

在前台按 `Ctrl+C` 停止。后台运行时，在采集目录创建名为 `STOP` 的空文件，程序会在约一秒内结束：

```powershell
New-Item -ItemType File -Path 'captures/my-session/STOP'
```

`run.ps1` 在本机 Maven 仓库缺少参考项目依赖时，会先安装其虎牙模块；该项目的 Javadoc 含非标准 `@date` 标签，因此安装时跳过 Javadoc 生成。采集程序不会发送弹幕或礼物。

## 输出

| 文件 | 内容 |
| --- | --- |
| `raw-0001.ndjson`、`raw-0002.ndjson`… | 到达应用解码器前和客户端写出的 WebSocket 数据帧。载荷以 Base64 保存，含时间、房间号、连续序号、`direction`（`in` / `out`）、帧类型、字节数。原始帧按约 64 MiB 分文件。 |
| `events.ndjson` | 已解析的弹幕、礼物、其他命令、未知命令、解码错误、连接及会话事件。`rawSequence` 指向事件生成时最后保存的原始帧。 |
| `headline-board.ndjson` | 可选的“上头条”留言板只读快照。每行含查询时间、已解析的留言 ID、正文和支付原值，以及完整 WUP 请求/响应的 Base64。需单独运行 `run-board.ps1`。 |
| `recovered-gifts.ndjson` | 对旧版采集中的 `decode_error` 原始帧补做基础礼物解析；使用 `run-recover.ps1` 生成，不覆盖原始文件。 |

原始帧能用于日后补充未知消息协议；`events.ndjson` 中的 `payTotalRaw` 只是协议原值，**不是主播实际收入**。当前参考项目只对普通弹幕和 `6501` 送礼消息提供明确解析，守护、贵族购买和“上头条”付费留言仍需要进一步分析原始数据与实播样本。`unknown_command` 不意味着数据丢失，其对应原始帧已保存。参考项目在部分礼物帧上会发生 Tars 字段类型不匹配；demo 对此尝试只读取稳定的基础礼物字段，并将结果写成 `gift_recovered`（`decodeMode: partial`）。仍无法恢复的帧会写成 `decode_error`，完整 Base64 始终保留。

文件会包含观众昵称、弹幕等公开房间互动信息，且持续运行会增长磁盘占用。`captures/` 已被 Git 忽略；分享数据前可先压缩所需会话目录并检查是否需要去标识化。新会话请使用新的输出目录。

## “上头条”留言板快照

网页 WebSocket 的 `2001314` 只提示留言板发生变化，不直接包含留言正文。可在**另一个终端**用同一房间和采集目录运行辅助查询，示例每 15 秒抓取一次：

```powershell
.\run-board.ps1 -Room 527988 -OutputDirectory captures/my-session -DurationSeconds 3600 -IntervalSeconds 15
```

每次快照都会保留完整 WUP 响应，以便重新解析；同一 `messageId` 会在展示期内重复出现，分析时应按 ID 去重。这个辅助查询依据第三方公开协议实现推导，和 WebSocket 采集一样需要真实房间样本验证。当前请求每次最多读取留言板前 10 条，不应把快照条数当作房间历史留言总数。

## 恢复旧采集中的礼物

旧版会话中的 `decode_error` 仍可从原始帧恢复基础礼物字段。对**已结束**的采集目录运行：

```powershell
.\run-recover.ps1 -CaptureDirectory captures/old-session
```

结果写到 `recovered-gifts.ndjson`，仅包含部分可读字段；与 `events.ndjson` 中的 `gift` 合并分析时，按 `rawSequence` 去重。新的修复版采集会直接把容错结果写为 `gift_recovered`，无需再执行这一步。

## 初步核对

```powershell
$events = Get-Content 'captures/my-session/events.ndjson' | ConvertFrom-Json
$events | Group-Object type | Select-Object Name,Count
$events | Where-Object type -eq 'gift' | Group-Object { $_.data.giftName } | Select-Object Name,Count
```

## 发布

SDK采用Apache-2.0。发布配置见[发布说明](docs/publishing.md)，新增房间查询的口径与验证见[查询验证说明](docs/sdk-room-queries.md)。公开源码不包含正式录制、运行快照和个人研究数据。
