# 耳机侧命令目录与扩展候选

> 基线：Sound Connect 13.2.1 + SonyPods 当前工作区（2026-08-08）。
> 本表只用于规划“不依赖 Sound Connect App 服务、可由耳机固件在收到 Tandem 包后执行”的功能。`协议已知` 不等于 payload 已验证，更不等于当前可安全发送。

## 1. 状态定义

| 状态 | 定义 |
|---|---|
| 已接入 | typed builder/parser、codec、adapter、repository/API、能力门控和测试均存在 |
| 部分接入 | 已有读取或部分设备路径，但缺 setter、完整 response 或 Table2 链路 |
| 协议已知 | Command / FunctionType / InquiredType 已确认，仍缺完整 payload/调用链或产品垂直切片 |
| 待验证 | 只有枚举或间接线索，不能据此发包 |
| 排除 | 明确依赖 App/云/内容传输，或属于非目标耳机产品域 |

## 2. 当前已接入

| 功能 | 版本/Table | 耳机侧独立 | 当前层级 | 备注 |
|---|---|---:|---|---|
| 设备/固件信息 | V1/V2 T1 | 是 | 已接入 | typed GET/RET，连接刷新 |
| 电量（single/L/R/cradle） | V1/V2 T1 | 是 | 已接入 | capability 决定 query 类型 |
| NC/ASM/环境声等级/人声 | V1/V2 T1 | 是 | 已接入 | V1/V2 `0x02` 按上下文消歧 |
| EQ/EBB/Clear Bass | V1/V2 T1 | 是 | 已接入 | typed GET/SET + EQ 引擎 |
| 播放控制 | V1/V2 T1 | 是 | 已接入 | Tandem + Android media fallback 策略 |
| LE Audio 状态/历史 | V2 T1 | 是 | 部分接入 | 部分 InquiredType 的 query/parser |
| Quick Access 状态 | V2 T1 System | 是 | 部分接入 | 当前偏只读，未形成完整设置 API |
| 佩戴状态 | V1/V2 T1 | 是 | 部分接入 | T1 query/parser，Table2 扩展未接 |

## 3. 高优先级扩展候选

### 3.1 电源与充电

| 功能 | 版本/Table | FunctionType | Command family | InquiredType | 独立完成 | 当前实现 | 风险/下一证据 |
|---|---|---|---|---|---:|---|---|
| 立即关机 | V1 T1 | `POWER_OFF 0x21` | `COMMON_SET_POWER_OFF 0x22` | `FIXED_VALUE 0x00` | 是 | 协议已知 | 精确 SET body、NTFY/断线时序待抓包 |
| 立即关机 | V2 T1 | `POWER_OFF 0x23` | `POWER_SET_STATUS 0x24` | `POWER_OFF 0x03` | 是 | 协议已知 | 精确 SET body、前置状态待调用链确认 |
| 自动关机 | V1 T1 | `AUTO_POWER_OFF 0xF4` | SYSTEM GET/SET PARAM | `AUTO_POWER_OFF 0x04` | 是 | 协议已知 | capability、timeout 枚举和 NTFY body 待验证 |
| 自动关机（佩戴检测） | V2 T1 | `0x24/0x25` | POWER GET/SET PARAM | `0x04/0x05` | 是 | 协议已知 | 两种能力与超时/禁用值需分开建模 |
| Power saving | V1 T1 / V2 T1 | V1 `0xF2`；V2 `0x26` | SYSTEM 或 POWER | V1 `0x02`；V2 `0x06` | 是 | 协议已知 | bool/状态命令和固件差异待验证 |
| Battery safe mode | V2 T1 | `0x2B` | POWER | `0x0B` | 是 | 协议已知 | 适用产品和状态值待验证 |
| Caring Charge | V2 T1 | `0x2C` | POWER | `0x0C` | 是 | 协议已知 | 阈值是否另走 T2 需按 FunctionType 分流 |
| Caring Charge threshold | V2 T2 | `0x22` | POWER | `0x01` | 是 | 协议已知 | T2 typed codec、阈值范围和 GET/SET/NTFY body 待验证 |
| BT standby | V2 T1 | `0x2D` | POWER | `0x0D` | 是 | 协议已知 | 与连接断开/唤醒行为需实机验证 |
| Stamina | V2 T1 | `0x2E` | POWER | `0x0E` | 是 | 协议已知 | 参数枚举与副作用待验证 |
| Auto standby | V2 T2 | `0x20` | POWER | `0x00` | 是 | 协议已知 | T2 typed codec 和 timeout/条件字段待验证 |

