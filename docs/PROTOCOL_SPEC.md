# SonyPods Tandem 协议技术说明（构造 / 响应 / 逐项支持清单）

> 基线：SonyPods master（commit `15c8e8d`，2026-08-29）对照 Sound Connect 13.2.1
> （`com.sony.songpal.mdr`，versionCode 130215029）。
> 核对方式：JADX MCP 静态反编译 SC 对应类，逐字节比对 SonyPods 构造器与解析器。
> 完整 Command / FunctionType / InquiredType inventory 见
> [SOUND_CONNECT_13_2_1_PROTOCOL_AUDIT.md](SOUND_CONNECT_13_2_1_PROTOCOL_AUDIT.md)；
> 本文聚焦**当前实现的逐项协议面、帧构造与响应布局、一致性结论**。

## 0. 本次核对结论摘要

| 核对项 | SC 证据类 | 结果 |
|---|---|---|
| V2 Table1 Command 字节总表（全 172 值含未实现域） | `p069v2.table1.Command` | ✅ 全部一致 |
| V1 Table1 Command 字节总表 | `p068v1.table1.Command` | ✅ 全部一致 |
| V1 协议版本白名单（9 项 2 字节 BE） | `p573uv.C29903d.f85968b` | ✅ 逐值一致 |
| V2 协议版本白名单（74 项 4 字节 BE） | `p636wv.C30916e.f88128b` | ✅ 逐值一致 |
| ASSIGNABLE_SETTINGS V1（枚举/能力/SET_PARAM/解析） | `se0.d` `te0.b/d/a` `p068v1…AssignableSettings*` | ✅ 逐字节一致 |
| ASSIGNABLE_SETTINGS V2（枚举/能力/SET/SET_EXT） | `cg0.c/d/a/b` `bg0.a/b` `p069v2…Preset/Action/Function/Key/Type` | ✅ 逐字节一致 |
| NCASM SET 写入器（NA 变体 0x19） | `rf0.C27214g` | ✅ 逐字节一致 |
| LE Audio 设置流（0x0C 可用性/取值） | `d30.C15454a` + `LEAInquiredType` | ✅ 一致 |
| 连接质量 PriorMode 枚举 | `p069v2…audio.param.PriorMode` | ✅ 逐值一致 |
| Table2 配对模式 PeripheralBluetoothMode | `p069v2…table2.peripheral.param.PeripheralBluetoothMode` | ✅ 逐值一致 |
| GET_CAPABILITY / GET_EXT_PARAM 通用体 | `ag0.C0126a` `ag0.C0130b` | ✅ 一致 |
| 帧层（0x3E…0x3C 转义/校验和） | `ne0.C24171b` / `AbstractC24170a` | ✅（2026-08-08 审计 + 单测） |

其余域的布局在源码注释中逐条标注 SC 类出处（各功能提交时经 jadx 核对），
并有 byte-level 单测覆盖（`app/src/test/…/protocol/`，321+ 项）。
未发现任何与 Sound Connect 不一致的已实现项。

## 1. 传输层

### 1.1 通道

| 通道 | 用途 |
|---|---|
| BLE GATT（HPC 端点） | Table1（dataType 0x0E）会话，主控制通道 |
| BLE GATT（MC 端点） | Table2（dataType 0x0F）会话 |
| 经典 SPP（RFCOMM） | 老设备的 Table1/Table2 复用通道，同一帧格式 |

### 1.2 GATT UUID（`SonyGatt`，与 SC Service/Characteristic 表一致）

- 服务：`5b833eXX-6bc7-4802-8e9a-723ceca4bd8f`，
  `XX=0x20` TANDEM_V2_HPC、`0x21` TANDEM_V2_MC、`0x23` TANDEM_V1_MC、
  `0x25` SSH、`0x0D` TWS 配对、`0x27` LE Audio 能力等。
- 特征：`5b833cXX-…`，`0x60/0x61` HPC TO/FROM ACC、`0x62/0x63` MC TO/FROM ACC、
  `0x93` DETERMINE_MTU、`0x91` WRITABLE_VALUE_LENGTH、`0x68` LE 切换兼容性等。

### 1.3 Tandem 帧格式（`SonyTandemFraming`，SC `ne0.C24171b`）

