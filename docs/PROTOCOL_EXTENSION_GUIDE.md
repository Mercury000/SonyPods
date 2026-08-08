# 耳机侧协议功能扩展指南

> 目标：为 SonyPods 增加“不依赖 Sound Connect App 本体，只需通过现有 SPP/GATT transport 向耳机发送合法 Tandem 消息即可完成”的功能。
> 典型候选：关机、自动待机、节能/充电设置、语音提示、多设备管理、按键/手势设置。

## 1. 先判断是否属于本项目范围

一个功能可以作为“耳机侧独立功能”接入，至少应满足：

- 最终状态或动作由耳机固件执行；
- 手机侧只需建立现有 Tandem 连接并发送/接收协议消息；
- 不要求 Sony 账号、云 API、内容下载、App 数据库、WebView 或私有 Android service；
- 不要求复制 Sound Connect 的媒体文件、语言包、固件包或许可证资源；
- 能通过 support function / capability response 判断适用性；
- 有可确认的 request payload 和结果 response/notification。

以下通常不属于“只发包即可完成”：

- FOTA 文件下载、签名验证、分片传输和升级恢复；
- 语音指导语言包下载/传输本身；
- 云端 Safe Listening 报表、账号同步、推荐内容；
- 需要 Sound Connect UI 流程或数据库才能生成参数的个性化内容；
- 日志上传和客服诊断后台；
- Party/DJ/Karaoke/lighting/Fiestable 等非目标耳机产品域。

如果一个域同时包含耳机侧设置和 App 服务，拆分实现：只接入已证明由耳机保存/执行的字段。

## 2. 完成定义

只有以下链路全部完成，功能才可标记为“已接入”：

```text
能力标识
  -> typed request/response model
  -> protocol builder/parser
  -> TandemCodec 暴露
  -> adapter 的 variant/channel/query/write binding
  -> repository state + 产品 API
  -> capability gating
  -> SET 后状态确认
  -> byte-level 与行为测试
```

下列情况仍是“未接入”：

- 只有 `Command` 常量；
- 只有 `FunctionType` / `InquiredType` 枚举；
- 通用 parser 能把 payload 显示为整数列表；
- 调试页可以发送 raw HEX；
- UI 有开关但没有 capability gating；
- SET 能发出但没有 response/NTFY 验证。

## 3. 协议调查表

开始编码前，在 PR 或文档中填完：

```text
功能名称：
耳机侧独立完成：是 / 否 / 部分
目标型号与固件：
V1/V2：
Table：
Transport/channel：
FunctionType：名称、table、code
Command family：
InquiredType：名称、code
GET：command + payload
RET：command + payload
SET：command + payload
NTFY：command + payload
SET 后确认方式：RET / NTFY / 延时 GET
前置条件/冲突：
失败或拒绝语义：
JADX 证据类：
抓包 fixture：
```

任何未确认项写 `待验证`。不要通过相邻命令、枚举 ordinal 或字段名称补齐字节。

## 4. 分层实现步骤

### 4.1 能力和枚举

在对应 V1/V2、Table1/Table2 上定义：

- FunctionType；
- InquiredType；
- payload 内部 enum / flags；
- `HeadphoneFeature` 或更细粒度 capability model。

同一个 byte 在不同代次或 table 可能语义不同。保存/恢复 FunctionType 时必须携带协议代次，`0xFF` 也可能同时是合法值和 sentinel，不能用单一 `Map<Byte, Enum>` 粗暴合并。

### 4.2 Typed request/response

优先建立明确类型，而不是 `List<Int>`：

```kotlin
data class AutoStandbyState(
    val enabled: Boolean,
    val timeout: AutoStandbyTimeout,
)
```

解析器应：

- 先验证最小/精确长度；
- 验证 command 和 inquired type；
- 对未知枚举保留 raw code，不崩溃；
- 不接受截断 payload；
- 将原始 bytes 保留到 diagnostic 字段（如现有类型需要）。

### 4.3 Protocol builder/parser

在正确的 `SonyTandemV{1,2}Table{1,2}Protocol.kt` 中实现：

- `buildGet*`；
- `buildSet*`；
- 对应 RET/NTFY parser；
- 必要的 capability parser。

builder 返回的是项目内部 Tandem 消息：

```text
Table1: [0x0E][command][payload...]
Table2: [0x0F][command][payload...]
```

不要在协议层构造 SPP frame；SPP/GATT 封装由 transport 完成。

### 4.4 TandemCodec

在 `TandemCodec` 增加最小、typed 的方法，并只由支持该 variant 的 codec override。避免提供一个“任意 command + 任意 bytes”的产品接口绕过类型检查。

