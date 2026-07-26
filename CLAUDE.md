# CLAUDE.md — SonyPods 迁移工作指南（agent 接手文档）

> **进度状态（2026-07-26）**：阶段 1–5 已全部完成并分阶段提交（`7aa69a1`→`3cfae90`），后续真机修复见 `4310182`/`01e7020`/`03ae973`。包名已是 `dev.sonypods`，品牌已是 SonyPods。阶段 6 真机回归进行中：LinkBuds S 的 NC/ASM 写入极性已按真机抓包修正（0x17 的 ncAsmEffect 是 01=开/00=关，**不**走 OnOffSettingValue 反转约定——OpenBuds 上游同样带此 bug）；WH-1000XM4 / WF-1000XM5 尚未真机验证。本文其余内容保留作迁移过程参考。

> 这份文档是一次**跨 agent 交接**。上一个 agent 无法在其环境编译（云端沙箱无 Android SDK、Google/Maven/Gradle 源被墙、设备 VM 无工具链），已完成「阶段 1」纯源码引入并交接给你。你运行在能编译的本机环境，请**每完成一个阶段就 `.\gradlew.bat testDebugUnitTest assembleDebug` 验证一次**，编译错误当场修，不要盲推多个阶段。

---

## 0. 怎么用这份文档

1. 先读本文档全文。
2. 读参考工程的文档：`D:\mercu\Desktop\OpenBuds\CLAUDE.md`、`OpenBuds\docs\PROTOCOL_GUIDE.md`、`OpenBuds\docs\FEATURE_STATUS.md`（Sony 协议与能力的权威说明）。
3. 确认阶段 1 是否已应用到本工程（见 §4）。若未应用，先应用再 build 出基线。
4. 按 §7 的阶段推进，每阶段 build 验证。

---

## 1. 目标

把本工程 **SonyPods（当前实为 OppoPods，package `moe.chenxy.oppopods`，版本 2.0.7）** 的**协议/传输/设备识别引擎**，从「OPPO RFCOMM 私有协议」整体替换为 **OpenBuds 的 Sony Tandem BLE/SPP 协议栈**。

- **保留** OppoPods 的 HyperOS 系统集成外壳：超级岛、系统蓝牙设置注入、融合设备中心、型号伪装（把耳机伪装成受支持的小米耳机）、快捷弹窗、系统通知。
- **所有场景都改用 OpenBuds 的实现**：设备图片、降噪/通透切换、hook 能力、电量读取等。
- **移除一切 OPPO 相关**，替换为 Sony。OPPO 独有、Sony 无对应的功能（自适应 ANC、智能/轻/中/深降噪档、通透人声增强、游戏模式/低延迟、欢律 root 导图）**直接删除，不做映射**。

一句话：**OppoPods 的外壳 + hook/广播基础设施保留，把「大脑」（RfcommController + Packets + OPPO 型号表）换成 OpenBuds 的 Sony 引擎，并把所有 OPPO 语义重映射为 Sony。**

---

## 2. 已锁定的决策（用户已确认，勿再改）

| 决策 | 选择 |
|------|------|
| **传输层运行位置** | **方案 A**：Sony 传输/协议/Repository 跑在**模块 App 进程**（前台 Service），系统进程的 hook 退化为「广播桥」。支持 GATT+SPP 全型号。详见 §6。 |
| **HyperOS 系统集成** | **全部保留**：型号伪装 + 系统蓝牙设置注入 + 融合设备中心 + 超级岛 + 通知，全部重指 Sony。 |
| **首批目标型号** | **只做 OpenBuds 现有三款**：WH-1000XM4 / LinkBuds S / WF-1000XM5。 |
| **包名** | `dev.sonypods`（Sony 引擎已用此包；后续品牌/applicationId 重命名见 §7 阶段 5）。 |
| **OPPO 独有功能** | 直接移除，不做 Sony 映射。 |
| **广播命名空间** | 从 `chen.action.oppopods.*` 迁到 `dev.sonypods.action.*`（OpenBuds 的 GPL 边界文档明令禁用 `chen.action.*`）。 |

---

## 3. 两个工程与源头位置