```
0x3E + escape( [type][seq][len:4 BE][payload][checksum] ) + 0x3C
```

- `type`：0x0E = DATA_MDR（Table1），0x0F = DATA_MDR_NO2（Table2）；ACK 帧独立类型。
- 转义：0x3D 后跟 `byte XOR 0x20`；校验和 = 帧体求和低字节。
- GATT 写使用 `WRITE_TYPE_NO_RESPONSE`；每条 DATA_MDR 对端回 ACK 帧（seq 匹配）。
- BLE 建连握手顺序：enable DETERMINE_MTU 通知 → 等待 `[0x01]` → 读 WRITABLE_VALUE_LENGTH → 开业务通知。
  裸写 Tandem payload（不经帧封装）会被耳机静默丢弃。

## 2. 初始化序列与版本门控

1. `0E 00 00` — CONNECT_GET_PROTOCOL_INFO（体 `[FIXED_VALUE 0x00]`，SC `qe0.C26634y` / `ff0.C16469c`）。
2. RET_PROTOCOL_INFO：
   - V1（SC `qe0.C26580m2`）体 `[0x01][type 0x00][vhi][vlo]` → 2 字节 BE 版本；
   - V2（SC `ff0.C16477k`）体 `[0x01][0x00][v3][v2][v1][v0][ena][ena]` → 4 字节 BE 版本（`m69436c`）。
3. 版本必须命中白名单（V1 9 项 / V2 74 项，逐字节来自 SC 初始化器），否则终止握手。
4. `0E 02 00` — CONNECT_GET_CAPABILITY_INFO；RET `[0x00][counter][idLen][identifier]`
   （SC `qe0.C26624v1` / `ff0.C16471e`；identifier 截断至 128 字节）——能力缓存对账键。
5. `0E 06 00` — CONNECT_GET_SUPPORT_FUNCTION；RET：
   - V1 `[0x00][count][code…]` 扁平单字节表（SC `qe0.C26610s2`）；
   - V2 `[0x00][count]{(code, order)×2 字节对}`（SC `ff0.C16478l`，按 order 排序）。
   该表驱动后续按 FunctionType 动态能力探测。

## 3. 通用消息格式

业务消息 = `[dataType][command][payload]`。GET 体通常 `[inquiredType]`（个别域附带 DisplayLanguage 等），
SET 体 `[inquiredType][…]`，RET/NTFY 回显 inquiredType 在首位。命令字节按域分段：

- 0x00-0x07 CONNECT；0x10-0x19 COMMON；0x20-0x29 POWER；0x40-0x4D LEA；
- 0x50-0x5B EQEBB；0x60-0x69 NCASM；0x92-0x99 ALERT；0xA0-0xA9 PLAY；
- 0xB0-0xB9 SAR（仅 SC）；0xD0-0xD9 GENERAL_SETTING；0xE0-0xE9 AUDIO；0xF0-0xFD SYSTEM。

（V1 与 V2 的分段一致，但个别域命令不同——见各域明细；Table2 的分段另列。）

## 4. V1 Table1 逐项支持（dataType 0x0E）

