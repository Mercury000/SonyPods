<div align="center">

# SonyPods

**System-level Sony headphone control for HyperOS devices**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS3-orange?style=flat-square)](https://hyperos.mi.com)

**English** | **[简体中文](README.md)**

</div>

An Xposed module bringing system-level Sony headphone control to Xiaomi HyperOS.
The protocol layer is based on OpenBuds' clean-room Sony Tandem implementation
(BLE GATT + SPP).

### Supported models (first batch)

- Sony WH-1000XM4
- Sony LinkBuds S
- Sony WF-1000XM5

### Headphone features

- **Noise control** — Off / Noise Cancelling / Ambient Sound, ambient level (1–20) and voice focus
- **Equalizer** — official presets + Clear Bass + custom bands
- **Battery** — TWS left / right / case, single level for headbands
- **Playback** — previous / play-pause / next
- **Status** — LE Audio, Quick Access, wearing detection, firmware version
- **Tandem debug** — TX/RX log viewer and raw HEX sender

### HyperOS integration

- **Model spoofing** — presents Sony headphones as supported Xiaomi earbuds to the system headset UI
- **System battery injection** — battery synced into the system bluetooth stack
- **Focus Island / notifications** — connect & battery island, AOD battery, ANC-cycle notification button
- **Fusion device center** — battery and ANC state read/write
- **Quick popup** — control popup from the notification
- **Model images** — cloud catalog matched by model + color, custom images supported

### Usage

1. Install the APK, enable the module in LSPosed with scopes:
   `com.android.bluetooth`, `com.milink.service`, `com.xiaomi.bluetooth`
2. Restart the scopes (one-tap root restart inside the app)
3. Open the app, grant Bluetooth / notification permissions, connect your Sony headphones

### Requirements

- HyperOS (Android 15+)
- LSPosed (libxposed API 100+)

### Credits

- [OppoPods-Enhanced](https://github.com/1812z/OppoPods) — HyperOS 系统集成外壳来源
- [OpenBuds](https://github.com/IgnotusJee/OpenBuds) — Sony Tandem 协议栈来源
