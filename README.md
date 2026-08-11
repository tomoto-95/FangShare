# LanShare — 局域网文件快传

基于 Android 的局域网文件传输工具，无需服务器、无需互联网，同一 WiFi 下即可在手机之间高速传输照片、文档等文件。

## 核心功能

| 功能 | 说明 |
|------|------|
| **设备发现** | 基于 NSD (mDNS) 自动发现同一局域网内的在线设备 |
| **文件传输** | 通过 HTTP 协议在设备间直接传输，支持任意文件类型 |
| **家庭组** | 创建/加入家庭组，组内设备优先显示，一键群发 |
| **文件选择** | 浏览照片、文档，支持多选批量发送 |
| **传输历史** | 记录所有发送和接收的文件 |
| **前台服务** | 后台常驻接收文件，通知栏显示运行状态 |

## 技术架构

```
┌─────────────────────────────────┐
│         Jetpack Compose          │  ← Material 3 UI
├─────────────────────────────────┤
│          MainViewModel           │  ← 状态管理
├─────────────────────────────────┤
│  DiscoveryService  │ FileServer │  ← NSD 发现 + NanoHTTPd
│  (NSD mDNS)        │  (HTTP)    │
├─────────────────────────────────┤
│         TransferClient           │  ← OkHttp 发送文件
├─────────────────────────────────┤
│       Android Framework          │  ← WiFi, NSD, Storage
└─────────────────────────────────┘
```

### 依赖库

- **Jetpack Compose** + Material 3 — 现代声明式 UI
- **NanoHTTPd** — 轻量级嵌入式 HTTP 服务器（接收文件）
- **OkHttp** — HTTP 客户端（发送文件）
- **Gson** — JSON 序列化
- **Coil** — 图片加载
- **NSD (Network Service Discovery)** — 局域网设备发现

## 构建运行

### 环境要求

- Android Studio Hedgehog (2023.1) 或更高
- JDK 17
- Android SDK 34
- Gradle 8.5

### 步骤

```bash
# 1. 用 Android Studio 打开 LanShare 目录
# 2. 等待 Gradle 同步完成
# 3. 连接 Android 设备或启动模拟器
# 4. 点击 Run 运行
```

### 构建 APK

```bash
./gradlew assembleRelease
# APK 输出: app/build/outputs/apk/release/app-release.apk
```

## 使用说明

### 基本传输流程

1. 两台手机连接**同一 WiFi 网络**
2. 两台手机都打开 LanShare
3. 在「设备」页面会看到对方的设备
4. 在「文件」页面选择要发送的文件，或点击首页「发送文件」
5. 选择目标设备，文件开始传输
6. 接收方自动保存文件到 `内部存储/Android/data/com.lanshare.app/files/LanShare/`

### 家庭组

1. 在「家庭组」页面点击「创建家庭组」
2. 输入组名（如"我家"），获得 6 位加入码
3. 其他家庭成员在「家庭组」页面点击「加入家庭组」
4. 输入加入码即可加入
5. 激活家庭组后，组内设备在列表中优先显示

### 注意事项

- 需要授予**文件访问权限**和**通知权限**
- 接收方需要保持 LanShare 在前台或后台运行
- 传输大文件时保持屏幕常亮以避免被系统中断
- 仅支持同一局域网；不支持跨网络传输

## 项目结构

```
LanShare/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/lanshare/app/
│       │   ├── LanShareApp.kt              # Application
│       │   ├── MainActivity.kt             # 入口 Activity
│       │   ├── model/
│       │   │   ├── Device.kt               # 设备数据模型
│       │   │   ├── Group.kt                # 家庭组数据模型
│       │   │   └── TransferTask.kt         # 传输任务模型
│       │   ├── service/
│       │   │   ├── DiscoveryService.kt     # NSD 设备发现
│       │   │   ├── FileServerService.kt    # 前台服务 + HTTP 服务器
│       │   │   └── TransferClient.kt       # OkHttp 传输客户端
│       │   ├── viewmodel/
│       │   │   └── MainViewModel.kt        # 全局状态管理
│       │   ├── util/
│       │   │   ├── NetworkUtils.kt         # 网络工具
│       │   │   ├── FileUtils.kt            # 文件工具
│       │   │   └── PermissionHelper.kt     # 权限管理
│       │   └── ui/
│       │       ├── theme/                  # Material 3 主题
│       │       ├── navigation/             # 导航框架
│       │       └── screens/                # 各页面
│       └── res/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```