| # | 功能 | 命令（GET/SET → RET/NTFY） | 构造体 | 响应体 | SC 证据 |
|---|---|---|---|---|---|
| 1 | 协议信息 | 0x00→0x01 | `[0x00]` | `[type][vhi][vlo]` | `qe0.C26634y`/`C26580m2` |
| 2 | 能力信息 | 0x02→0x03 | `[0x00]` | `[0x00][counter][len][id]` | `qe0.C26552h`/`C26624v1` |
| 3 | 设备信息 | 0x04→0x05 | `[DeviceInfoType]`（0x01 型号/0x02 固件/0x03 系列配色/0x04 指南） | `[type][len][utf8]`；系列配色 `[type][series][color]` | `qe0.C26567k` |
| 4 | 支持功能表 | 0x06→0x07 | `[0x00]` | `[0x00][count][code…]` | `qe0.C26538e0`/`C26610s2` |
| 5 | 电量 | 0x10→0x11 / NTFY 0x13 | `[PowerInquiredType]`（0x00 单电池/0x01 左右/0x02 仓） | BATTERY `[t][lv]`；L/R `[t][l][0x00][r]`；仓 `[t][lv]`。扩展 NTFY 0x09（左右、2 字节值）映射为 L/R | `se0.*` |
| 6 | 关机 | SET 0x22 | `[0x00][0x01 USER_REQUEST]`（整帧 `0E 22 00 01`） | —（耳机直接断连） | `qe0.C26581m3` |
| 7 | 音质徽章-编码 | 0x18→0x19 / NTFY 0x1B | `[0x00]` | `[0x00][codec]`（2 字节整；V1 无 LC3） | `qe0.C26527c`/`p0` |
| 8 | 音质徽章-DSEE | 0x14→0x15 / NTFY 0x17 | `[0x00]` | `[0x00][effectType][effectStatus]`（3 字节整） | `qe0.C26568k0`/`y2` |
| 9 | 播放控制能力 | 0xA0→0xA1 | `[0x01 controller]` | `[0x01][volumeStep][buttons][metadata]` | `qe0.C26622v` |
| 10 | 播放状态 | 0xA2→0xA3 / SET 0xA4 / NTFY 0xA5 | GET `[0x01]`；SET `[0x01][0x00][PlaybackControl]`（暂停 01/上一曲 03/下一曲 02/停止 06/播放 07） | `[0x01][enable][status]`（1 播放 2 暂停 3 停止） | `tf0.C28926a` |
| 11 | 播放参数 | 0xA6→0xA7 / SET 0xA8 / NTFY 0xA9 | GET `[0x01][dataType]`；音量 SET `[0x01][0x20][vol]` | 名字 `[0x01][dataType][status][len][utf8]`；音量 `[0x01][0x20][vol]`；V1 NTFY 无内容仅宣告失效→触发延迟重取 | `SC v1 play param 族` |
| 12 | 降噪/环境声能力 | 0x60→0x61 | `[0x02]`（V1 Table1 NC/ASM 固定 0x02） | `[0x02][settingType][ncValue][asmSettingType][count][ids…]`（settingType 决定单麦抗风噪存在性） | `qe0.C26592p` |
| 13 | 降噪/环境声状态 | 0x66→0x67 / SET 0x68 / NTFY 0x69 | GET `[0x02]`；SET `[0x02][effect][settingType][ncValue][asmAdjust][asmMode][asmLevel]`（effect 0x00 关/0x11 调整完成；ncValue 0 单麦/1 双麦风噪） | 同 SET 布局 | `se0.b0` |
| 14 | EQ/EBB 能力 | 0x50→0x51 | `[v1Type][0x00]`（V1 型号：0x01 预设/0x02 EBB/0x03 不可定制预设） | 域内布局 | `qe0.C26572l` |
| 15 | EQ 预设/EBB | 0x52/0x56→0x53/0x57 / SET 0x58 / NTFY 0x59 | 预设 SET `[type][preset][bandCount][bands…]`；Clear Bass SET `[0x02][level -127..127]` | 同构 | `hf0/c` 同构（V1 型号映射） |
| 16 | EQ 扩展信息 | 0x5A→0x5B | `[type]` | 频段数/频点信息 | `SonyEqEbbPayloadParser` |
| 17 | 手势能力（V1 ASSIGNABLE） | 0xF0→0xF1 | `[0x06]`（V1 固定 0x06；V2 的 0x02/0x03 在 V1 是别的域） | `[0x06][keyCount]{[key][keyType][defaultPreset][presetCount]{[preset][actionCount]{[action][function]}}}`；preset 列表空的 key 整体丢弃 | `qe0.t2$b`→`te0.c` |
| 18 | 手势预设读写（V1，preset 级） | 0xF6→0xF7 / SET 0xF8 / NTFY 0xF9 | GET `[0x06]`；SET `[0x06][count][preset…]`（按能力 key 顺序整表写入） | `[0x06][count][preset…]`（越界字节保位 OUT_OF_RANGE 维持索引对齐） | `se0.d` |
| 19 | 手势可用性（V1） | 0xF2→0xF3 / NTFY 0xF5 | `[0x06]` | `[0x06][count][CommonStatus…]`（ENABLE=0x00） | `se0.e` |
| 20 | 智能免摘（V1 SMART_TALKING 0x05） | 0xF2/0xF6/0xFA→0xF3/0xF7/0xFB / SET 0xF8/0xFC / NTFY 0xF5/0xF9/0xFD | 开关 SET `[0x05][0x01 MODE_ON_OFF][ON 0x01/OFF 0x00]`（V1 极性与 V2 相反）；详情 SET `[0x05][0x00 DETAIL_1][灵敏度][voiceFocus][modeOutTime]` | 开关 RET `[0x05][0x00][值]`；状态 RET `[0x05][CommonStatus][effect]`；详情 RET 同 SET 布局 | `ve0.a-e` `qe0.q3/r3` |

