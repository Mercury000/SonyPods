<div align="center">

# SonyPods

**为 HyperOS 设备提供系统级 Sony 耳机控制**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-澎湃OS3-orange?style=flat-square)](https://hyperos.mi.com)

**[English](README_EN.md)** | **简体中文**

</div>

为小米 HyperOS 设备提供系统级 Sony 耳机控制的 Xposed 模块。协议层基于
OpenBuds 的 Sony Tandem（BLE GATT + SPP）净室实现。

### 支持型号（首批）

- Sony WH-1000XM4
- Sony LinkBuds S
- Sony WF-1000XM5

### 耳机功能

- **降噪控制** — 关闭 / 降噪 / 环境声三态切换，环境声等级（1–20）与人声模式
- **均衡器** — 官方预设 + Clear Bass + 自定义频段
- **电量显示** — TWS 左 / 右 / 充电盒，头戴式单电量
- **播放控制** — 上一首 / 播放暂停 / 下一首
- **状态读取** — LE Audio、Quick Access、佩戴检测、固件版本
- **Tandem 调试** — 查看 TX/RX 日志、发送原始 HEX 消息

### 系统集成（HyperOS）

- **型号伪装** — 将 Sony 耳机伪装为受支持的小米耳机，接入系统耳机 UI
- **系统蓝牙电量注入** — 电量实时同步到系统蓝牙栈
- **超级岛 / 焦点通知** — 连接与电量岛、AOD 息屏电量、通知栏降噪循环按钮
- **融合设备中心** — 电量与降噪状态读写
- **快捷弹窗** — 点击通知弹出控制浮窗
- **型号图片** — 云端型号图目录按型号+颜色自动匹配，支持自定义图片

### 使用

1. 安装 APK，在 LSPosed 中启用模块，勾选作用域：
   `com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings`
2. 重启作用域（App 内可一键 root 重启）
3. 打开 App 授予蓝牙 / 通知权限，连接 Sony 耳机

### 环境

- HyperOS（Android 15+）
- LSPosed（libxposed API 100+）

### 致谢

- [OppoPods-Enhanced](https://github.com/1812z/OppoPods) — HyperOS 系统集成外壳来源
- [OpenBuds](https://github.com/IgnotusJee/OpenBuds) — Sony Tandem 协议栈来源