- **目标工程（要改的）**：`D:\mercu\Desktop\SonyPods`，package `moe.chenxy.oppopods`。LSPosed 模块，Kotlin + Compose + Miuix。作用域 `com.android.bluetooth` / `com.milink.service` / `com.xiaomi.bluetooth`。
- **Sony 能力源头（参考+复制来源）**：`D:\mercu\Desktop\OpenBuds`，package `dev.ignotus.openbuds`。索尼 Sound Connect 净室重写，含完整 Sony Tandem 协议栈 + BLE/SPP 传输 + 适配器/profile + Repository。**Sony 相关的一切实现都以此为准**。

### 3.1 OppoPods 架构（现状）
- 传输：`pods/RfcommController.kt`（单例，跑在 `com.android.bluetooth` 进程内），RFCOMM UUID `0000079A-D102-11E1-9B23-00025B00A5A5`，OPPO 私有包 `AA+len+0000+cmd(2B LE)+seq+payLen(2B)+payload`（`pods/Packets.kt`）。
- 设备识别：名字含 "oppo"。
- 触发：BT 进程 `hook/HeadsetStateDispatcher` 监听 A2DP 状态自动连 RFCOMM。
- 状态分发：BT 进程广播 `chen.action.oppopods.*` 到 app/milink/xiaomi/settings。
- App UI（`ui/MainUI` 等）无蓝牙逻辑，全靠广播。
- Hook：`BluetoothUpstreamHeadsetHook`（伪装小米耳机 Binder）、`SettingsHeadsetHook`（系统设置注入，当前在 HookEntry 中被注释）、`MiBluetoothToastHook`（通知+岛+ANC循环）、`milink/MiLinkServiceHook`+`MiLinkSpatialAudioHook`（融合中心/超级岛）。

### 3.2 OpenBuds 架构（能力来源）
- 传输：`ble/SonyBleClient`（BLE GATT，Tandem V2 HPC/MC、V1 MC，完整握手）+ `ble/SonySppTransport`（经典 SPP）+ `ble/TandemTransportRouting`。跑在 App 进程。
- 协议：`protocol/` Sony Tandem V1/V2 Table1/Table2，消息 `[DataType][Command][Payload]`，0x0E=Table1 / 0x0F=Table2。
- 适配器：`headphones/` adapter/profile/capability + `TandemCodecRegistry` + `EqProtocolEngine` + `sonydevices/` 三个 profile。
- 状态：`data/SonyHeadphoneRepository`（StateFlow 聚合，App 内单例）。
- LSPosed（OpenBuds 自带，较简单，**本迁移不用它的 hook**）：仅电量通知 + 控制中心卡片拦截。

---

## 4. 当前进度：阶段 1 已完成

**阶段 1 = 把 OpenBuds 的自包含 Sony 引擎以 `dev.sonypods` 包引入本工程，与现有 OPPO 代码共存。没改 gradle、没动任何 OPPO 代码、没碰 hook。**

引入的 34 个文件（package 已从 `dev.ignotus.openbuds` 改为 `dev.sonypods`）：

```
app/src/main/java/dev/sonypods/
├── protocol/   (9)  SonyGatt, SonyTandemConstants/Enums/Types,
│                    SonyTandemV1Table1/V1Table2/V2Table1/V2Table2Protocol, SonyEqEbbPayloadParser
├── ble/        (3)  SonyBleClient, SonySppTransport, TandemTransportRouting
├── headphones/ (5)  HeadphoneAdapter, SonyTandemHeadphoneAdapter, TandemCodecRegistry,
│   └── sonydevices/  EqProtocolEngine, LinkBudsSProfile, Wf1000Xm5Profile, Wh1000Xm4Profile
├── data/       (2)  SonyHeadphoneRepository, SonyModelImageCatalog
└── media/      (1)  MediaPlaybackController
app/src/main/assets/sony_model_images.json
app/src/test/java/dev/sonypods/  (11 单测：protocol/headphones/ble/data)
```

- 引擎只依赖 OppoPods 已有的东西（Android 框架、kotlinx.coroutines、kotlinx.serialization、JUnit），**无需改 gradle**。
- 这些文件与 OpenBuds 逐字节相同，仅做了 `dev.ignotus.openbuds`→`dev.sonypods` 包重命名（已核验无残留、无悬空引用）。
- **注意**：上个 agent 无法编译，阶段 1 **尚未经过真机 build 验证**。你的第一件事就是应用它并 build。

