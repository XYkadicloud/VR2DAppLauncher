# VR2DAppLauncher

把普通 2D Android 应用投放到 Google Cardboard VR 界面中观看与操作 —— 一个**独立的新项目**（不是 OpenNOW-VR 仓库的修改），当前处于**技术验证阶段**：先保证云端能稳定编译出可安装的 APK，再逐步补齐能力。

## 1. 当前实现架构（请先读这一节）

```
用户点"创建虚拟显示器"
  → MediaProjectionManager.createScreenCaptureIntent() 弹出系统授权
  → MediaProjection.createVirtualDisplay(..., Surface)
  → Surface 来自 SurfaceTexture，绑定为 GL_TEXTURE_EXTERNAL_OES
  → CardboardView 左右眼采样这张纹理（零 Bitmap 拷贝）
  → 手机放入 Cardboard 观看
```

- **虚拟显示器**：使用 **公开 Android API**（`MediaProjection.createVirtualDisplay`）。标准 SDK 可编译、无需 root、无需 framework hidden API。
- **Shizuku**：**可选增强**，不再是创建虚拟显示器的前置条件。已接入的部分：
  - `Shizuku.pingBinder()` / `checkSelfPermission()` / `requestPermission(int)` 权限检测；
  - `SystemServiceHelper.getSystemService()` + `ShizukuBinderWrapper` 取得 `display` / `input` / `activity` / `window` / `package` / `audio` 等系统服务 Binder；
  - `Shizuku.newProcess()` 执行 `dumpsys input` 解析、`input -d <displayId> tap/keyevent` 注入、`am start --display <id>` 在指定 display 启动应用。
- **应用启动**：公开 API `ActivityOptions.setLaunchDisplayId(displayId)` 为主，Shizuku shell 作为增强尝试。
- **多窗口**：`WindowManager` 维护窗口 id / bounds / Z-order / 焦点 / 最小化 / 最大化 / 可见性，`VrWindowRenderer` 在 VR 中绘制窗口边框、标题栏与控制按钮。

### ⚠️ 尚未实现（不要照着旧版 README 理解）

反编译 `屏连 1.2.7` / `屏易连 1.6.3` 确认它们用的是 **framework hidden API**：

- `IDisplayManager.createVirtualDisplay()` + `VirtualDisplayConfig.Builder`（真正"独立的、能承载任意 Activity 的"虚拟显示器）
- `IInputManager.addUniqueIdAssociationByDescriptor()` / `addPortAssociation()`（把外部手柄/键盘物理绑定到该显示器）

这些类**不在标准 Android SDK 中**，直接 import 会让 Kotlin 编译期报 `Unresolved reference`。本版本刻意没有引入 hidden API stub / AIDL transact / 反射桥，因此：

- 当前 `MediaProjection` 路径显示的是**主屏镜像**；在虚拟显示器上启动应用的行为与"真·独立虚拟屏多窗口"存在语义差距。
- 外部输入设备的**物理绑定**尚未实现；`InputRouter` 目前只做 `dumpsys input` 解析与 shell 注入（尽力而为）。
- `AudioRouter` 目前只是输出设备枚举的 stub。

以上是下一阶段的主要工作，需要在编译期引入 hidden API stub（或 AIDL 生成的 Binder transact），并保证不破坏标准 SDK 可编译性。

## 2. 功能清单

| 功能 | 状态 |
|---|---|
| Shizuku 权限检测 / 请求 | ✅ |
| MediaProjection 授权 + 虚拟显示器创建 / 销毁 | ✅ |
| 虚拟显示器分辨率 / DPI / 刷新率可配置 | ✅ |
| 在指定 display 启动应用（Chrome / YouTube / 浏览器 / 自定义包名） | ✅ |
| Cardboard SDK 立体渲染 + 头部姿态 | ✅ |
| 多窗口数据模型（移动 / 缩放 / 最小化 / 最大化 / 焦点 / Z-order） | ✅ |
| VR 中窗口边框 / 标题栏 / 控制按钮渲染 | ✅ |
| VR 设置界面（亮度 / 对比度 / 饱和度 / 渐晕 / 色差 / 屏幕几何 / 瞳距 / 虚拟屏参数） | ✅ |
| 手柄 / 键盘输入路由到虚拟显示器 | ⚠️ 仅 shell 注入，物理绑定未实现 |
| 按虚拟显示器隔离音频 | ❌ stub |
| Hidden API 独立虚拟屏（真·多窗口） | ❌ 下一阶段 |