示意：

```kotlin
fun buildGetAutoStandby(): ByteArray? = null
fun buildSetAutoStandby(value: AutoStandbySetting): ByteArray? = null
```

Table2 功能需要真正实现 V1/V2 Table2 codec 暴露；不能停留在当前 `parse(raw)` 或 generic builder。

### 4.5 Adapter 与 channel

Adapter 负责：

- 根据 feature binding 选择 codec；
- 使用 `FeatureProtocolBinding.channel`；
- 从 capability 中选择正确 InquiredType；
- 构建带 label、bytes、channel 的 `HeadphoneCommand`；
- 在 response command collision 时用 channel、variant 和 inquired type 消歧。

不要假设 Table1 一定走 HPC、Table2 一定走 MC；应使用 profile 的实际 binding。SPP 使用 mapper 处理外层 frame type。

### 4.6 Repository/API

Repository 提供业务语义，不暴露协议细节：

```kotlin
fun powerOff()
fun setAutoStandby(enabled: Boolean, timeout: Duration)
```

发送前必须验证：

- 已连接且 protocol ready；
- profile 支持该 feature；
- feature binding 和 writable type 存在；
- 参数在 capability 允许范围；
- payload 长度不超过 transport writable limit；
- 没有已知互斥状态。

### 4.7 SET 后确认

优先级：

1. 等待与本次写入匹配的 NTFY；
2. 使用 SET 的明确 ACK/RET；
3. 延时发送 GET 并读取 RET；
4. 若设备会断线（例如关机），把预期断线作为成功候选，但仍需超时和错误区分。

禁止在发送成功回调时立即把 UI 永久状态改为成功。`WRITE_TYPE_NO_RESPONSE` 只证明本地写入队列已释放，不证明耳机接受了设置。

## 5. 关机功能示例（当前为协议已知、payload 待验证）

已确认的协议定位：

| 代次 | Table | 能力 | Command | InquiredType |
|---|---|---|---|---|
| V1 | Table1 | `POWER_OFF = 0x21` | `COMMON_SET_POWER_OFF = 0x22` | `PowerOffInquiredType.FIXED_VALUE = 0x00` |
| V2 | Table1 | `POWER_OFF = 0x23` | `POWER_SET_STATUS = 0x24` | `PowerInquiredType.POWER_OFF = 0x03` |

尚未在当前项目中核实并固化：

- SET body 中除 InquiredType 外的精确字段和值；
- 不同固件是否需要确认/延时；
- 成功后是先 NTFY 再断线，还是直接断线；
- TWS 左右耳/充电盒状态对行为的影响；
- V1/V2 的调用方前置条件。

因此现在只能建立接入任务，不能根据上表直接拼一个“猜测包”。正确实现顺序：

1. 在 JADX 中定位 `COMMON_SET_POWER_OFF` 和 V2 `POWER_SET_STATUS + POWER_OFF` 的实际 request builder；
2. 在 Sound Connect 中执行关机并保存 TX/RX 抓包；
3. 分别为 V1/V2 建立精确 fixture；
4. 添加 typed builder 和 capability gating；
5. 将“预期主动断线”纳入 repository 状态机；
6. 在至少一个 V1 和一个 V2 支持型号上验证。

## 6. 参数与安全规则

- bool/enum 只发送 JADX 或抓包确认的合法值；
- capability 未返回的功能默认不可写；
- 不把 `OUT_OF_RANGE`、`UNKNOWN`、`0xFF` 发送给耳机；
- 不自动遍历值域探测写命令；
- 有破坏性或不可逆效果的命令（关机、重置、配对清除、升级）必须显式用户操作；
- reset、pairing database delete、FOTA 等需要额外确认和失败恢复设计；
- transport timeout 不自动无限重试业务 SET；SPP frame 级重试由 transport 负责，产品层不得重复叠加导致多次执行。

## 7. 测试要求

每个功能至少包括：

1. V1/V2 builder 精确 bytes；
2. RET/NTFY parser 的正常、截断、未知值；
3. Table/channel 路由；
4. capability 存在/不存在两条路径；
5. 参数边界；
6. SET 后确认或超时；
7. 断线、重连、旧 callback 不污染新 session；
8. 与当前 321 项测试的全量回归。

## 8. 文档同步

功能合入时同步：

- [协议实现现状](PROTOCOL_IMPLEMENTATION.md)；
- [耳机命令目录](HEADPHONE_COMMAND_CATALOG.md)；
- 新增证据的 APK 版本、类和 fixture；
- 若改变架构，再更新 [架构说明](ARCHITECTURE.md)。