### 应用阶段 1（若 `app/src/main/java/dev/sonypods/` 尚不存在）
工程根目录已放了 `0001-sony-migration-phase1.patch` 和 `sony-migration-phase1.zip`。任选：
```powershell
cd D:\mercu\Desktop\SonyPods
git am .\0001-sony-migration-phase1.patch      # 或 git apply，或 Expand-Archive .\sony-migration-phase1.zip -DestinationPath . -Force
```

### Sony 引擎对外 API（后续接线就调它，替代 RfcommController + OPPO 广播）
`dev.sonypods.data.SonyHeadphoneRepository`：
- `getInstance(context)` 单例；`state: StateFlow<SonyHeadphoneUiState>`（含 deviceInfo/battery/noiseControl/eq/lea/quickAccess/wearing/playback/…）。
- 动作：`startScan()`/`stopScan()`/`connect(device|address)`/`disconnect()`/`refreshBasics()`/`setNoiseControlMode(mode)`/`setAmbientLevel(1..20)`/`setAmbientVoiceMode(bool)`/`setEqPreset(preset)`/`setClearBass(-10..10)`/`setCustomEqBand(index,level)`/`playbackPrevious()`/`playbackPlayPause()`/`playbackNext()`。

---

## 5. 构建与验证（你能编译，务必每阶段执行）

```powershell
cd D:\mercu\Desktop\SonyPods
.\gradlew.bat testDebugUnitTest assembleDebug
# 装机
$adb="C:\Software\platform-tools\adb.exe"   # 或你的 adb 路径
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
& $adb logcat -v time OppoPods:I AndroidRuntime:E '*:S'
```
环境：JDK 22、AGP 9.1.0、Kotlin 2.4.0、compileSdk 37、minSdk 35、Miuix 0.9.2、libxposed api 101。真机需 HyperOS（Android 15+），LSPosed 启用模块并勾选三个作用域，右上角一键重启作用域。

**纪律**：一个阶段一次 build。红了当场修，不要攒着往下推。

---

## 6. 推荐架构（方案 A 详解）

Sony 传输是 BLE GATT + SPP，天然应在 App 进程（不像 OPPO 的 RFCOMM 能在 BT 进程里跑）。OppoPods 本身已有 App 进程（`OppoPodsApp` Application + `MainActivity`/`PopupActivity` + `XposedServiceHelper`）。

**数据流：**
```
[Sony 耳机] ⇄ BLE/SPP ⇄ App 进程(前台 Service 持 SonyHeadphoneRepository)
        │                                   ▲          │
        │ (Repository StateFlow → UI 直接消费)          │
        ▼                                   │          ▼
   App 广播状态(电量/ANC/佩戴/连接态)         │   App 收控制广播→调 Repository setter→发 Tandem
   dev.sonypods.action.* →                  │        ↑
        ↓                                   │        │
[系统进程 hook：桥]                          │        │
  bluetooth: A2DP 连接检测 → 广播"连Sony" ───┘        │
             收 App 电量 → setBatteryLevel 注入系统栈  │
  xiaomi.bluetooth: 收 App 状态 → 超级岛/系统通知      │
  milink.service: 收 App 状态 → 融合中心/超级岛        │
  settings: 系统设置页显示，用户改 ANC → 广播给 App ───┘
```

**本质**：OppoPods 的 hook + 广播架构不变，只是把「大脑」从 BT 进程搬到 App 进程，方向从「hook→app」扩展为双向。
- 原 `RfcommController` 在 BT 进程里既是传输又是状态中枢；现在传输+状态中枢搬到 App 的 Service，BT 进程只留一个轻量 hook 做「检测 A2DP 连接 → 通知 App」+「收 App 电量 → setBatteryLevel」。
- 原来 UI 靠广播跟 BT 进程的 RfcommController 通信；现在 UI 可**直接**消费同进程的 Repository StateFlow（更简单），系统进程的 hook 仍靠广播。

---

## 7. 剩余阶段与文件级替换目标

> 每阶段结束都要 build。阶段间尽量保持可编译（允许功能未接全，但不允许编译不过）。

