# SonyPods 文档

> 当前基线：SonyPods 工作区（2026-08-08）与 Sound Connect 13.2.1。
> 详细项目文档统一维护在本目录；仓库根目录的 `README.md` / `README_EN.md` 仅作为项目首页。

## 文档导航

### 当前实现

- [架构说明](ARCHITECTURE.md)：进程、transport、协议、adapter、repository 与 UI 的边界。
- [协议实现现状](PROTOCOL_IMPLEMENTATION.md)：当前真实支持的 V1/V2、Table1/Table2、SPP/GATT、typed codec 与产品 API。
- [协议技术说明](PROTOCOL_SPEC.md)：逐项协议面、帧构造与响应布局、与 Sound Connect 13.2.1 的一致性核对结论（2026-08-29 全量复验）。
- [新机型适配](DEVICE_ADAPTATION.md)：基于动态能力探测和真实协议证据接入新设备。

### 协议扩展

- [协议功能扩展指南](PROTOCOL_EXTENSION_GUIDE.md)：增加“只发包即可由耳机完成”的功能时应遵循的完整垂直切片流程。
- [耳机命令目录](HEADPHONE_COMMAND_CATALOG.md)：已实现、协议已知但未接入、待验证及排除项；包含关机等候选功能。

### 逆向与证据

- [Sound Connect 逆向与证据维护](SOUND_CONNECT_REVERSE_ENGINEERING.md)：如何用 JADX MCP 重新审计、如何管理证据、如何避免把推测写成协议事实。
- [Sound Connect 13.2.1 协议审计](SOUND_CONNECT_13_2_1_PROTOCOL_AUDIT.md)：完整 Command、FunctionType、InquiredType inventory 与 2026-08-08 修复记录。

## 权威性

发生冲突时按以下顺序处理：

1. 目标版本 Sound Connect 的 JADX 调用链与实际耳机抓包；
2. 当前 Kotlin 源码和通过的 byte-level 单测；
3. 本目录的现状文档；
4. 历史提交、旧笔记或第三方实现。

`SOUND_CONNECT_13_2_1_PROTOCOL_AUDIT.md` 中的完整清单是 13.2.1 的审计证据，不代表所有枚举均适用于耳机，也不代表项目已接入对应功能。功能只有同时具备 builder、typed parser、codec、adapter、repository/API、能力门控和测试，才可标记为“已实现”。

## 文档维护规则

- 不在仓库根目录新增专项设计文档；新增文档放入 `docs/` 并更新本索引。
- 不复制维护大段易过期枚举；完整清单以审计报告和 `build/jadx_mcp/protocol_inventory.json` 为准。
- 协议值必须注明版本、Table、Command family、InquiredType、方向和证据。
- 未确认的 payload 字节必须写成 `待验证`，禁止以命名或枚举位置推断。
- APK 升级、协议实现变更或产品 API 新增后，同步更新 `PROTOCOL_IMPLEMENTATION.md` 和 `HEADPHONE_COMMAND_CATALOG.md`。
