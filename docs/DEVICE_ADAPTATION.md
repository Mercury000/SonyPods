# Sony 耳机新机型适配

> 更新日期：2026-08-08。适配以真实 transport、support function、capability 响应和抓包为依据，不以型号名称猜测写入能力。

## 1. 适配的目标

新机型适配是让项目正确完成以下事项：

1. 发现可用的 SPP / GATT endpoint；
2. 选择 V1/V2 与 Table1/Table2 的正确 channel；
3. 完成握手和动态能力探测；
4. 将已确认功能绑定到正确的 query type、writable type 和 protocol variant；
5. 让已有产品 API 只在该机型确实支持时显示和发送。

如果只是希望加入一个新功能，而不是一个新型号，请使用 [协议功能扩展指南](PROTOCOL_EXTENSION_GUIDE.md)。

## 2. 证据优先级

| 优先级 | 证据 | 用途 |
|---|---|---|
| 1 | 目标耳机的实际 HCI/BLE/SPP 抓包 | 确认实际 payload、方向、时序与 response |
| 2 | 同版本 Sound Connect JADX 调用链 | 确认 Command、InquiredType、builder/parser 语义 |
| 3 | 连接时 support function / capability 响应 | 决定本次会话开放哪些功能 |
| 4 | 当前 SonyPods typed codec 与单测 | 复用已验证实现 |
| 5 | 型号名称、第三方表格 | 仅作线索，不能据此开放写入 |

`build/jadx_mcp/` 是本地逆向证据目录，故意不纳入 Git。可追踪的结论写入 `docs/` 并标注证据路径。

## 3. 标准流程

### 第 1 步：收集连接证据

记录：

- 蓝牙地址、广播名称、Classic BT 与 LE endpoint；
- GATT service / characteristic UUID；
- GATT handshake 日志、writable value length、实际 channel；
- Sound Connect 操作一个功能时的完整 TX/RX；
- support function 与 capability response。

保留原始数据，不要只保存人工转写后的 HEX。

### 第 2 步：确认 transport 和 variant

默认 GATT 路由：

| Variant | 默认 channel |
|---|---|
| V1 Table1 / V1 Table2 | `GATT_V1_MC` |
| V2 Table1 | `GATT_V2_HPC` |
| V2 Table2 | `GATT_V2_MC` |

SPP 则使用 `SPP_MDR`。实际 endpoint、response marker 和功能 binding 优先于默认值；同一设备可能让不同功能走不同 variant。

### 第 3 步：先运行动态能力探测

不要先为型号硬编码 feature 集。连接后应确认：

- protocol version 通过对应 whitelist；
- connect capability counter；
- `RET_SUPPORT_FUNCTION` 中的 V1/V2 FunctionType；
- 由 `SonyCapabilityProbe` 发出的域 capability 请求及返回；
- 推导出的 battery query、NC/ASM type、EQ 配置、feature binding。

如 counter 缓存命中，仍要在确认型号/transport/variant 合理后使用；counter 变化必须重新探测。

### 第 4 步：只添加必要的 quirk

确有动态探测不能表达的型号差异时，才新增 profile 或 quirk。它应明确说明：

- 目标型号与证据来源；
- 限制的功能、variant、channel 或 payload；
- 为什么通用 capability 推导不足；
- 精确回归 fixture。

禁止把“未抓到”解释为“肯定不支持”，也禁止把“同系列型号支持”复制为可写能力。

### 第 5 步：验证已有功能

至少覆盖：

- 设备信息和固件；
- 电量（头戴 single 或 TWS L/R/cradle）；
- NC/ASM（若 support function 存在）；
- EQ/Clear Bass（若存在）；
- 播放控制；
- 协议不支持、无 endpoint、异常 notification 的失败路径。

## 4. 文件边界

| 需求 | 正确位置 |
|---|---|
| Command/InquiredType、payload builder/parser | `protocol/` |
| 从 variant 选择 codec | `headphones/TandemCodecRegistry.kt` |
| 功能到 variant/channel 的绑定 | `headphones/HeadphoneAdapter.kt`、`SonyTandemHeadphoneAdapter.kt` |
| FunctionType 到能力的推导 | `headphones/SonyCapabilityProbe.kt` |
| 连接、GATT/SPP、重试和帧 | `ble/` |
| 状态合并、产品 API、能力门控 | `data/SonyHeadphoneRepository.kt` |
| UI、Hook 的展示/调用 | `ui/`、`bridge/`、`hook/` |

UI / Repository 禁止手拼协议字节。只新增 enum 或 profile 不得标记为“已适配”。

## 5. 测试清单

- [ ] endpoint / channel 选择测试；
- [ ] protocol version 与 support function fixture；
- [ ] 每个新增 builder 的精确 byte fixture；
- [ ] 每个 parser 的有效、截断、未知值 fixture；
- [ ] codec 和 adapter 的 variant / channel 路由；
- [ ] capability 不存在时 setter 不发包；
- [ ] SET 后的 GET/NTFY 状态回填；
- [ ] 与既有型号的回归测试。

## 6. 不应沿用的旧做法

早期文档中出现过静态 `sonydevices/*Profile.kt`、`tandem-capabilities.db`、`tools/btsnoop_extract.py` 等路径作为必经步骤；这些不是当前仓库的架构基线。当前实现以 `SonyCapabilityProbe`、`FeatureProtocolBinding`、`CapabilityProbeCache` 和现有 codec/adapter 为准。