## 3. 交互方式

**2D 主界面**（`MainActivity`）

| 操作 | 效果 |
|---|---|
| 点「创建虚拟显示器」 | 弹出系统"开始录制/投射"授权；同意后建立虚拟屏 |
| 点「启动 Chrome / YouTube / 浏览器 / 自定义包名」 | 在虚拟屏上启动对应应用 |
| 点「进入 VR 模式」 | 打开 `VrActivity`，把手机放入 Cardboard |
| 点「VR 设置」 | 打开设置页 |
| 点「销毁虚拟显示器」 | 释放 `VirtualDisplay` 与 `MediaProjection` |

**VR 模式**（`VrActivity`）

| 输入 | 效果 |
|---|---|
| 返回键 / MENU 键 | 退出 VR 回到 2D 界面（虚拟显示器继续运行） |
| 单击 | 选中当前焦点窗口 |
| 双击 | 最大化 / 还原选中窗口 |
| 拖拽 | 移动选中窗口 |
| 双击后拖拽 | 缩放选中窗口 |
| 长按触控板 | 打开窗口菜单（最小化 / 还原） |
| 方向键 | 在可见窗口间切换焦点 |
| A / DPAD_CENTER | 确认 / 聚焦 |
| 长按音量上/下键 | 打开 VR 设置 |

> 头部追踪由 Cardboard SDK 自动处理；渲染遵循 SDK 的共享 FBO 规则（`onDrawEye` 内不做 `glClear`，否则画右眼时会擦掉左眼）。

## 4. 构建方式（只用 GitHub Actions，不在本机编译）

本项目**不在本地编译**。推送到 `main` / `master`（或手动 `workflow_dispatch`）即触发 `.github/workflows/build.yml`：

1. 检出 `VR2DAppLauncher` 与 `googlevr/cardboard@v1.36.0` 源码；
2. **JDK 21** 下 `:sdk:assembleRelease` 从源码构建 Cardboard SDK AAR（其 `sdk/build.gradle` 用 AGP 9.2.0 / compileSdk 35 / NDK 29.0.14206865，且 `java.srcDirs` 包含 `../java_api/src/main/java`，所以 AAR 内含 `CardboardView` 等类）；
3. 用 `find` 定位实际 AAR 输出名并复制为 `app/libs/cardboard-sdk.aar`（不硬编码 `sdk-release.aar`）；
4. 切到 **JDK 17**，执行 `./gradlew assembleDebug --no-daemon --stacktrace`（app 用 AGP 8.7.2 / Gradle 8.9 / Kotlin 2.0.21，Java 与 Kotlin target 均为 17）；
5. **断言 APK 真的产出**：文件存在、体积 > 100 KB、包含 `classes*.dex` 与 `AndroidManifest.xml`，并打印 Kotlin 编译出的 `.class` 数量；
6. 上传 `app-debug.apk` 为 Artifact（保留 30 天）；失败时上传构建报告与日志。

版本链（已核验）：

| 组件 | 版本 |
|---|---|
| Gradle Wrapper | 8.9（官方 `gradlew` + 官方 `gradle-wrapper.jar`） |
| Android Gradle Plugin | 8.7.2 |
| Kotlin Android | 2.0.21 |
| compileSdk / minSdk / targetSdk | 35 / 26 / 33 |
| JDK | 21（构建 Cardboard SDK）/ 17（构建 app） |
| Cardboard SDK | v1.36.0（CI 从源码构建） |
| Shizuku | `dev.rikka.shizuku:api:13.1.5` + `provider:13.1.5` |

> `moe.shizuku:api:13.5.3` / `manager:13.5.3` / `dev.rikka.tools.refine:refine:2.2.0` 这些坐标在 Maven Central 上不存在，早期版本误用已全部移除。