### 阶段 2 — 拆掉 OPPO 引擎，App 内接 Sony
- **删除**：`pods/RfcommController.kt`、`pods/Packets.kt`、`pods/DeviceCapabilities.kt`、`pods/GameModeImplementation.kt`、`pods/RfcommLog.kt`（RfcommLog 可改写成 Tandem TX/RX 日志，或直接用 Repository.debugLogs）。
- **新增**：一个前台 `SonyControlService`（参考 `OpenBuds\...\service\SonyControlService.kt`，适配本工程 UI/通知），持 `SonyHeadphoneRepository`。
- **改 App UI**：`ui/MainUI` 及 `PopupActivity` 里所有对 `RfcommController` / `OppoPodsAction` / OPPO 类型的调用，改为消费 `SonyHeadphoneRepository.state` + 调它的 setter。耳机详情控件换成 Sony 功能集（NC/环境声 + 环境声等级 + EQ 预设 + 自定义频段 + Clear Bass + LEA 状态 + Quick Access + 佩戴检测，参考 `OpenBuds\...\ui\screen\DeviceScreen.kt`）。
- **删除 OPPO 独有 UI**：游戏模式开关、自适应模式、通透人声增强、智能/轻/中/深 ANC 档、欢律导图对话框(`MelodyImageImportDialog`)。
- gradle：此阶段引擎依赖仍够用；若引入 OpenBuds 的 DataStore(外观设置)/其他，再按需加 `libs.versions.toml` + `app/build.gradle.kts`。
- **build 目标**：App 能独立扫描/连接/控制 Sony 耳机；Sony 单测通过。

### 阶段 3 — 重指 hook（HyperOS 集成桥接，方案 A）
把广播命名空间从 `chen.action.oppopods.*` 全量改为 `dev.sonypods.action.*`（见 §8.5）。
- `hook/HeadsetStateDispatcher.kt`：`isOppoPod`（名字含 oppo）→ **Sony 设备检测**（见 §8.1）；触发从「BT 进程连 RFCOMM」改为「广播通知 App 连 Sony 传输」；保留 A2DP 状态监听 + `setBatteryLevel`（收 App 电量广播后注入）。
- `hook/BluetoothUpstreamHeadsetHook.kt`：**保留伪装小米耳机的 Binder/Parcel hook 结构**（checkSupport/isMiTWS/register/changeAncMode…）；ANC 指令翻译表由 OPPO 改 Sony（见 §8.2）。
- `hook/SettingsHeadsetHook.kt`：同上（当前在 `HookEntry` 中被注释，是否启用可选）。
- `hook/MiBluetoothToastHook.kt`：保留系统通知/岛构建；`cycleAnc` 改为 Sony 三态循环（OFF→NC→AMBIENT→OFF）。
- `hook/milink/MiLinkServiceHook.kt` + `MiLinkSpatialAudioHook.kt`：保留融合中心/超级岛集成结构；空间音频重指 Sony 对应能力或置空（Sony 三款首批无空间音频写入，先置空/隐藏）。
- App 侧：新增/改 `SystemIntegrationReceiver` 等，接收 hook 的控制广播 → 调 Repository；发送状态广播给各 hook 进程。
- **build 目标**：真机上系统通知/超级岛显示 Sony 电量与 ANC；系统设置/融合中心能读写。

### 阶段 4 — 图片与超级岛
- **保留** `config/PodImagePrefs`、`PodImageProvider`（只读 ContentProvider，把图片注入系统通知/岛的机制）、`utils/PodImageLoader`。
- 图片**来源**从「欢律 root 导入」换成 `dev.sonypods.data.SonyModelImageCatalog`（云 manifest 按型号+颜色匹配 URL，asset `sony_model_images.json` 已引入）。
- **删除** `utils/RootManager` 里的欢律扫描/导入；**保留** 一键重启作用域。
- 超级岛/通知/AOD 电量与 ANC 重指 Sony。

### 阶段 5 — UI 收尾与品牌
- 能力覆盖页(`DeviceCapabilitiesPage`)改 Sony 语义或精简；`RfcommDebugPage` 保留为 Tandem 调试页（发 HEX、看 TX/RX）。
- 品牌资源：图标/字符串/README 全部 OPPO→Sony。
- **包名/applicationId 重命名** `moe.chenxy.oppopods` → `dev.sonypods`（涉及 `AndroidManifest`、`applicationId`、Provider authority `moe.chenxy.oppopods.podimages`、ContentProvider URI、`OppoPodsApp` 等类名、日志 tag "OppoPods"→"SonyPods"）。这是最后做、一次性全局替换 + build。

