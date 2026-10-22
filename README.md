# Shadow(影子) 🌍

![Android](https://img.shields.io/badge/Android-10%2B-green.svg)
![LSPosed](https://img.shields.io/badge/LSPosed-Module-blue.svg)
![License](https://img.shields.io/badge/License-GPL%20v3.0-orange.svg)

> 🍴 本仓库是 [原项目 GlobalTraveling](https://github.com/Qeccentric/GlobalTraveling) 的 Fork，
> 由 [@xenon2160](https://github.com/xenon2160) 维护。已完成 **Android 17 / 澎湃OS 4** 全面适配，
> 应用 ID 为 `com.xenon.shadow2`（自 1.1.2 起），当前版本 **2.0**，支持 GitHub Actions 云端构建一键出包。

## � 快速开始 (Quick Start)

### 第 1 步：准备环境

*   手机已 **Root**（Magisk / KernelSU / APatch）。
*   已安装 **Xposed 框架**：
    *   Android 16 及以下：任意 LSPosed（Zygisk 版）即可；
    *   **澎湃OS 4 / Android 17**：必须按下方「📦 正确的模块组合」一节配置（ReZygisk + Vector）——用错 ZygiskNext 会导致所有模块失效。
*   （可选）搭配 **Shamiko** / **Hide My Applist** 隐藏 Root 环境。

### 第 2 步：安装 App

在 **Releases** 页面下载最新的 `Shadow-vX.X.X-debug.apk` 安装。

### 第 3 步：申请高德 Key（可选，推荐只申请一个 Web 服务 Key）

> 实测发现：**地图底图不填任何 Key 也能正常显示**，Key 的实际作用集中于**搜索**。

| 方案 | 申请难度 | 效果 |
| --- | --- | --- |
| 什么都不填 | 零成本 | 地图可看；搜索走 OpenStreetMap 公共通道（部分网络可能不通） |
| **Web 服务 Key（推荐）** | ✨ 最简单：无需绑定包名/SHA1 | 搜索自动改走高德 Web API，国内直连稳定、结果质量高 |
| Android 平台 Key（可选） | 需绑定包名 + SHA1 | 高德 SDK 原生搜索通道（输入联想更快） |

**申请 Web 服务 Key**（2 分钟）：

1.  打开 [lbs.amap.com](https://lbs.amap.com)，注册/登录后进入 **控制台 → 应用管理 → 我的应用**；
2.  创建应用（名称随意）→ 点 **「添加」** → 服务平台选 **「Web服务」** → 提交；
3.  复制生成的 **Key**（32 位字符串）。

**申请 Android 平台 Key**（可选）：同样入口，服务平台改选 **「Android平台」**，并填写：

*   **包名**：`com.xenon.shadow2`
*   **SHA1**：`09:F4:55:2F:EE:41:0E:DA:81:27:01:3C:47:74:B5:EE:4D:38:76:9C`

> 💡 Key 随时可改——**长按 App 顶部「已选」状态文字**即可重新打开填写弹窗。

### 第 4 步：填入 Key 并激活模块

1.  打开 Shadow，同意使用须知。首次启动会自动弹出 **Key 填写弹窗**（包名/SHA1 已自动算好，点「复制」即可），粘贴第 3 步的 Key 保存，地图自动重新加载。
2.  打开 **LSPosed/Vector 管理器 → 模块 → Shadow**：启用开关，**作用域勾选你要模拟的目标 App**（微信、高德地图、钉钉等）。
3.  **重启手机**（首次激活或改动作用域后必须）。

### 第 5 步：开始模拟

1.  打开 Shadow，在地图上 **长按** 或 **搜索** 选择目标地点。
2.  点击 **「开始模拟」**（首次会弹 Root 授权请求，允许即可）。
3.  **强制停止目标 App 后重新打开**——微信等 App 有定位缓存，不重启看不到效果。
4.  验证：微信聊天窗口 →「+」→ 位置 → 发送位置，看到的应是你选择的模拟点。

---

## �🔧 本 Fork 相对原版的主要工作

> 以下修复与增强使项目在 **Android 17 / 澎湃OS 4** 上真正可用，
> 并修复了长期失效的搜索与 Key 流程：

| 类别 | 工作内容 |
| --- | --- |
| **Android 17 适配** | `HookMain` 全面重构：每个 Hook 独立容错（单个 API 移除不再拖垮整个模块）、Location 优先用公开 setter 修改、`WifiInfo.mBSSID` 按新类型 `MacAddress` 赋值、补充 `Location.isMock()` 屏蔽、监听器改用 `instanceof` 判断（覆盖 Lambda/Consumer 路径）、LastKnownLocation 补全防"定位中"转圈 |
| **澎湃OS 4 框架排查** | 定位到 **ZygiskNext 在 Android 17 孵化 App 进程时崩溃**（Scene 等所有模块失效的共同根因），确立 **ReZygisk + Vector(LSPosed)** 可用组合并写入文档；解决 Vector 管理器黑屏问题（从模块包手动安装 `manager.apk`） |
| **云端构建** | 搭建 **GitHub Actions** 流水线（JDK 21 + Gradle 9.1），无本地 Android 环境也能出包；提交**固定调试签名** `ci-debug.keystore`，解决高德 Key 因构建签名漂移反复失效的问题 |
| **构建链升级** | AGP 9.0 / Gradle 9.1 / compileSdk 36；Manifest 移除 `package` 属性改用 `namespace` |
| **高德 SDK 修复** | `3dmap` 升级 10.0.600（适配 16KB 内存页新机型）；锁定 `search:9.7.0` 解决 9.7.1 与 3dmap 的**重复类编译冲突**；补齐原仓库漏传的 `ic_search_line` 图标资源 |
| **搜索功能修复** | 失败错误码可见（不再静默）；内置**免 Key 备用搜索通道**（含 WGS-84 → GCJ-02 坐标转换）；适配高德 SDK 历史拼写 `setPostion`；2.0 起备用链国内化（高德 Web API → Nominatim 国内镜像），并实测确认地图底图可免 Key 显示 |
| **Key 流程恢复** | 恢复原版缺失的**高德 Key 填写弹窗**（运行时 `setApiKey` 注入）；弹窗内自动展示包名/SHA1 并支持一键复制；代码改用 `BuildConfig.APPLICATION_ID` 动态读取自身包名 |
| **包名变更** | 应用 ID 改为 `com.xenon.shadow2`，降低被目标 App 按包名识别为 Xposed 模块的概率；今后改包名只需改 `build.gradle` 一行 |
| **UI 现代化** | Key 弹窗重绘为 Material 风格（描边输入框 + 信息卡片）；搜索下拉改为双行卡片条目（定位图标 + 地点名 + 地址副标题）+ 圆角浮层 |
| **体验修复** | 未选点时点「开始模拟」增加 Toast 提示（原为静默无反应） |

---

## 📖 项目介绍

**Shadow** 是一款基于 **LSPosed** 框架的 Android 系统级虚拟定位与环境伪装模块。

不同于传统的 Mock Location（模拟位置），本项目采用 **系统层 Hook + 底层文件交互** 的方式，能够对系统 GPS、基站信息 (Cell)、Wi-Fi 列表及各大地图 SDK（高德/腾讯）、GMS进行全维度的位置模拟。

> **核心特性**：
> *   **底层劫持**：直接 Hook 系统服务与 SDK，无视普通防作弊检测。
> *   **环境伪装**：自动伪造 Wi-Fi BSSID 与基站信息，防止通过网络环境反查真实位置。
> *   **防跳回**：针对高德、钉钉、企业微信等应用做了深度适配，防止位置回弹。

> 📥 原作者的下载渠道（旧版 1.0 / JS 版，**未包含本 Fork 的修复**，仅供对照）：
> [GitHub Releases（JS 版）](https://github.com/Qeccentric/GlobalTraveling/releases/latest) ｜
> [蓝奏云（密码 4onp）](https://wwaon.lanzn.com/iqUY13h9prwj)
>
> 本 Fork 的安装包：**Releases 页面**（永久下载，推荐）或 **Actions → Artifacts**（90 天有效）。
> 发布新版本只需推送 tag：`git tag v1.1.2 && git push origin v1.1.2`，CI 会自动构建并创建 Release。

---

## 📦 澎湃OS 4 / Android 17 用户必读：正确的模块组合

> 很多人升级 Android 17 后发现「模块装了、开关开了、作用域也勾了，但就是不生效」——
> 根因几乎都是 **ZygiskNext**：它已停止维护，在澎湃OS 4 上注入 App 进程时会崩溃
>（`SpecializeCommon` SIGABRT），导致**所有** Xposed 模块（Shadow、Scene……）的
> Hook 全部失效。**抛弃 ZygiskNext 是让一切模块恢复工作的前提。**

| 操作 | 组件 | 说明 |
| --- | --- | --- |
| ❌ **需要卸载** | **ZygiskNext**（KernelSU 中模块名 `zygisksu`） | 已停止维护，Android 17 上注入无效 |
| ❌ **需要卸载** | 旧版独立 **LSPosed Manager** App（`org.lsposed.manager`） | 无法连接新版服务，点通知黑屏闪退 |
| ✅ **必须安装** | **[ReZygisk](https://github.com/PerformanC/ReZygisk)** v1.0.0+ | ZygiskNext 的社区续作，明确支持 Android 17 |
| ✅ **必须安装** | **[Vector](https://github.com/JingMatrix/Vector)** v2.2+ | LSPosed 的现维护版本（LSPosed 已更名 Vector） |
| ➕ 按需保留 | KernelSU / Magisk、Scene、Shamiko、Hide My Applist… | 其余模块可继续搭配 |

**安装顺序**：

1.  KernelSU 管理器 → 模块 → **卸载 `zygisksu`**（ZygiskNext）→ 安装 **ReZygisk** 模块 zip
2.  安装/更新 **Vector** 模块 zip（若管理器打不开：从 zip 中解出 `manager.apk` 手动安装即可）
3.  **重启手机** → Vector 管理器中启用 Shadow、勾选目标 App 作用域 → 再重启一次
4.  强停目标 App 后重开，验证注入：`adb shell su -c "cat /data/adb/lspd/log/modules_*.log"`，
    出现 `[Shadow] Hooks installed for 包名` 即大功告成

---

## ✅ 已验证机型 (Verified Devices)

本项目已在以下设备/系统版本上测试通过，理论上支持 Android 10 - 17（含澎湃OS 4）：

| 设备型号 | Android 版本 | 系统 UI | 测试结果 |
| --- | --- | --- | --- |
| **Xiaomi 13** | **Android 17** | 澎湃OS 4 | ✅ 完美运行（KernelSU + ReZygisk + Vector，定位模拟/搜索/微信均已验证） |
| **OnePlus Ace 3V** | **Android 16**| ColorOS 16 | ✅ 完美运行 |
| **Xiaomi 11 Pro** | **Android 13** | MIUI 14 | ✅ 完美运行 |

### 🆕 2.1 版本：微信小程序定位劫持

*   **小程序定位生效**：微信小程序的 `wx.getLocation` 走微信内置腾讯定位 SDK
    （`com.tencent.map.geolocation`），**不经过系统 LocationManager**，此前无论怎么
    模拟小程序都显示真实位置。2.1 直接在该 SDK 的坐标出口类级别 Hook
    （`getLatitude`/`getLongitude` → 系统模拟坐标），小程序定位与主程序定位一并生效。
*   **实现更稳健**：类加载监听 + 按包前缀/简单类名识别，**不依赖 SDK 内部字段名与
    方法签名**（第三方模块 HookWXMAppLocation 依赖 `mProxyClass` 等内部链路，SDK 一更新
    即静默失效——这正是它"装了不生效"的原因）。同时覆盖微信、企业微信、QQ。
*   **无需再安装 HookWXMAppLocation**：装了 2.1 后建议卸载它，避免重复 Hook。
*   **2.1.1 加强**：覆盖连续定位（`requestLocationUpdates` + 监听器回调，打卡/导航类
    小程序常用）、**字段级覆写**（`mLatitude`/`mLongitude`，堵死"直接读字段不走
    getter"的内部链路）；新增 `ShadowTP` logcat 诊断日志，便于排查个别小程序的
    特殊取位置路径。

---

### 🆕 2.0 版本：搜索全面适配国内网络（版本号说明：原作者已发布 1.2，本 Fork 从 1.0 重构，故跳至 2.0）

*   **实测确认：地图底图不填任何 Key 也能正常显示**——Key 的实际作用集中于搜索。
*   **Key 弹窗新增「Web 服务 Key」输入框（推荐）**：申请最简单（无需绑定包名/SHA1），
    填写后搜索自动改走高德 Web API，国内直连稳定、坐标免转换。
*   **备用搜索链国内化**：Nominatim 国内镜像优先于 Photon/国际站，解决部分网络下
    公共备用通道无法访问导致的「搜索失败」。

---

### 🆕 1.1.2 版本：应用包名变更

*   应用 ID（applicationId）由 `com.kankan.globaltraveling` 改为 **`com.xenon.shadow2`**。
*   代码内部已改为通过 `BuildConfig.APPLICATION_ID` 动态读取自身包名，
    **今后再改包名只需改 `build.gradle` 一行**，无需动 Java 代码。
*   ⚠️ 从旧包名升级等同于安装新应用：需重新配置**高德 Key 包名**、
    重新勾选 **LSPosed 作用域**、重新授予 **KernelSU Root 权限**。

---

### 🆕 1.1.1 版本：搜索功能修复 & 高德 Key 流程恢复

*   **恢复高德 Key 填写弹窗**（原版有、仓库源码缺失的功能）：首次启动自动提示填写，
    弹窗内自动展示包名与当前签名 SHA1，长按顶部状态文字可随时重新填写。
*   **修复 CI 构建导致高德 Key 失效的问题**：高德 Key 绑定「包名 + 签名 SHA1」，
    原先每次 CI 构建都会随机生成调试签名（SHA1 每次不同），现已固定为仓库内
    `ci-debug.keystore`（SHA1 恒定），一次配置 Key 永久有效。
*   **搜索内置免 Key 备用通道**：高德接口失败（未填 Key / 限流）时自动切换
    OpenStreetMap（Photon → Nominatim）搜索，并做 WGS-84 → GCJ-02 坐标转换，
    不填 Key 也能正常搜索选点。
*   **错误不再静默**：搜索失败会在日志输出具体错误码（`rCode`）并 Toast 提示。

---

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

**Original project made with ❤️ by coolapk@堪堪sqr**
**Fork maintained by [@xenon2160](https://github.com/xenon2160)**