## 5. 设备要求与使用流程

- Android 8.0+（**API 26+**，由 Cardboard SDK AAR 的 minSdk 决定）
- Google Cardboard 眼镜或同类 VR 盒子
- （可选）Shizuku Manager，通过 ADB 激活后可获得增强能力；**不装也能创建虚拟显示器**
- （可选）USB / 蓝牙手柄、键盘、鼠标

1. 安装 CI 产出的 `app-debug.apk`
2. 打开应用 → 点「创建虚拟显示器」→ 在系统弹窗中同意屏幕捕获
3. 点应用按钮在虚拟屏上启动应用
4. 点「VR 设置」按需调整画面参数（改虚拟屏分辨率/DPI 需销毁后重建）
5. 点「进入 VR 模式」并放入 Cardboard

## 6. 依赖（`app/build.gradle.kts`）

```kotlin
implementation(files("libs/cardboard-sdk.aar"))          // CI 从源码构建
implementation("com.google.android.gms:play-services-vision:20.1.3")
implementation("com.google.protobuf:protobuf-javalite:3.19.4")
implementation("org.jspecify:jspecify:1.0.0")           // 以上为 flat AAR 的传递依赖，需显式补齐
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("com.google.android.material:material:1.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

`api:13.1.5` 的 POM 会传递引入 `dev.rikka.shizuku:aidl:13.1.5` 与 `shared:13.1.5`，无需手动声明。

## 7. 目录结构

```
VR2DAppLauncher/
├── .github/workflows/build.yml          # GitHub Actions 云端编译
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   ├── libs/cardboard-sdk.aar           # CI 生成（.gitignore 忽略）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/vr2dapplauncher/
│       │   ├── VR2DApp.kt
│       │   ├── ui/
│       │   │   ├── MainActivity.kt             # 2D 启动器 + MediaProjection 授权
│       │   │   └── VrSettingsActivity.kt       # VR 设置
│       │   ├── vr/
│       │   │   ├── VrActivity.kt               # VR 模式与交互
│       │   │   ├── VrRenderer.kt               # 单窗口 GL 渲染器（兼容保留）
│       │   │   ├── VrWindowRenderer.kt         # 多窗口 GL 渲染器（主路径）
│       │   │   ├── WindowManager.kt            # 多窗口状态机
│       │   │   └── VrSettings.kt               # 设置持久化
│       │   └── shizuku/
│       │       ├── ShizukuUtils.kt             # 权限 / shell
│       │       ├── ShizukuServiceUtils.kt      # 系统服务 Binder
│       │       ├── VirtualDisplayManager.kt    # 虚拟显示器（公开 API）
│       │       ├── InputRouter.kt              # 输入解析 / 注入
│       │       ├── AppLauncher.kt              # 指定 display 启动
│       │       ├── AudioRouter.kt              # 音频路由 stub
│       │       └── VrVirtualDisplayHost.kt     # 生命周期 + SurfaceTexture 桥接
│       └── res/{layout,values,mipmap-*}
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
└── .gitignore
```

## 8. 常见问题

- **Q：CI 几十秒就结束了，是不是没编译？** A：看 `Verify Debug APK was really produced` 步骤。它会打印 APK 字节数、APK 内文件清单、`classes*.dex` 与 Kotlin `.class` 数量；缺任何一项都会让 job 变红。
- **Q：虚拟显示器创建失败？** A：必须同意系统屏幕捕获授权；`MediaProjection` 为 null 时 `VirtualDisplayManager` 会直接返回 null（日志有明确提示）。
- **Q：VR 里黑屏？** A：确认 `app/libs/cardboard-sdk.aar` 是 CI 从 `googlevr/cardboard@v1.36.0` 构建的真 AAR（不是占位文件），且 fragment shader 首行是 `#extension GL_OES_EGL_image_external : require`。
- **Q：Shizuku 显示未授权？** A：可选增强项，不影响虚拟显示器创建。
- **Q：手柄控制不了应用？** A：物理输入绑定（hidden `IInputManager`）尚未实现，见第 1 节。

## 9. 许可证

Apache 2.0
