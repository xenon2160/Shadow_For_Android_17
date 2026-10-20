# Shadow(影子) 🌍

![Android](https://img.shields.io/badge/Android-10%2B-green.svg)
![LSPosed](https://img.shields.io/badge/LSPosed-Module-blue.svg)
![License](https://img.shields.io/badge/License-GPL%20v3.0-orange.svg)

推荐使用 JS 版，如果不想注册高德账号，继续使用 1.0 版本。
### 📥 [点击下载最新版 JS](https://github.com/Qeccentric/GlobalTraveling/releases/latest)
### 📥 [ 蓝奏云 支持填入KEY 密码:4onp](https://wwaon.lanzn.com/iqUY13h9prwj)
**Shadow** 是一款基于 **LSPosed** 框架的 Android 系统级虚拟定位与环境伪装模块。

不同于传统的 Mock Location（模拟位置），本项目采用 **系统层 Hook + 底层文件交互** 的方式，能够对系统 GPS、基站信息 (Cell)、Wi-Fi 列表及各大地图 SDK（高德/腾讯）、GMS进行全维度的位置模拟。


> **核心特性**：
> *   **底层劫持**：直接 Hook 系统服务与 SDK，无视普通防作弊检测。
> *   **环境伪装**：自动伪造 Wi-Fi BSSID 与基站信息，防止通过网络环境反查真实位置。
> *   **防跳回**：针对高德、钉钉、企业微信等应用做了深度适配，防止位置回弹。

---

## ✅ 已验证机型 (Verified Devices)

本项目已在以下设备/系统版本上测试通过，理论上支持 Android 10 - 17（含澎湃OS 4）：
| 设备型号 | Android 版本 | 系统 UI | 测试结果 |
| **OnePlus Ace 3V** | **Android 16**| ColorOS 16 | ✅ 完美运行 |
| **Xiaomi 11 Pro** | **Android 13** | MIUI 14 | ✅ 完美运行 |

### 🆕 1.1 版本：Android 17（澎湃OS 4）适配

旧版（1.0）在 Android 17 上无法工作，主要是因为 Hook 初始化过程中任何一个被废弃的
系统 API（如 `TelephonyManager.getNetworkType`、`CellLocation` 等）被新系统移除后，
异常会中断整个模块的 Hook 安装，导致**所有** Hook 全部失效。1.1 版本的完整修改如下：

#### 一、Hook 层修改（`HookMain.java`）—— Android 17 失效问题的根源修复

| 修改点 | 说明 |
| --- | --- |
| **Hook 全面隔离** | 新增 `safe()` 统一包装，每个 Hook 独立 try/catch，单个 API 被新系统移除只跳过该 Hook 并写日志，不再中断整个模块初始化 |
| **定位修改加固** | `applyLocationFix()` 优先使用公开 setter（`setLatitude`/`setLongitude`）修改坐标，私有字段反射（`mLatitude` 等）仅作回退，避免新系统字段布局变化导致反射失败 |
| **Wi-Fi 伪造修复** | `WifiInfo.mBSSID` 自 Android 12 起为 `MacAddress` 类型而非 `String`，新增 `setWifiInfoField()` 按字段实际类型赋值，修复新系统上 Wi-Fi 指纹伪造失效 |
| **新增 Mock 检测屏蔽** | 补充 `Location.isMock()`（Android 12+ 新的 Mock 检测途径）的 Hook，与旧的 `isFromMockProvider` 双保险 |
| **监听器 Hook 修复** | `requestLocationUpdates` 改用 `instanceof LocationListener` 判断（旧版按类名 `contains` 判断会漏掉 Lambda/方法引用），并新增 `Consumer` 路径支持（Android 12+ API）；使用 `WeakHashMap` 保证每个监听器只挂载一次，防止重复 Hook 无限膨胀 |
| **LastKnownLocation 补全** | 当系统返回 `null` 时用配置坐标构造 `Location` 返回，解决部分 App 一直显示"定位中"转圈的问题 |
| **配置读取缓存** | 配置文件 `/data/local/tmp/irest_loc.conf` 增加 500ms 读取缓存，降低高频文件 IO |

#### 二、构建链升级（适配新版 Android Studio / AGP）

| 修改点 | 说明 |
| --- | --- |
| **AGP 9.0 + Gradle 9.1** | 升级 Android Gradle Plugin 至 9.0.0（`gradle/libs.versions.toml`），Wrapper 升级至 Gradle 9.1.0 |
| **compileSdk 36** | 编译平台升级至 Android 16+（`targetSdk` 保持 34，避免 edge-to-edge 等行为变化影响 UI） |
| **Manifest 适配** | 移除 `<manifest>` 的 `package` 属性（AGP 8+ 已不支持），改由 `build.gradle` 的 `namespace` 提供 |
| **高德 SDK 升级** | `3dmap` 9.8.2 → **10.0.600**（官方适配 Android 15/16+ 与 **16KB 内存页**设备，澎湃OS 4 等新机型不升级会在加载 so 时崩溃）；`search` 固定 9.7.0（9.7.1 起与 3dmap 10.0.600 存在重复类冲突，导致编译失败） |
| **代码清理** | `MainActivity` 移除重复的 `setContentView` 调用 |

#### 三、新增 GitHub Actions 云端编译（`.github/workflows/build.yml`）

本机没有 Android 开发环境也可以出 APK：

1.  将本仓库 **Push 到你自己的 GitHub**；
2.  打开仓库的 **Actions** 页面 → 选择 **Build APK** → **Run workflow**；
3.  编译完成后在 **Artifacts** 中下载 `Shadow-debug-apk` 即可。

> 若升级后仍无法模拟，请在 LSPosed 管理器中勾选目标应用的作用域（必要时加上
> “系统框架”），**强制停止**目标应用后重新打开（或重启手机）；模块日志可在
> LSPosed 日志中查看 `[Shadow]` 前缀输出，被跳过的 Hook 会显示 `Hook skipped`。

---

## ⚠️ 关于地图选点与 QPS 限制的说明

本项目内置的地图选点功能使用的是 **高德地图 SDK (免费版)**。

由于高德对个人开发者账号有 **QPS (每秒请求数)** 和 **每日流量** 的限制，当同时使用人数较多时，可能会出现：
*   搜索地点无响应或报错。
*   地图加载缓慢。

**💡 最佳实践建议：**
1.  **善用“历史记录”**：App 会自动保存您使用过的地点。点击搜索框下方的历史标签可直接复用坐标，**无需消耗 API 配额**。
2.  **设置“默认位置”**：建议将常用地点（如公司/家）设为默认位置，App 启动时会自动跳转，无需重复搜索。
3.  **手动选点**：如果搜索失败，您可以直接拖动地图进行长按选点，这通常不受 QPS 限制影响。

---

## 📖 使用说明 (Usage)

### 1. 环境要求
*   手机已 **Root** (Magisk / KernelSU / APatch)。
*   已安装 **LSPosed** 框架 (推荐 Zygisk 版)。
*   (可选) 建议配合 **Shamiko** 和 **Hide My Applist** 使用，以隐藏 Root 环境。

> **⚠️ 澎湃OS 4 / Android 17 用户必读**（已在 Xiaomi 11 Pro 澎湃OS 4 上实测通过）：
> *   **不要用 ZygiskNext**——项目已停止维护，它在澎湃OS 4 上注入 App 进程时会崩溃
>     （`SpecializeCommon` SIGABRT），导致**所有** Xposed 模块的 Hook 全部失效，
>     表现为"模块已启用但定位不变"。
> *   正确组合：**[ReZygisk](https://github.com/PerformanC/ReZygisk)** v1.0.0+
>     （支持 Android 17）+ **[Vector](https://github.com/JingMatrix/Vector)** v2.2+
>     （LSPosed 的现维护版本）。先卸载 ZygiskNext 和旧版 LSPosed 管理器 App，再装这两个，
>     最后重启手机。
> *   若 Vector 管理器打不开（黑屏闪退），可从 Vector 模块 zip 中解出 `manager.apk`
>     手动安装。
> *   排查注入是否生效：`adb shell su -c "cat /data/adb/lspd/log/modules_*.log"`，
>     打开目标 App 后应出现 `[Shadow] Hooks installed for 包名`。

### 2. 安装与激活
1.  下载并安装 `Shadow.apk`。
2.  打开 App，授予 **Root 权限** 和 **存储权限**。
3.  打开 **LSPosed 管理器**，找到“Shadow”模块。
4.  **勾选作用域**：
    *   **推荐**：勾选所有需要模拟的目标应用（如：高德地图、腾讯地图、企业微信、钉钉等）。
    *   **可选**：勾选“系统框架 (System Framework)”。
5.  **重启手机** (首次激活或更新模块后必须重启)。

### 3. 开始模拟
1.  打开 App，在地图上 **长按** 或 **搜索** 选择一个地点。
2.  点击底部的 **“开始模拟”** 按钮。
3.  此时 App 会在底层生成随机的 Wi-Fi/基站指纹并写入系统，模拟立即生效。

---

## ⚖️ 免责声明 (Disclaimer)

**请务必仔细阅读以下条款，下载、安装或使用本项目即视为您已完全知晓并同意本声明：**

1.  **仅供学习**：本项目旨在研究 Android 系统底层 Binder 通信、Hook 技术及隐私保护原理。**严禁** 将本项目用于任何非法用途（包括但不限于：考勤作弊、网约车虚假跑单、游戏外挂、网络诈骗等）。
2.  **后果自负**：因违反本声明使用本软件而产生的任何法律责任、账号封禁、财产损失或数据丢失，均由使用者本人承担，开发者不承担任何连带责任。
3.  **无担保**：本项目属于开源软件，按“原样”提供，不提供任何形式的商业担保或售后服务。
4.  **隐私安全**：本项目**不收集、不上传**用户的任何地理位置信息，所有配置数据仅存储于用户手机本地 (`/data/local/tmp` 和 `SharedPreferences`)。

---

## 📜 开源协议 (License)

本项目遵循 **GNU General Public License v3.0 (GPLv3)** 协议。

*   ✅ 您可以免费使用、修改和分发本项目的源代码。
*   ✅ 如果您修改了源代码并发布了新的版本（无论是否收费），您 **必须** 开源您的修改部分，并继续沿用 GPLv3 协议。
*   🚫 **禁止将本项目闭源商用**。

---

**Made with ❤️ by coolapk@堪堪sqr**