### 阶段 6 — 验证
单测全绿；真机逐项 TX/RX 回归（电量、NC/ASM、EQ、播放）；超级岛/系统设置/融合中心显示验证。三款型号各测一遍。

---

## 8. 关键语义映射

### 8.1 设备识别
- OPPO：`device.name.contains("oppo")`（散落在各 hook 的 `isOppoPod`/`knownOppoAddresses`）。
- Sony：用 OpenBuds `SonyBleClient.isSonyCandidate` 的判定 —— 名字含 sony/linkbuds 或以 wf-/wh-/wi-/xba-/mdr- 开头；加 Sony Audio 广播（manufacturer id `0x012D`）。把 hook 里的 `isOppoPod`/`knownOppoAddresses` 逐个改为 `isSonyPod`/`knownSonyAddresses`，逻辑照搬、只换判定。

### 8.2 降噪/通透（最关键）
| 场景 | OPPO（要删的） | Sony（`dev.sonypods.protocol.NoiseControlMode`） |
|------|----------------|--------------------------------------------------|
| 关闭 | 1 OFF | `OFF` |
| 降噪 | 2 NC（+5/6/7/8 智能/轻/中/深） | `NOISE_CANCELLING`（无档位） |
| 通透 | 3 Transparency（+通透人声增强） | `AMBIENT_SOUND`（+等级 1-20 + voice focus） |
| 自适应 | 4 Adaptive | **无对应 → 删除** |

hook 里原有 Xiaomi↔OPPO 翻译（`oppoAncFromMiuiMode`/`oppoAncFromMiuiLevel`：Xiaomi mode 1→OPPO NC，2→OPPO 通透，其余→OFF）**重写为 Xiaomi↔Sony**：Xiaomi 关→`OFF`，Xiaomi 降噪→`NOISE_CANCELLING`，Xiaomi 通透→`AMBIENT_SOUND`。Sony 的环境声等级(1-20)/voice 主要在模块自身 UI 呈现（Xiaomi 系统 UI 未必暴露）。

### 8.3 电量
OPPO left/right/case(+charging位) → Sony：TWS(LinkBuds S/WF-1000XM5) 用 left/right/cradle；头戴(WH-1000XM4) 用 single。`SonyHeadphoneUiState.batteryState` 已含 single/left/right/cradle。系统 BT 栈注入沿用 `setBatteryLevel`（single 或 min(left,right)）。

### 8.4 EQ
删 OPPO「大师调音」5 预设；用 Sony `EqProtocolEngine`（官方预设 + 手动 + 自定义1/2 + Clear Bass + 5 可见频段 400/1k/2.5k/6.3k/16k，raw band 0=Clear Bass，显示值=raw−10）。EQ 只在模块 App UI 呈现，不进系统 UI。

### 8.5 广播命名空间迁移（`utils/miuiStrongToast/data/OppoPodsAction.kt`）
全部 `chen.action.oppopods.*` → `dev.sonypods.action.*`。**删除**这些 OPPO 独有 action：`ACTION_GAME_MODE_SET`/`ACTION_PODS_GAME_MODE_CHANGED`/`ACTION_AUTO_GAME_MODE_CHANGED`/`ACTION_GAME_MODE_IMPLEMENTATION_CHANGED`/`ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET`/`ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED`/`ACTION_PODS_SMART_ANC_LEVEL_CHANGED`/`ACTION_ADAPTIVE_MODE_CHANGED`。保留并沿用：连接/断开、电量、ANC 选择/变更、佩戴、刷新、UI init、config changed、RFCOMM(→Tandem)调试日志。新增 Sony 需要的：环境声等级、EQ、LEA、Quick Access（可选，方案 A 下 UI 直连 Repository，这些广播主要用于 hook↔app）。

---

## 9. 能力对照

### 9.1 OppoPods 有、OpenBuds 没有 → 迁移后要在新外壳**保留并重指 Sony**
1. 超级岛（Focus Island，官方+模块内建） 2. 融合设备中心集成 3. 系统蓝牙设置注入 4. **型号伪装**（伪装成小米耳机：fake device id、checkSupport、isMiTWS、isBleMmaConnect 一整套 Binder/Parcel hook） 5. 系统通知带 ANC 循环+断开按钮 6. 通知/控制中心卡片点击弹快捷浮窗 7. 传输在 BT 进程直接 `setBatteryLevel` 注入系统栈（方案 A 下改由 BT-process 小 hook 收 App 广播后注入） 8. ANC 从通知/岛按钮循环 9. 一键重启作用域(root) 10. 自定义耳机图注入系统 UI(ContentProvider) 11. AOD 息屏电量 12. 超级岛显示时机配置 13. 通知点击/「更多」行为配置。

