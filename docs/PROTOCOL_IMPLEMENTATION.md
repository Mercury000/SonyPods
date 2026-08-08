# Sony Tandem 协议实现现状

> 更新日期：2026-08-08。Sound Connect 13.2.1 为当前逆向基线。

## 1. 支持范围

项目能够识别并路由：

- Tandem V1 / V2；
- Table1 / Table2；
- BLE GATT 的 V2 HPC、V2 MC、V1 MC；
- Classic Bluetooth SPP；
- 未知命令的保守解析和诊断记录。

这不等于四张表的全部命令均已产品化。当前 typed builder/parser 和产品 API 主要集中在 Table1；V1/V2 Table2 仍主要是常量镜像、通用构建能力和诊断解析，尚无系统化产品 setter/query。

## 2. Tandem 消息与 DataType

项目内部消息结构：

```text
[dataType][command][payload...]
```

内部 marker：

| Table | 内部 DataType | 项目常量 |
|---|---:|---|
| Table1 | `0x0E` | `DATA_MDR` |
| Table2 | `0x0F` | `DATA_MDR_NO2` |

SPP 外层 frame type 与内部 marker 不同：

| Tandem table | SPP frame type |
|---|---:|
| Table1 | `0x0C` (`DATA_MDR`) |
| Table2 | `0x0E` (`DATA_MDR_NO2`) |
| Large MDR | `0x0D` |

`SonySppPayloadMapper` 负责映射，禁止在上层直接把内部 `0x0E/0x0F` 当成 SPP frame type。

## 3. BLE GATT

### 3.1 Endpoint

`TandemGattRouting` 根据 service UUID 路由到：

- V2 HPC：`GATT_V2_HPC`
- V2 MC：`GATT_V2_MC`
- V1 MC：`GATT_V1_MC`

每个 endpoint 使用对应 `TO_ACCESSORY` / `FROM_ACCESSORY` characteristic；通知按明确顺序启用。

### 3.2 握手顺序

当前状态机与 Sound Connect 13.2.1 对齐：

```text
发现服务
  -> 读取 OPTIMAL_MTU（缺失时使用默认大 MTU 请求）
  -> requestMtu
  -> enable DETERMINE_MTU notification
  -> 等待严格单字节通知 [0x01]
  -> disable DETERMINE_MTU notification
  -> 读取 WRITABLE_VALUE_LENGTH
  -> 启用 Tandem FROM_ACCESSORY notifications
  -> ready
```

要点：

- descriptor write success 仅表示订阅成功，不表示 accessory 已准备好；
- `DETERMINE_MTU` 仅接受长度为 1 且值为 `0x01`；
- writable length 必须是严格两字节大端正整数；
- payload 超出 writable length 时拒绝发送，不静默截断；
- 各握手阶段都有 timeout 和失败回收；
- 使用 `WRITE_TYPE_NO_RESPONSE`，写队列兼容有回调与无回调实现，并有 500 ms 释放保护；
- 旧 GATT session 的迟到 callback 会被忽略。

规则函数集中在 `ble/TandemGattProtocolRules.kt`，回归测试在 `ble/TandemTransportRulesTest.kt`。

## 4. SPP

SPP frame 由以下字段组成，并执行 escape/unescape 与 checksum：

```text
START | frameType | sequence | payloadLength(4-byte BE) | payload | checksum | END
```

当前可靠性策略：

| Frame | ACK timeout | 最大重试 |
|---|---:|---:|
| 普通 MDR（Table1/Table2） | 750 ms | 10 |
| Large MDR | 5000 ms | 2 |

重试保持原 frame 和原 sequence。连续收到相同 RX sequence 时仍发送 ACK，但只向 Tandem 上层分发一次，避免 accessory 重传造成状态重复应用。

## 5. 协议版本与表

- V1 protocol whitelist：9 项；
- V2 protocol whitelist：74 项；
- 白名单与 Sound Connect 13.2.1 顺序一致；
- V1 和 V2 的 FunctionType 必须按各自代次解释，不能用单一 byte map；
- `NcAsmInquiredType=0x02` 在 V1/V2 语义不同，解析必须携带 variant 上下文；
- V2 Table2 的 LEA table marker 为 `0x04`，Party 为 `0x05`。

完整枚举见 [13.2.1 协议审计](SOUND_CONNECT_13_2_1_PROTOCOL_AUDIT.md)，不要在本文件重复维护全量常量表。

## 6. 当前 typed 覆盖

### 6.1 已有完整或接近完整的垂直切片

| 功能 | Builder/parser | Codec | Adapter/API | 状态 |
|---|---|---|---|---|
| protocol/capability/support function | 有 | 有 | 连接探测已使用 | 已接入 |
| 设备信息/固件 | 有 | 有 | 刷新与状态已使用 | 已接入 |
| 单电量、L/R、充电盒 | 有 | 有 | capability 驱动查询与状态合并 | 已接入 |
| NC/ASM/环境声等级/人声 | 有 | 有 | setter + refresh | 已接入 |
| EQ/EBB/Clear Bass | 有 | 有 | setter + refresh + EQ 引擎 | 已接入 |
| 播放控制/状态 | 有 | 有 | setter + refresh/fallback | 已接入 |
| LE Audio 状态/配对历史 | 部分 | V2 T1 暴露 | query/parser | 部分接入 |
| Quick Access | 部分 | V2 T1 暴露 | query/parser，只读状态 | 部分接入 |
| 佩戴状态 | 部分 | V2 T1 暴露 | query/parser，只读状态 | 部分接入 |

### 6.2 未形成产品功能

- V1/V2 Table2 的 voice guidance、peripheral、多数 power、safe listening、system 扩展；
- 自动待机、Caring Charge、BT standby、stamina、power saving 的完整读写 API；
- 可分配按键、手势、语音助手设置；
- optimizer、spatial/head tracker；
- FOTA、日志、General Settings；
- 非耳机核心的 Party/DJ/Karaoke/lighting/Fiestable。

其中部分仅有 Command / InquiredType 常量或通用 parser，不应在 README 中宣称支持。

## 7. 能力探测与缓存

连接后流程：

1. 建立 transport 并绑定 endpoint；
2. 查询协议版本、connect capability counter、support function；
3. `SonyCapabilityProbe` 根据 FunctionType 生成有序域 capability query；
4. 推导 feature、battery query、NC/ASM query/write type 和 EQ 配置；
5. Repository 将 `counter + identifier + variant + transport + functions` 按设备地址缓存；
6. counter 一致时恢复，变化时重新探测。

跨进程缓存通过 `CapabilityCacheReceiver` 写入 app 可写的 remote preferences。缓存只保存可重新解释的 function code，不把旧的推导结果作为永久真相。

## 8. 响应分发

Repository 通过 `HeadphoneAdapterRegistry.parse(profile, channel, raw)` 得到 `ParsedTandemResponse`，再分别合并设备信息、电量、NC、EQ、播放、LEA、Quick Access、佩戴和 Table2 diagnostics。未知消息返回 `Unknown` 并记录，不因未知枚举崩溃。

## 9. 测试基线

2026-08-08 的协议与 transport 修复后：

```text
321 tests, 0 failures, 0 skipped
BUILD SUCCESSFUL
```

常用命令：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

新增功能至少需要 builder 精确字节测试、parser fixture、codec 路由、adapter channel、capability gating 和 repository 行为测试。详见 [协议功能扩展指南](PROTOCOL_EXTENSION_GUIDE.md)。
