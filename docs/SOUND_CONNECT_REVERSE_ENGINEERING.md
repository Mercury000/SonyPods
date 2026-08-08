# Sound Connect 逆向与证据维护

> 更新日期：2026-08-08。本文记录方法与证据规则，不再把历史常量表当作当前协议结论。

## 1. 范围

SonyPods 的 Tandem 逆向以目标版本 Sound Connect 为参照。目前可复核的基线是：

- App：`com.sony.songpal.mdr`；
- 版本：Sound Connect 13.2.1；
- 静态分析：已打开 APK 的 JADX MCP；
- 可追踪结论：[Sound Connect 13.2.1 协议审计](SOUND_CONNECT_13_2_1_PROTOCOL_AUDIT.md)。

APK 内包含耳机、音箱、Party/DJ、Karaoke、照明、Fiestable、FOTA 和日志等多个产品域。发现 Command 不代表该命令适用于耳机，更不代表它可安全发送给所有型号。

## 2. 证据层级

1. 实际目标耳机抓包与可重复操作；
2. 同版本 APK 中 request builder、response parser 和调用链；
3. Command / FunctionType / InquiredType 枚举；
4. 当前项目源码与测试；
5. 名称推断、旧文档或其他项目。

枚举只证明“协议中存在一个标识符”。要确认一个功能，需要同时确认 payload 结构、方向、触发前置条件、适用 FunctionType 和结果通知。

## 3. JADX MCP 工作流

### 3.1 首先确定表和域

针对目标功能，记录：

```text
协议版本：V1 / V2
Table：Table1 / Table2
DataType：0x0E / 0x0F（内部 Tandem marker）
Command：GET / RET / SET / NTFY / 扩展命令
Family：power / system / peripheral / voiceguidance / ...
InquiredType：字节值与枚举名称
FunctionType：能力门控的字节值与所在 table
```

先读取对应 `Command`、`InquiredType`、`FunctionType` 类，再追踪该 `InquiredType` 的 request builder 和 `SET` 的调用方。

### 3.2 确认 payload，不靠命名猜测

需要回答：

- request payload 除 command / inquired type 外有哪些字段；
- bool、enum、长度、可选字段和校验规则；
- response 是 `RET_*`、`NTFY_*` 还是两者；
- SET 后是否需要显式 GET 重新读取；
- 是否因为 LE Audio、连接模式、固件或耳机状态而禁用；
- 是否有确认对话、延时、断线等行为。

例如 `POWER_OFF` 的 FunctionType 和 V2 Power InquiredType 可以从枚举确认，但在没有追到实际 builder 或抓包前，不能把“值为 1”之类 payload 写为事实。

### 3.3 保存证据

本机的原始 MCP 输出应放在 `build/jadx_mcp/`，包括：

- `protocol_inventory.json`；
- `sources/*.json`；
- 由调用链提取的最小源码片段；
- 可复现实验的抓包和时间戳。

该目录包含 APK 反编译材料和中间产物，因此保持 Git 忽略。Git 中只提交经过核验、可公开复现的摘要、fixture 和 `docs/` 结论。

## 4. APK 更新后的重新审计

1. 记录 APK 的 package、versionName、versionCode、SHA-256；
2. 用 JADX MCP 导出四套 Command、FunctionType、InquiredType 与 protocol whitelist；
3. 对比旧 `protocol_inventory.json`：新增、删除和 byte code 变化均需人工复核；
4. 重跑 transport、V1/V2 Table1/Table2、FunctionType 和 parser fixture；
5. 更新审计报告、[协议实现现状](PROTOCOL_IMPLEMENTATION.md) 和 [耳机命令目录](HEADPHONE_COMMAND_CATALOG.md)；
6. 不因同名枚举而自动复用旧 payload。

## 5. 结论写法

推荐写法：

| 状态 | 含义 |
|---|---|
| 已验证 | 有 builder/调用链或抓包，且包含适用条件与精确 payload |
| 协议已知 | 已确认 Command / InquiredType / FunctionType，但 payload 或行为仍缺证据 |
| 待验证 | 仅有枚举、名称或间接线索 |
| 不适用 | 明确属于非耳机产品域或依赖 App/云服务 |

把 `待验证` 保留下来比写入错误的“完整协议”更有价值。

## 6. 与项目实现的关系

逆向结论进入代码前，必须完成 [协议功能扩展指南](PROTOCOL_EXTENSION_GUIDE.md) 所述的完整垂直切片。审计 inventory 的 command 覆盖数不是产品功能覆盖率。
