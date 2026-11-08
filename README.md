# VR2DAppLauncher - Shizuku 虚拟显示器 VR 演示应用

## 项目概述
这是一个独立的 Android 应用，演示如何使用 **Shizuku** 创建虚拟显示器，在 VR (Google Cardboard) 中观看和操作普通 2D 应用。

## 核心功能
1. **Shizuku 权限管理** - 检查/请求权限
2. **虚拟显示器创建** - 可配置分辨率/刷新率/DPI，双路径（Shizuku 优先 + MediaProjection 回退）
3. **应用启动** - 在虚拟显示器上启动 Chrome/YouTube/浏览器/自定义包名
4. **输入路由** - 外部手柄/键盘/鼠标自动绑定到虚拟显示器，支持 `dumpsys input` 解析
5. **VR 观看模式** - Cardboard SDK 立体渲染，头部追踪
6. **VR 设置菜单** - 完整的画面调整、屏幕几何、舒适度配置

## 目录结构
```
VR2DAppLauncher/
├── app/
│   ├── src/main/
│   │   ├── java/com/vr2dapplauncher/
│   │   │   ├── VR2DApp.kt                    # Application 入口
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt           # 2D 启动器界面
│   │   │   │   └── VrSettingsActivity.kt     # VR 设置界面
│   │   │   ├── vr/
│   │   │   │   ├── VrActivity.kt             # VR 模式 Activity
│   │   │   │   ├── VrRenderer.kt             # Cardboard 渲染器 (带画面调整着色器)
│   │   │   │   └── VrSettings.kt             # VR 设置数据类
│   │   │   └── shizuku/
│   │   │       ├── ShizukuUtils.kt           # 权限工具
│   │   │       ├── ShizukuServiceUtils.kt    # 系统服务获取
│   │   │       ├── VirtualDisplayManager.kt  # 虚拟显示器创建
│   │   │       ├── InputRouter.kt            # 输入设备绑定 (含 dumpsys 解析)
│   │   │       ├── AppLauncher.kt            # 应用启动
│   │   │       ├── VrVirtualDisplayHost.kt   # VR 宿主
│   │   │       └── MediaProjectionService.kt # 回退路径
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml         # 主界面
│   │   │   │   ├── activity_vr.xml           # VR 界面
│   │   │   │   ├── activity_vr_settings.xml  # VR 设置界面
│   │   │   │   ├── item_settings_header.xml  # 设置分组标题
│   │   │   │   └── item_settings_seekbar.xml # 设置滑块项
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
- **Google Cardboard SDK**: `implementation(files("libs/cardboard-sdk.aar"))` - **CI 自动从源码构建**

## 必须手动准备的文件 (本地构建时)

### 1. Google Cardboard SDK AAR
将 `cardboard-sdk.aar` (v1.36.0) 放入 `app/libs/` 目录。

获取方式：
- 从 [Google Cardboard SDK releases](https://developers.google.com/cardboard/develop/unity/downloads) 下载
- 或从 OpenNOW-VR 项目的 CI 产物中获取
- 文件名必须为 `cardboard-sdk.aar`

**注意**: GitHub Actions CI 会自动从源码构建 Cardboard SDK AAR，无需手动放入。

### 2. 设备准备
- Android 7.0+ (API 24+)
- 安装 **Shizuku Manager** (从 GitHub Releases 或酷安下载)
- 通过 ADB 激活 Shizuku：
  ```bash
  adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
  ```
- Google Cardboard 眼镜或同类 VR 盒子
- (可选) USB/蓝牙手柄、键盘、鼠标

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
3. 点击 **"创建虚拟显示器"** - 后台创建虚拟屏幕 (默认 1920×1080@60Hz)
4. 点击 **"启动 Chrome (VR)"** / **"启动 YouTube (VR)"** / **"启动 浏览器 (VR)"** - 对应应用在虚拟显示器上启动
5. 点击 **"VR 设置"** 调整画面参数 (亮度/对比度/饱和度/角渐晕/色差/屏幕几何/瞳距等)
6. 将手机放入 Cardboard，点击 **"进入 VR 模式"**
7. 在 VR 中观看应用画面，手柄/键盘输入自动路由到虚拟显示器

## 控制说明
- **头部追踪**: Cardboard SDK 自动处理
- **手柄/键盘**: 通过 InputRouter 自动绑定到虚拟显示器
- **返回键/菜单键**: 退出 VR 模式回到 2D 界面
- **长按 HOME/电源键**: 系统级返回，也会退出 VR
- **VR 设置**: 主界面点击 "VR 设置" 按钮，或在 VR 模式下长按音量键 (待实现)

## VR 设置详解

### 屏幕几何参数 (影响 VR 中虚拟屏幕大小/距离)
- **屏幕距离** (0.02~0.2m): 眼睛到虚拟屏幕的距离，越近视野越大
- **屏幕宽度** (0.1~0.5m): 虚拟屏幕物理宽度
- **屏幕高度** (0.05~0.3m): 虚拟屏幕物理高度
- **屏幕曲率** (-1.0~1.0): 正值=凸面(桶形)，负值=凹面(枕形)，模拟曲面屏效果

### 画面调整
- **亮度** (0.1~2.0): 整体亮度倍数
- **对比度** (0.1~2.0): 对比度倍数
- **饱和度** (0.1~2.0): 色彩饱和度倍数
- **角渐晕** (0.0~1.0): 边缘变暗强度，模拟透镜边缘光学效果
- **色差校正** (-1.0~1.0): 正值=红外扩散/蓝内聚，负值相反，校正透镜色散

### 头部姿态校正 (修正安装偏差)
- **瞳距 (IPD)** (0.05~0.08m): 双眼间距，影响立体感强度
- **偏航角偏移** (-180~180°): 左右旋转偏移校正
- **俯仰角偏移** (-90~90°): 上下旋转偏移校正
- **翻滚角偏移** (-180~180°): 倾斜旋转偏移校正

### 虚拟显示器配置 (需重建虚拟显示器生效)
- **宽度** (720~3840): 虚拟显示器宽度 (像素)
- **高度** (480~2160): 虚拟显示器高度 (像素)
- **DPI** (120~640): 虚拟显示器像素密度
- **刷新率** (30~120): 虚拟显示器刷新率 (Hz)

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
1. `addUniqueIdAssociationByDescriptor()` - 按设备描述符绑定 (最常用，屏连/屏易连主用)
2. `addUniqueIdAssociation()` - 按 uniqueId 绑定 (需解析 dumpsys input)
3. `addPortAssociation()` - 按物理端口绑定 (HDMI/DP)

### 着色器增强 (VrRenderer)
- 亮度/对比度/饱和度实时调节
- 角渐晕效果 (模拟透镜边缘光学)
- 色差校正 (RGB 通道分别采样偏移)
- 屏幕曲面投影 (桶形/枕形畸变模拟)
- 所有调整在片段着色器中完成，零 CPU 开销

## 常见问题

### Q: Shizuku 权限显示未授予
A: 确保已安装 Shizuku Manager 并通过 ADB 激活。部分设备重启后需要重新激活。

### Q: 虚拟显示器创建失败
A: 检查 logcat，常见原因：
- Shizuku 版本过旧 (需 v13.5+)
- 设备不支持虚拟显示器 (极少见)
- 权限不足 (尝试 MediaProjection 回退)

### Q: VR 模式画面黑屏
A: 确保 Cardboard SDK AAR 正确放入 `app/libs/`，且版本为 v1.36.0。CI 构建会自动处理。

### Q: 手柄无法控制虚拟显示器应用
A: 确保手柄已通过 USB/蓝牙连接，且在 Android 设置中显示为外部输入设备。可在 VR 设置中查看绑定日志。

### Q: 画面参数调整后不生效
A: 画面调整参数 (亮度/对比度/饱和度/角渐晕/色差) 实时生效。屏幕几何参数和虚拟显示器配置需要重新创建虚拟显示器或重启 VR 模式。

## GitHub Actions CI/CD
- 每次推送到 main/master 分支自动触发构建
- 自动从源码构建 Cardboard SDK v1.36.0 AAR
- 构建 Debug APK 并上传为 Artifact (保留 30 天)
- 打 Tag (v*) 时额外构建 Release APK (保留 90 天)
- 构建失败时自动上传日志

## 许可证
Apache 2.0