### 3.2 配对、多设备与信源

| 功能 | 版本/Table | FunctionType | InquiredType | 独立完成 | 当前实现 | 风险/下一证据 |
|---|---|---|---|---:|---|---|
| Classic BT 配对设备管理 | V1 T2 | `0x38` | `0x01` | 是 | 协议已知 | 列表结构、删除语义、危险操作确认 |
| Classic BT 配对设备管理 | V2 T2 | `0x30` | `0x00` | 是 | 协议已知 | typed peripheral payload 待实现 |
| Source switch control | V2 T2 | `0x31` | `0x01` | 是 | 协议已知 | source id 与切换 ACK/NTFY 待验证 |
| 带 Bluetooth Class of Device 的设备管理 | V2 T2 | `0x32/0x33` | `0x02` | 是 | 协议已知 | Classic/LE FunctionType 区分和 entry schema 待验证 |
| Music hand-over setting | V2 T2 | 待核对 | `0x03` | 可能 | 待验证 | 需确认 FunctionType 与耳机型号调用链 |
| Link auto switch（headsets） | V2 T2 | `0xF8` | System `0x0A` | 可能 | 协议已知 | 可能涉及多设备生态，确认是否无需 App service |

配对数据库删除、取消所有配对等操作属于高风险，即使协议明确也必须二次确认，且不得通过遍历未知值试验。

### 3.3 Voice Guidance

| 功能 | 版本/Table | FunctionType / InquiredType | 独立完成 | 当前实现 | 边界 |
|---|---|---:|---|---|---|
| Voice Guidance setting | V1 T2 | Function `0x39` / Inquired `0x01` | 部分 | 协议已知 | 若只切换已安装语言/开关可接；语言包传输排除 |
| On/off only | V2 T2 | Function `0x45` / Inquired `0x03` | 是 | 协议已知 | SET/NTFY body 待验证 |
| Volume | V2 T2 | Function `0x42/0x43` / Inquired `0x20/0x21` | 是 | 协议已知 | 步数、范围与 capability 待验证 |
| Battery level voice | V2 T2 | Function `0x46` / Inquired `0x30` | 是 | 协议已知 | bool/enum 待验证 |
| Power on/off sound | V2 T2 | Function `0x47` / Inquired `0x31` | 是 | 协议已知 | bool/enum 待验证 |
| ULT beep | V2 T2 | Function `0x48` / Inquired `0x32` | 是 | 协议已知 | 仅适用支持 ULT 的设备 |
| Language switch / language data transfer | V1/V2 T2 | 多个 FunctionType | 部分/否 | 待验证或排除 | 切换已安装语言可研究；下载和传输语言包依赖内容流程，不纳入简单发包功能 |

### 3.4 按键、手势、佩戴与助手

| 功能 | 版本/Table | FunctionType | InquiredType | 独立完成 | 当前实现 | 备注 |
|---|---|---|---|---:|---|---|
| Assignable settings | V1 T1 / V2 T1 | V1 `0xF6`；V2 `0xF3/0xFE` | V1 `0x06`；V2 `0x03/0x0E` | 是 | 协议已知 | capability 中的可分配 action schema 待验证 |
| Voice assistant settings | V2 T1 | `0xF4` | `0x04` | 部分 | 协议已知 | 耳机保存的 assistant selection 可接；第三方授权/安装流程排除 |
| Voice assistant wake word | V2 T1 | `0xF5` | `0x05` | 是 | 协议已知 | 支持范围和冲突状态待验证 |
| Head gesture | V2 T1 | `0xFF`（合法 FunctionType） | `0x0F/0x10` | 是 | 协议已知 | 必须处理 `0xFF` 与 OUT_OF_RANGE sentinel 冲突 |
| Playback by wearing | V1/V2 T1 | V1 `0xF3`；V2 `0xF1` | V1 `0x03`；V2 `0x01` | 是 | 部分读取 | setter 与状态通知待接 |
| Quick Access setting | V2 T1/T2 | T1 `0xFD`；T2 `0xF2` | T1 `0x0D`；T2 `0x02` | 是 | 部分接入 | 当前只读路径与 T2 easy setting 需统一建模 |
| Wearing status checker/position | V2 T2 | `0xF0/0xF6/0xFF` | `0x00/0x07/0x08` | 是 | 协议已知 | 需区分检查、设备信息和传感器判断 |
| Mic on/off by headphone operation | V2 T2 | `0xF9` | `0x0B` | 是 | 协议已知 | 需确认只是耳机设置，不依赖系统麦克风服务 |
| Function change | V2 T2 | `0xFA` | `0x0C` | 是 | 协议已知 | action 表和限制条件待验证 |