V1 枚举要点：Function 表与 V2 碰撞三码（V1 `NC_OPTIMIZER=0x02`、`VOLUME_UP=0x11`、`VOLUME_DOWN=0x12`），解析必须走 V1 专属表（`v1AssignableSettingsFunction`）；
Preset 项目采用 V2 超集表（V1 设备不会上报多余项，SET 由能力表约束）。

## 5. V2 Table1 逐项支持（dataType 0x0E）

| # | 功能 | 命令 | 构造体 | 响应体 | SC 证据 |
|---|---|---|---|---|---|
| 1 | 协议信息 / 能力信息 / 设备信息 / 支持功能 | 0x00/0x02/0x04/0x06 | 同 V1（版本 4 字节 BE；支持功能带 order 对） | 同 V1 | `ff0.C16469c/C16467a/C16471e/C16478l` |
| 2 | 通用状态（固件号/编码/DSEE 徽章） | 0x12→0x13 / NTFY 0x15 | `[CommonInquiredType]`（0x09 固件号 / 0x02 编码 / 0x03 DSEE） | 编码 `[0x02][codec]`（2 字节整，越界整帧拒绝）；DSEE `[0x03][type][state]`（3 字节整） | `ef0.b`/`n$b`/`i$b`/`o$b` |
| 3 | 电量 | 0x22→0x23 / NTFY 0x25 | `[PowerInquiredType]`（0x00/0x01/0x02） | 同 V1 布局 | POWER 族 |
| 4 | 关机 | SET 0x24 | `[0x03 POWER_OFF][0x01 USER_REQUEST]`（整帧 `0E 24 03 01`） | — | POWER_SET_STATUS |
| 5 | LE Audio 设置 | 0x42/0x46→0x43/0x47 / SET 0x48 / NTFY 0x45/0x49 | 可用性 GET `[0x0C]`；取值 GET `[0x0C]`；SET `[0x0C][Enable 0x00/Disable 0x01][0x00 连接方式一并切换/0x01 仅设置]` | STATUS `[0x0C][EnableDisable]`；PARAM `[0x0C][OnOffSettingValue ON=0x00]` | `d30.C15454a`、`LEAInquiredType.CLASSIC_ONLY_LE_CLASSIC_SETTING` |
| 6 | LE Audio 配对历史 | 0x46→0x47 | `[LeaInquiredType]`（0x00/0x01/0x02） | `[type][pairedHistory 0x00 双/0x01 仅经典/0x02 仅 BLE]` | LEA 族 |
| 7 | EQ/EBB（含 ULT/音效） | 0x50-0x5B 全家族 | 预设 SET `[type][preset][bandCount][bands…]`；ULT 型 SET `[type][basePreset][ultMode 0/1/2][bandCount][bands…]`；音效 SET `[0x30/0x33][SoundEffectType 0x00-0x06]`（整帧长 3）；Clear Bass `[type][level]` | 同构 + NTFY 失效宣告 | `gf0.C16901b` `hf0/c` `hf0/d` `gf0.z0/v0` |
| 8 | EQ 扩展信息 | 0x5A→0x5B | `[type]` | 频段/频点表 | `SonyEqEbbPayloadParser` |
| 9 | 降噪/环境声（12 种写入布局） | 0x60-0x69 | 通用前缀 `[type][ValueChangeStatus 0x01][NcAsmOnOffValue 总效果]`；随后按型（例：`MODE_NC_ASM_DUAL…_SEAMLESS`（0x17/0x14）`[NcAsmMode][模式][AmbientSoundMode][level]`；NA 型（0x19）`[NcAsmMode][AmbientSoundMode][level][OnOff][灵敏度]`；`NC_AMB_TOGGLE`（0x30）例外仅 `[function 01/03/04]` 三字节帧） | RET/NTFY_PARAM 回显同构 | `rf0/{d…p}`（本次抽核 `rf0.g` ✅） |
| 10 | 设备告警 | 0x92→0x93 / SET 0x94 / NTFY 0x95 / SET 0x98 / NTFY 0x99 | 布防 SET `[inquiredType 0x00 固定消息/0x04 前台/0x05 LE][Enable 0x00]`；应答 SET `[0x00][msgType][action 1 确认/0 取消]` | NTFY_PARAM `[0x00/0x02][msgType][actionType]`（多点/LDAC/LE 切换确认流；msgType 表 44-65/112-119 及 flexible 12-17） | `bf0.AbstractC5694r` `C14663l0` |
| 11 | 播放控制/参数 | 0xA0-0xA9 | GET `[PlayInquiredType 0x01]`；SET `[0x01][0x00][PlaybackControl]`；音量 SET `[0x20][vol]`；V2 RET_PARAM 可带内容（歌名等） | 状态 `[0x01][enable][status]`；元数据 `[type][dataType][status][len][utf8]`；失效 NTFY→50ms 防抖整块重取 | `tf0.C28926a` 族 |
| 12 | 通用设置 GS（多点开关） | 0xD0-0xD9 | 能力 GET `[slot 0xD1-0xD4][DisplayLanguage 0x01]`；布尔 SET `[slot][0x00 BOOLEAN][ON 0x00/OFF 0x01]` | 能力 `[slot][settingType][stringFormat][titleLen][title…][descLen][desc…]`；状态 `[slot][EnableDisable]`；参数 `[slot][settingType][value]`；NTFY_PARAM 同参数 | `if0/{a,b,h,i,j,o,p}` `jf0/a` |
| 13 | AUDIO 域-DSEE 开关 | 0xE0/0xE6→0xE1/0xE7 / SET 0xE8 / NTFY 0xE9 | 能力/取值 GET `[0x01 一代 / 0x0B 带禁用原因]`；SET `[inq][AUTO 0x01/OFF 0x00]` | 能力 RET 带 `UpscalingType` 代际字节（0 DSEE HX/1 DSEE/2 Extreme/3 Ultimate）；PARAM `[inq][UpscalingTypeAutoOff]` | `cf0.e0` |
| 14 | AUDIO 域-连接质量 | 0xE2/0xE6→0xE3/0xE7 / SET 0xE8 | 可用性 GET `[inq 0x00 经典/0x02 带 LDAC/0x05 双模]`；取值 GET `[inq]`；SET `[inq][PriorMode 0 音质优先/1 稳定优先/2 低延迟]` | STATUS `[inq][EnableDisable]`（2 字节整，越界整帧拒绝）；PARAM `[inq][PriorMode]`（NTFY 可带流迁移方向） | `cf0.q0/s0/t0/m`、`r60.b`、`PriorMode` ✅ |
| 15 | 手势能力（V2 ASSIGNABLE） | 0xF0→0xF1 | `[0x03 ASSIGNABLE / 0x0E WITH_LIMITATION]`（后者在 type 与 key 数之间多一个 LimitationType 字节） | `[type][limit?][keyCount]{[key][type][defaultPreset][presetCount]{[preset][单功能对数][多功能条目数]{` + 单功能对 `[action][function]` + 多功能条目 `[action][defaultFunction][funcCount][functions…]`}} | `cg0.c`（key 层）`cg0.d`（preset 层）`cg0.a`（多功能条目）✅ |
| 16 | 手势预设读写（V2 preset 级） | 0xF6→0xF7 / SET 0xF8 / NTFY 0xF9 | GET `[type]`；SET `[type][count][preset…]`（1-255，整表按能力顺序） | `[type][count][preset…]` | `bg0.a/b` ✅ |
| 17 | 手势动作映射（V2 EXT_PARAM） | 0xFA→0xFB / SET 0xFC / NTFY 0xFD | GET `[type]`；SET `[type][映射组数]{[preset][对数]{[action][function]}}`（V1 无此路径——官方序列化直接抛异常） | 同构 | `bg0.a/b` + `cg0.e/a` ✅ |
| 18 | 智能免摘（V2 TYPE1 0x02 / TYPE2 0x0C） | 0xF0-0xFD | 开关 SET `[type][OnOffSettingValue ON=0x00/OFF=0x01][0x01]`（V2 极性与 V1 相反）；TYPE1 详情 `[type][灵敏度][voiceFocus 0x00/0x01][modeOutTime]`；TYPE2 详情 `[type][灵敏度][modeOutTime]` | PARAM `[type][OnOff]`；EXT 详情同构 | `qe0.C26538e0` 等族 |
| 19 | 佩戴状态 | 0xF6→0xF7 / NTFY 0xF9 | `[0x06 WEARING_STATUS_DETECTOR]` | `[type][status 0-3][result 0/1]` | SYSTEM 族 |
| 20 | Quick Access | 0xF0/0xF2/0xF6→0xF1/0xF3/0xF7 / SET 0xF8 / NTFY 0xF5/0xF9 | GET `[0x0D]`；SET `[0x0D][count][serviceId…]`（serviceId 是 SAR 服务 ID，非手势 ID；原始 ID 直通以兼容新服务） | 能力带动作/默认/可用功能表；PARAM `[0x0D][count][ids…]` | SYSTEM 族 |