### 9.2 OpenBuds 有、OppoPods 没有 → 本次要**引入的能力**（阶段 1 已带入引擎）
1. Sony Tandem V1/V2 Table1/Table2 完整协议 2. BLE GATT 传输(完整握手) 3. GATT+SPP 双传输自动选择 4. Sony Audio 广播解析 5. adapter/profile/capability 架构 + 功能级协议路由 6. EQ 引擎(预设+自定义频段+Clear Bass) 7. LE Audio 状态读取 8. Quick Access/佩戴检测读取 9. EQ 扩展频段信息 10. 播放跨验证(AudioManager+心跳+stale过滤) 11. unsupported endpoint 诊断 12. 完整单测 13. 云端型号图目录 14. 前台 Service 架构 15. DeviceStateSnapshot 跨进程 DTO 16. Table2 只读诊断。

---

## 10. 关键技术事实速查

**Sony Tandem 协议**（详见 `OpenBuds\docs\PROTOCOL_GUIDE.md`）：
- 消息 `[DataType(1)][Command(1)][Payload(N)]`；DataType `0x0E`=Table1，`0x0F`=Table2。
- 命令位规律：`0xX0`GET_CAP … `0xX2`GET_STATUS `0xX3`RET_STATUS `0xX4`SET_STATUS `0xX5`NTFY_STATUS `0xX6`GET_PARAM `0xX8`SET_PARAM `0xX9`NTFY_PARAM。
- 明文无加密；`OnOffSettingValue` 反转(0x00=ON)；EQ raw band 0=Clear Bass，显示=raw−10。
- 命令 `0x13` 在 V2(COMMON_RET_STATUS)/V1(NTFY_BATTERY) 冲突，靠 payload 形状消歧（引擎已处理）。
- GATT 握手：discover→读 OPTIMAL_MTU→请求 MTU→开 DETERMINE_MTU 通知→读 WRITABLE_VALUE_LENGTH→逐个开 FROM_ACC 通知。GATT 服务 UUID `5b833eXX-6bc7-4802-8e9a-723ceca4bd8f`，特征 `5b833cXX-…`。
- SPP 帧 `0x3E + 转义(dataType+seq+len(4BE)+payload+校验和) + 0x3C`，LinkBuds S 主走 SPP。
- 型号↔协议：WH-1000XM4=全 V1 Table1；LinkBuds S / WF-1000XM5=全 V2 Table1。

**OppoPods 关键标识**：RFCOMM UUID `0000079A-D102-11E1-9B23-00025B00A5A5`；OPPO ANC status 1=OFF/2=NC/3=通透/4=自适应/5-8=智能/轻/中/深；广播前缀 `chen.action.oppopods`。

**GPL 边界**（见 `OpenBuds\...\lsposed\GPL_BOUNDARY.md`）：可引用小米类名/方法签名/行为观察；**禁止**逐字复制 GPL 源码、复制 `chen.action.*` 字符串、复制通知构建逻辑结构、嵌入 Base64 资产。广播命名空间必须用自有前缀。

---

## 11. 约定与护栏
- **每阶段 build**，红了当场修。别盲推。
- 保持阶段间可编译（功能可未接全）。
- Sony 相关实现**以 `D:\mercu\Desktop\OpenBuds` 为准**，能直接复用就复用（改包名 `dev.ignotus.openbuds`→`dev.sonypods`），别自己重写协议。
- 别在 UI/Repository 里手写协议字节；命令一律经 adapter/codec/`EqProtocolEngine`。
- parser 对未知 command 返回 `Unknown` 不崩溃；状态合并用 `copy(...)` 避免互相覆盖。
- 新增 parser/命令要补单测（编码测试写死期望字节）。
- HyperOS hook 全程 try/catch 降级，未知版本跳过不崩系统。
- 完成一个阶段就 `git commit`，提交信息写清做了什么。
```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```
