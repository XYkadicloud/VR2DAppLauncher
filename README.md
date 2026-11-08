# VR2DAppLauncher - Shizuku 虚拟显示器 VR 演示应用

## 项目概述
这是一个独立的 Android 应用，演示如何使用 **Shizuku** 创建虚拟显示器，在 VR (Google Cardboard) 中观看和操作普通 2D 应用。

## 核心功能
1. **Shizuku 权限管理** - 检查/请求权限
2. **虚拟显示器创建** - 1920×1080@60Hz，双路径（Shizuku 优先 + MediaProjection 回退）
3. **应用启动** - 在虚拟显示器上启动 Chrome/YouTube/浏览器/自定义包名
4. **输入路由** - 外部手柄/键盘/鼠标自动绑定到虚拟显示器
5. **VR 观看模式** - Cardboard SDK 立体渲染，头部追踪

## 目录结构
```
VR2DAppLauncher/
├── app/
│   ├── src/main/
│   │   ├── java/com/vr2dapplauncher/
│   │   │   ├── VR2DApp.kt                    # Application 入口
│   │   │   ├── ui/
│   │   │   │   └── MainActivity.kt           # 2D 启动器界面
│   │   │   ├── vr/
│   │   │   │   ├── VrActivity.kt             # VR 模式 Activity
│   │   │   │   └── VrRenderer.kt             # Cardboard 渲染器
│   │   │   └── shizuku/
│   │   │       ├── ShizukuUtils.kt           # 权限工具
│   │   │       ├── ShizukuServiceUtils.kt    # 系统服务获取
│   │   │       ├── VirtualDisplayManager.kt  # 虚拟显示器创建
│   │   │       ├── InputRouter.kt            # 输入设备绑定
│   │   │       ├── AppLauncher.kt            # 应用启动
│   │   │       ├── VrVirtualDisplayHost.kt   # VR 宿主
│   │   │       └── MediaProjectionService.kt # 回退路径
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml         # 主界面
│   │   │   │   └── activity_vr.xml           # VR 界面
│   │   │   └── values/
│   │   │       ├── strings.xml
│   │   │       ├── colors.xml
│   │   │       └── themes.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
└── gradle/wrapper/
```

## 依赖要求
在 `app/build.gradle.kts` 中已声明：
- **Shizuku**: `moe.shizuku:api:13.5.3`, `moe.shizuku:manager:13.5.3`
- **Refine**: `dev.rikka.tools.refine:refine:2.2.0` (用于 unsafeCast)
- **Google Cardboard SDK**: `implementation(files("libs/cardboard-sdk.aar"))` - **需手动放入**

## 必须手动准备的文件

### 1. Google Cardboard SDK AAR
将 `cardboard-sdk.aar` (v1.36.0) 放入 `app/libs/` 目录。

获取方式：
- 从 [Google Cardboard SDK releases](https://developers.google.com/cardboard/develop/unity/downloads) 下载
- 或从 OpenNOW-VR 项目的 CI 产物中获取
- 文件名必须为 `cardboard-sdk.aar`

### 2. 设备准备
- Android 7.0+ (API 24+)
- 安装 **Shizuku Manager** (从 GitHub Releases 或酷安下载)
- 通过 ADB 激活 Shizuku：
  ```bash
  adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
  ```
- Google Cardboard 眼镜或同类 VR 盒子

## 构建步骤
```bash
cd VR2DAppLauncher

# Linux/macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug

# 输出 APK: app/build/outputs/apk/debug/app-debug.apk
```

## 使用流程
1. 安装 APK 到设备
2. 打开应用，检查 Shizuku 权限状态（应显示 ✅ 已授予）
3. 点击 **"创建虚拟显示器"** - 后台创建 1920×1080 虚拟屏幕
4. 点击 **"启动 Chrome (VR)"** / **"启动 YouTube (VR)"** / **"启动 浏览器 (VR)"** - 对应应用在虚拟显示器上启动
5. 将手机放入 Cardboard，点击 **"进入 VR 模式"**
6. 在 VR 中观看应用画面，手柄/键盘输入自动路由到虚拟显示器

## 控制说明
- **头部追踪**: Cardboard SDK 自动处理
- **手柄/键盘**: 通过 InputRouter 自动绑定到虚拟显示器
- **返回键/菜单键**: 退出 VR 模式回到 2D 界面
- **长按 HOME/电源键**: 系统级返回，也会退出 VR

## 技术要点

### 零拷贝渲染管线
```
虚拟显示器 → SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) 
    → VrRenderer.onNewFrame.updateTexImage() 
    → CardboardView 左右眼立体渲染
```
视频流从未经过 Bitmap 或二次渲染目标，直接在 GL 线程采样。

### 虚拟显示器双路径创建
1. **Shizuku 路径** (优先): `IDisplayManager.createVirtualDisplay()` - 无需用户弹窗
2. **MediaProjection 路径** (回退): `MediaProjection.createVirtualDisplay()` - 需用户授权

### 输入路由三重策略
1. `addUniqueIdAssociationByDescriptor()` - 按设备描述符绑定 (最常用)
2. `addUniqueIdAssociation()` - 按 uniqueId 绑定 (需解析 dumpsys input)
3. `addPortAssociation()` - 按物理端口绑定 (HDMI/DP)

## 常见问题

### Q: Shizuku 权限显示未授予
A: 确保已安装 Shizuku Manager 并通过 ADB 激活。部分设备重启后需要重新激活。

### Q: 虚拟显示器创建失败
A: 检查 logcat，常见原因：
- Shizuku 版本过旧 (需 v13.5+)
- 设备不支持虚拟显示器 (极少见)
- 权限不足 (尝试 MediaProjection 回退)

### Q: VR 模式画面黑屏
A: 确保 Cardboard SDK AAR 正确放入 `app/libs/`，且版本为 v1.36.0。

### Q: 手柄无法控制虚拟显示器应用
A: 确保手柄已通过 USB/蓝牙连接，且在 Android 设置中显示为外部输入设备。

## 许可证
Apache 2.0