## 6. V2 Table2 逐项支持（dataType 0x0F，MC 端点）

命令分段：0x06-07 CONNECT；0x20-29 POWER；0x30-3D PERIPHERAL；0x40-4B VOICE_GUIDANCE；
0x50-5B SAFE_LISTENING；0x60-69 LEA；0x70-7C PARTY；0xF0-FD SYSTEM。

| # | 功能 | 命令 | 构造体 | 响应体 | SC 证据 |
|---|---|---|---|---|---|
| 1 | 支持功能表 | 0x06→0x07 | `[0x00]` | 同 Table1 V2 布局（FunctionType 属 NO_2 表） | `ff0.C16478l` |
| 2 | 多点-能力 | 0x30→0x31 | `[type 0x00/0x02]` | `[type][maxPaired][maxConnected][fileTransfer]`（5 字节整帧） | `lg0.p` |
| 3 | 多点-状态 | 0x32→0x33 / NTFY 0x35 | `[type]` | `[type][bluetoothMode][EnableDisable]` | `lg0.w` |
| 4 | 多点-设备表 | 0x36→0x37 / NTFY 0x39 | `[type]` | `[type][count]{[addr:17][connectedOrder:1][可选 CoD:3][nameLen:1][name]}[播放权持有者 connectedOrder]`（connectedOrder 是 1 基连接序号） | `lg0.s`/`mg0.b` |
| 5 | 多点-连接/断开/取消配对 | SET 0x3C / NTFY 0x3D | `[type][action 0x00 断开/0x01 连接/0x02 取消配对][addr ASCII]` | NTFY 结果 `[type][action][result][addr:17]` | `lg0` SET_EXT 族 |
| 6 | 配对模式进出 | SET 0x34 | `[type][PeripheralBluetoothMode 0x00 NORMAL/0x01 INQUIRY_SCAN][Enable 0x00]`（模式字节即进出配对） | — | `lg0.e0.b.h`、`PeripheralBluetoothMode` ✅ |
| 7 | 声源切换开关 | SET 0x38 / GET 0x36 | SET `[0x01][OnOff ON=0x00]` | RET `[0x01][onOff]`；NTFY 追加结果字节 | `lg0.u`/`lg0.l` |
| 8 | 固定声源地址 | SET 0x3C | `[0x01][addr ASCII]` | NTFY `[0x01][result][addr:17]` | `lg0` 族 |
| 9 | 音乐转接（Music Hand Over） | SET 0x38 | `[0x03][OnOffSettingValue]`（UI 层极性反转在仓储做，同官方 `x30/d.a`） | RET `[0x03][onOff]` | `lg0.b0.b.h`、`mg0.a.a` |
| 10 | POWER / VOICE_GUIDANCE / SAFE_LISTENING / LEA / PARTY / SYSTEM | 各域 GET/RET/NTFY | GET `[inquiredType]` | 通用 Table2Generic 解析（域+类型+原始值），typed codec 未接入（项目当前无对应产品功能） | 分段见上 |