## 4. 中优先级候选

| 域 | 功能 | 协议位置 | 当前状态 | 注意事项 |
|---|---|---|---|---|
| Safe Listening | HBS/TWS 1/2、Safe Volume、Max Volume Limit | V2 T2 `0x50..0x5B`，Inquired `0x00..0x05` | 常量/枚举，未产品化 | 耳机侧限音设置可研究；历史/报表/云同步排除 |
| Audio | connection mode、upscaling、BGM、voice contents、sound leakage reduction | V1/V2 T1 | 协议已知，API 不完整 | 切换可能导致连接重建，需事务状态机 |
| Optimizer | NC optimizer、sound field optimization、TV sound booster | V1/V2 T1 OPT | 协议已知 | 部分流程可能需要测量数据，不能只实现触发按钮 |
| Spatial/head tracker | head tracker、SAR optimization、LEA restrictions | V2 T1/T2 | 待验证 | 可能依赖系统 spatializer、校准或内容，不保证纯耳机侧 |
| Auto Play/SAR | SAR AutoPlay T1 | 协议已知 | 未接入 | 若只是耳机开关可接；App 场景/内容规则排除 |
| General Settings | GS 1..4 等 | V1/V2 T1 | 待验证 | 必须逐 FunctionType 找到业务语义，禁止做通用 blind setter |

## 5. 高风险或不作为简单功能接入

| 域 | 结论 | 原因 |
|---|---|---|
| FOTA | 单独项目，不按普通 setter 接入 | 包下载、签名/版本、分片、长包、恢复和断电风险 |
| Firmware/log upload | 排除普通功能范围 | App/服务端流程、隐私与大数据传输 |
| Voice guidance language transfer | 排除简单发包 | 需要语言资源和传输状态机；仅本地开关/音量可单独接入 |
| Reset / pairing database clear | 可研究但高风险 | 破坏性、需确认、需恢复和实机 fixture |
| Party/DJ/Karaoke/Lighting/Fiestable | 默认排除 | 多数属于 Sony 音箱/派对产品域，不是 SonyPods 核心耳机目标 |

## 6. 推荐实施顺序

1. 关机（V1/V2 T1），先完成 builder 调用链和断线成功语义；
2. Auto standby / Auto power off / Power saving；
3. Caring Charge、Battery safe、Stamina；
4. Voice Guidance 的纯本地 on/off、volume、提示音；
5. Source switching 和只读配对设备列表，再评估删除操作；
6. Assignable/Quick Access/gesture/wearing setters；
7. Safe volume / max volume limit；
8. optimizer/spatial 等需要额外系统或校准流程的域。

每项开始前使用 [协议功能扩展指南](PROTOCOL_EXTENSION_GUIDE.md) 的调查表，完成 payload 证据后再编码。

## 7. 证据索引

- 全量 Command / FunctionType / InquiredType：[Sound Connect 13.2.1 协议审计](SOUND_CONNECT_13_2_1_PROTOCOL_AUDIT.md)
- 本地自动 inventory：`build/jadx_mcp/protocol_inventory.json`（Git 忽略）
- 当前协议源码：`app/src/main/java/dev/sonypods/protocol/`
- 当前 codec/adapter：`app/src/main/java/dev/sonypods/headphones/`
- 当前产品 API：`app/src/main/java/dev/sonypods/data/SonyHeadphoneRepository.kt`