## 7. V1 Table2（dataType 0x0F）

仅 PERIPHERAL（0x30-0x39/0x3C/0x3D，类型 0x01 经典多点管理）与 VOICE_GUIDANCE（0x40-0x49，
类型 0x01 语音引导设置）的 GET/通用解析。SPP 传输下 Table2 外层帧 type 复用 0x0E、收发时还原 0x0F。

## 8. 与 Sound Connect 的一致性总结

### 8.1 已实现且逐字节一致（本次/既往 jadx 核对）

- 帧层、GATT/SPP 传输行为、握手序列、版本白名单（V1 9 / V2 74）；
- V1/V2 全部已实现命令字节（两套 Command 总表核对）；
- 电量（含 0x09 扩展 NTFY 与占位语义）、关机、设备信息；
- 降噪/环境声（V1 全套 + V2 十二种写入布局 + NA 灵敏度 + 抗风噪单/双麦语义）；
- EQ/EBB（预设/Clear Bass/ULT/音效/扩展信息，含 V1-V2 型号映射）；
- 播放控制（状态/参数/音量/元数据失效重取，V1/V2 差异处理）;
- 手势 ASSIGNABLE_SETTINGS（V1 preset 级 + V2 preset/映射两级，枚举 46+28+9 全对齐）;
- 智能免摘（V1/V2 两套极性与布局差异均对齐）、佩戴状态、Quick Access；
- GS 多点开关、ALERT 确认环（固定/前台/LE 消息类型表）、AUDIO 域（DSEE 代际 + 连接质量 PriorMode）;
- LE Audio 设置与配对历史、Table2 多点管理全家（能力/状态/设备表/连接动作/配对模式/声源/转接）。

### 8.2 SC 有、项目未实现的域（不影响现有功能）

- **V1**：VPT（0x40-0x49，360RA/声场）、SPORTS（0xB0-0xBD）、SENSE/OPT/UPDT/LOG、
  V1 GENERAL_SETTING（0xD0-0xD9）、COMMON 的蓝牙设备信息/连接状态/礼宾/链路控制；
- **V2**：UPDT（固件升级 0x30-0x3F）、SENSE（0x70-0x7B）、OPT（0x80-0x89）、
  SAR_AUTO_PLAY（0xB0-0xB9）、LOG、COMMON_SET_PARAM（0x18）；
- **Table2**：VOICE_GUIDANCE / SAFE_LISTENING / PARTY / SYSTEM 的 typed codec（当前仅通用解析）。

### 8.3 已知的语义差异（有意为之，均注明于源码）

| 差异 | 位置 | 说明 |
|---|---|---|
| V1 手势 Function 未知码回落 null（丢弃条目） | `v1AssignableSettingsFunction` | 官方回落 NO_FUNCTION 显示"无功能"；项目视为坏数据跳过，更保守，实际无影响 |
| V2 能力表零计数 preset 条目的丢弃路径 | V2 能力解析 | 官方在序列化层 `(0,0)` 拒绝；项目在组装层因 actions 为空丢弃——终态一致 |
| Preset 枚举采用 V2 超集共用 | `AssignableSettingsPreset` | V1 设备不上报超集项；SET 由设备能力表约束，不会构造非法帧 |

## 9. 证据与复验方法

- 每个布局在源码注释中标注 SC 类名（如 `SC 'se0.d'`），可直接用 JADX MCP 打开对应类复核；
- byte-level 单测：`app/src/test/java/dev/sonypods/headphones/` 与 `…/protocol/`
  （含 `SonyTandemV1Table1ProtocolTest`、`SonyTandemV2Table1ProtocolTest`、
  `SonyAssignableSettingsProtocolTest`、`SpeakerProtocolTest` 等）；
- 完整 inventory 与历史审计：`SOUND_CONNECT_13_2_1_PROTOCOL_AUDIT.md`、
  `build/jadx_mcp/protocol_inventory.json`（Git 忽略，需时重新生成）；
- 逆向操作规范：`SOUND_CONNECT_REVERSE_ENGINEERING.md`。
