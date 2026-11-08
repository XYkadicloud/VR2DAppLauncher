# VR2DAppLauncher

把普通 2D Android 应用投放到 Google Cardboard VR 界面中观看与操作 —— 一个**独立的新项目**（不是 OpenNOW-VR 仓库的修改），当前处于**技术验证阶段**。所有编译都在 **GitHub Actions 云端**完成，不在本机编译。

## 1. 当前架构：多块独立虚拟屏（★ 请按这一节理解）

```
用户在「选择应用」里勾选 N 个应用（最多 4 个）
  → 在 2D 界面点 N 次「授权屏幕捕获」（Android 14：一次同意 = 一块虚拟屏）
  → 一键进入 VR / 启动即 VR
  → VrActivity 全屏 CardboardView 占满物理主屏
  → GL 线程 onSurfaceCreated() 里为「每个应用」消费一个授权凭证：
        ProjectionHolder.takeProjection()  → getMediaProjection()
        MediaProjection.createVirtualDisplay(
            flags = PUBLIC | OWN_CONTENT_ONLY,   // 不镜像主屏
            surface = SurfaceTexture(texId))     // 每屏一张独立 GL_TEXTURE_EXTERNAL_OES
  → am start --display <displayId> -n <component>（Shizuku/shell 身份）
        把该应用真正启动到这块虚拟屏上
  → WindowManager 把 N 块屏同步成 VR 里的 N 个窗口（各自可独立移动/缩放）
  → VrWindowRenderer 每帧把每块屏的纹理采样到对应窗口
```

### 为什么必须用独立虚拟屏，不能镜像主屏

进入 VR 后，**物理主屏被全屏 `CardboardView` 占满，主屏显示的就是 VR 界面本身**。若用 `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` 镜像主屏，等于把 VR 界面再投回虚拟屏，形成自我镜像，**根本拿不到第三方应用内容**。所以每块屏都必须是 `OWN_CONTENT_ONLY` 的独立内容屏，应用由 `am start --display` 显式放上去。

### ★ 本轮修掉的闪退（真机日志定位）

用户反馈「已经授予了权限，一点创建虚拟显示器 / 进 VR 就闪退」。诊断日志反复出现：

```
[MainActivity] getMediaProjection threw SecurityException:
    Media projections require a foreground service of type
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
```

日志里**完全没有 `[ProjectionService]` 记录** —— 这就是根因线索：

| 旧问题 | 本版处理 |
|---|---|
| `getMediaProjection()` 在 `ProjectionService.start()` **之前**同步调用；而 `startForegroundService()` 是**异步**的，Service 的 `onCreate()/startForeground()` 还排在主线程消息队列里 | `onActivityResult()` **只把授权凭证存进 `ProjectionHolder`**，绝不在此处调 `getMediaProjection()` |
| 抛 `SecurityException` 后直接 `return` → 前台服务一次都没启动 → 恶性循环 | `ProjectionService.postWhenForeground()` 把「刷新 UI / 进 VR」排到 FGS 真正 `startForeground()` 成功之后；带 4 秒超时兜底，绝不卡死 |
| 旧版一个全局 `MediaProjection` 反复用来建多块屏 | Android 14 官方规定「一次同意 = 一次 `createVirtualDisplay()`」，改为**一屏一个凭证**：`ProjectionHolder.takeProjection()` 在 GL 线程逐块消费，每个 `MediaProjection` 只用一次 |
| 凭不足 / 建屏失败时行为不明 | 缺凭证的槽只生成占位纹理、不建屏，写 `lastError` + `Diagnostics`，不闪退不全黑 |
| 出问题只能连 adb logcat | 2D 界面「导出诊断日志」：弹窗显示 + 复制剪贴板 + 写 `vr2d_diagnostics.txt` |

另外 `ProjectionService` 已用 API 29+ 的 3 参数 `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`（判断用 `>= Q` 而非 `>= 34`，可覆盖 `SDK_INT` 不等于 34 但仍要求 typed FGS 的厂商 ROM），Manifest 已声明 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` / `POST_NOTIFICATIONS` 与 `foregroundServiceType="mediaProjection"`。

官方依据（[媒体投影 · Android Developers](https://developer.android.google.cn/guide/topics/large-screens/media-projection)）：

> 「您的应用必须在每次媒体投屏会话之前征得用户同意。**会话是指对 `createVirtualDisplay()` 的单次调用**。`MediaProjection` 令牌必须仅使用一次……在 Android 14 或更高版本中，把从 `createScreenCaptureIntent()` 返回的 Intent 传给 `getMediaProjection()` 超过一次，或在同一 `MediaProjection` 实例上多次调用 `createVirtualDisplay()`，都会抛出 `SecurityException`。」
>
> 「如果您的应用未注册回调，则对 `createVirtualDisplay()` 的任何调用都会抛出 `IllegalStateException`。」
>
> 「以 Android 14 或更高版本为目标平台时，清单必须包含 `mediaProjection` 前台服务类型的权限声明……通过调用 `startForeground()` 启动媒体投屏服务。」

## 2. 功能清单

| 功能 | 状态 |
|---|---|
| MediaProjection 授权 + **FGS 前台顺序正确** + 崩溃诊断导出 | ✅ 本轮修复 |
| **授权凭证池**（一次同意 = 一块独立虚拟屏） | ✅ 本轮新增 |
| 多块独立虚拟屏（`OWN_CONTENT_ONLY`，不镜像主屏） | ✅ 代码支持；Android 14 会话限制见第 4 节 |
| **读取已安装应用列表**，勾选最多 4 个应用 | ✅ |
| 每个应用通过 `am start --display` 启动到自己的虚拟屏 | ✅（需 Shizuku） |
| VR 里 N 个窗口**各自独立调整位置 / 大小** | ✅ |
| 单击命中窗口并把点击注入对应 `displayId` | ✅（需 Shizuku） |
| 一键进入 VR / **启动即进入 VR**（跳过 2D 界面） | ✅ |
| 2D 管理界面保留 | ✅ |
| **分辨率 / DPI / 刷新率可直接写入数值**（其余连续量仍用滑块） | ✅ |
| Cardboard SDK 立体渲染 + 头部姿态 | ✅ |
| 亮度 / 对比度 / 饱和度 / 渐晕 / 色差 / 瞳距 | ✅ |
| 按虚拟屏隔离音频 | ❌ stub |
| `IInputManager` 物理绑定外部手柄/键盘 | ❌ 未实现（当前为 shell 注入） |
| **Shizuku UserService + `IDisplayManager` hidden API 无限制多屏** | ❌ 下一步，见第 4 节 |

## 3. 交互方式

**2D 主界面（`MainActivity`，保留）**

| 操作 | 效果 |
|---|---|
| 授权屏幕捕获 | 弹出系统授权；成功后**只存一个凭证**，启动 FGS，FGS 前台后刷新 UI |
| 再点授权按钮 | 再加一个凭证（想同时开几个应用就点几次） |
| **长按授权按钮** | 释放全部凭证并停止前台服务 |
| 选择应用 | 打开 `AppListActivity`，可搜索、点选 / 取消，最多 4 个 |
| ▶ 一键进入 VR | 没有凭证自动先弹授权，FGS 就绪后直接进 VR |
| 启动即进入 VR 开关 | 打开后下次启动应用直接进 VR（未授权时自动先请求授权） |
| VR 设置 | 打开设置页 |
| 导出诊断日志 | 弹窗显示最近日志，可复制，并写到应用私有外部目录 |

**VR 模式（`VrActivity`）**

| 输入 | 效果 |
|---|---|
| 返回键 / MENU | 退出 VR 回 2D（虚拟屏与授权继续运行） |
| 单击 | 命中窗口并选中，同时把点击注入该窗口对应的虚拟屏（`input -d <displayId> tap x y`） |
| 双击 | 最大化 / 还原选中窗口 |
| 拖拽 | 移动选中窗口 |
| 双击后拖拽 | 缩放选中窗口 |
| 长按触控板 | 窗口菜单（最小化 / 还原） |
| 方向键 | 在可见窗口间切换焦点 |
| A / DPAD_CENTER | 确认 / 聚焦 |
| 长按音量上/下键 | 打开 VR 设置 |

> 渲染遵循 Cardboard SDK 共享 FBO 规则：`onDrawEye()` 内**不** `glClear()`（否则画右眼时擦掉左眼）；`samplerExternalOES` 的 fragment shader 首行必须是 `#extension GL_OES_EGL_image_external : require`。

## 4. 已知限制（如实记录，不假装已解决）

1. **★ Android 14+ 同一时刻只允许一个活跃的 MediaProjection 会话。** 官方文档 `onStop()` 的终止原因明确包含「另一个媒体投屏会话开始」。本版虽然为每块屏准备了独立凭证，但在 Android 14 上连续 `getMediaProjection()` 会让前一个会话收到 `onStop` 并被系统停掉，**实际往往只有最后一块屏存活**，其余窗口显示占位并在诊断里留下 `MediaProjection.onStop (session ended / superseded)`。Android 13 及以下不受此限制，多凭证可真正多开。
2. **要绕过第 1 条实现无限制多屏，唯一可靠路径是 Shizuku UserService + `IDisplayManager.createVirtualDisplay()` hidden API**：在 shell 身份的 UserService 里直接向 `DisplayManagerService` 建 `OWN_CONTENT_ONLY` 虚拟屏，完全不经过 `MediaProjectionManagerService`，因此不受「一次同意一屏 / 单会话」约束，也不需要用户反复授权。这正是反编译 `屏连 1.2.7` / `屏易连 1.6.3` 看到的做法（`VirtualDisplayConfig.Builder` + `IInputManager` 输入设备绑定）。实现它需要 AIDL UserService、hidden API 豁免（如 `org.lsposed.hiddenapibypass`）与真机联调，属于**独立的下一步**，本轮未做。
3. **没有 Shizuku 时，第三方应用无法被可靠地放进独立虚拟屏**。普通 app 用 `ActivityOptions.setLaunchDisplayId()` 把别的包启动到 secondary display 会被 framework 的 `isCallerAllowedToLaunchOnDisplay()` 拦截（需要 `ADD_TRUSTED_DISPLAY`）。代码保留公开 API 兜底，但窗口内可能没有应用内容。
4. **Android 14 每次进入 VR 通常需要重新授权**：GL context 重建会 `releaseSlotsLocked()` 重新建屏，而凭证是一次性的。这是系统规则，不是 bug。
5. `AudioRouter` 只是输出设备枚举 stub；`InputRouter` 只做 `dumpsys input` 解析与 `input -d` shell 注入。

## 5. 构建方式（只用 GitHub Actions）

推送到 `main` / `master`（或手动 `workflow_dispatch`）触发 `.github/workflows/build.yml`：

1. 检出 `VR2DAppLauncher` 与 `googlevr/cardboard@v1.36.0` 源码；
2. **JDK 21** 下 `:sdk:assembleDebug` 构建 Cardboard SDK AAR。
   > ⚠️ 必须用 **debug** 变体：SDK 的 `release { minifyEnabled true }` 会让 R8 把 `CardboardView` / `HeadTransform` / `Viewport` 削掉（run #11 实测 release AAR 的 `classes.jar` 只有 34 个条目、0 个 Cardboard API 类）。
3. 复制 `sdk-debug.aar` 为 `app/libs/cardboard-sdk.aar`；
4. **`Verify Cardboard SDK AAR contents`**：断言 `classes.jar` 含 `CardboardView` / `CardboardView$Eye` / `$Renderer` / `HeadTransform` / `Viewport` 且含 native `.so`；
5. 切 **JDK 17**；
6. **`Pre-build static audit`**：编译前断言关键 Kotlin / XML 文件齐全、旧 API 无残留、`VrRenderer.kt` 已删除、shader `#extension` 首行、无 `AUTO_MIRROR`、Manifest 声明齐全，**并新增回归门禁**：`MainActivity` 不得再出现 `getMediaProjection(`、必须走 `addConsent` + `postWhenForeground`、`ProjectionHolder` 必须有 `addConsent/takeProjection/consentCount/registerCallback`、`ProjectionService` 必须有 FGS 就绪信号、`VrVirtualDisplayHost` 必须 `takeProjection`；
7. `./gradlew assembleDebug --no-daemon --stacktrace`（AGP 8.7.2 / Gradle 8.9 / Kotlin 2.0.21，Java 与 Kotlin target 17）；
8. **`Verify Debug APK was really produced`**：断言 APK 存在、> 100 KB、含 `classes*.dex` 与 `AndroidManifest.xml`，且编译出的 Kotlin `.class` 数量 ≥ 20；
9. 上传 `app-debug.apk` 为 Artifact（30 天）；失败时上传构建报告。

本地等价门禁（不跑 Gradle）：仓库根目录执行 `python3 .static_audit.py`，与 CI inline 审计逻辑一致。

版本链（已核验，run #13 `42459835563`、run #14 `42472244952` 均 success）：

| 组件 | 版本 |
|---|---|
| Gradle Wrapper | 8.9（官方 `gradlew` + `gradle-wrapper.jar`） |
| Android Gradle Plugin | 8.7.2 |
| Kotlin Android | 2.0.21 |
| compileSdk / minSdk / targetSdk | 35 / 26 / 33 |
| JDK | 21（Cardboard SDK）/ 17（app） |
| Cardboard SDK | v1.36.0（CI 从源码构建 debug AAR） |
| Shizuku | `dev.rikka.shizuku:api:13.1.5` + `provider:13.1.5` |

> `moe.shizuku:api:13.5.3` / `manager:13.5.3` / `dev.rikka.tools.refine:refine:2.2.0` 在 Maven Central 上不存在，早期误用已全部移除。

## 6. 设备要求与使用流程

- Android 8.0+（API 26+，由 Cardboard SDK AAR 的 minSdk 决定）
- Google Cardboard 眼镜或同类 VR 盒子
- **强烈建议**：Shizuku（通过 ADB 激活并给本 app 授权），否则第 4 节第 3 条的应用启动限制会生效

1. 安装 CI 产出的 `app-debug.apk`
2. 打开应用 → 点「授权屏幕捕获」并在系统弹窗同意。**想同时开几个应用就点几次授权**（Android 14 每块虚拟屏一次同意）；长按可释放
3. 点「选择应用」勾选要同时运行的应用（最多 4 个）
4. （可选）点「VR 设置」直接输入分辨率 / DPI / 刷新率数值
5. 点「▶ 一键进入 VR 模式」，把手机放入 Cardboard
6. 若闪退或窗口是灰的，回 2D 界面点「导出诊断日志」，把内容复制出来

## 7. 依赖（`app/build.gradle.kts`）

```kotlin
implementation(files("libs/cardboard-sdk.aar"))          // CI 从源码构建
implementation("com.google.android.gms:play-services-vision:20.1.3")
implementation("com.google.protobuf:protobuf-javalite:3.19.4")
implementation("org.jspecify:jspecify:1.0.0")           // flat AAR 的传递依赖需显式补齐
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("com.google.android.material:material:1.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

`api:13.1.5` 的 POM 会传递引入 `aidl:13.1.5` 与 `shared:13.1.5`。`Shizuku.newProcess()` 在 13.1.5 中是 **private**，因此 `ShizukuUtils` 用反射调用，失败降级为 `Runtime.exec()`。

## 8. 目录结构

```
VR2DAppLauncher/
├── .github/workflows/build.yml          # GitHub Actions 云端编译 + 静态自检
├── .static_audit.py                     # 本地等价门禁（不跑 Gradle）
├── app/build.gradle.kts
├── app/libs/cardboard-sdk.aar           # CI 生成（.gitignore 忽略）
└── app/src/main/
    ├── AndroidManifest.xml              # queries / FGS / 4 个 Activity
    ├── java/com/vr2dapplauncher/
    │   ├── VR2DApp.kt                   # Application + 全局崩溃捕获
    │   ├── util/Diagnostics.kt          # 运行诊断 + 导出
    │   ├── ui/
    │   │   ├── MainActivity.kt          # 2D 管理界面 / 授权凭证 / 一键进 VR / 诊断导出
    │   │   ├── AppListActivity.kt       # 已安装应用列表 + 多选
    │   │   └── VrSettingsActivity.kt   # 设置（含数值输入项）
    │   ├── vr/
    │   │   ├── VrActivity.kt            # VR 模式与交互 / 点击注入
    │   │   ├── VrWindowRenderer.kt     # 多窗口 GL 渲染（主路径）
    │   │   ├── WindowManager.kt        # 多窗口状态机
    │   │   └── VrSettings.kt           # 设置持久化
    │   └── shizuku/
    │       ├── ShizukuUtils.kt          # 权限 / shell
    │       ├── ShizukuServiceUtils.kt   # 系统服务 Binder
    │       ├── ProjectionHolder.kt      # ★ 授权凭证池 / takeProjection / Callback
    │       ├── ProjectionService.kt     # ★ mediaProjection 前台服务 + FGS 就绪信号
    │       ├── LaunchPlan.kt            # 用户勾选的应用（AppInfo）
    │       ├── VirtualDisplayManager.kt # OWN_CONTENT_ONLY 独立虚拟屏
    │       ├── AppLauncher.kt           # am start --display
    │       ├── InputRouter.kt           # 输入解析 / input -d 注入
    │       ├── AudioRouter.kt           # 音频路由 stub
    │       └── VrVirtualDisplayHost.kt  # ★ 多屏宿主 / GL 线程逐凭证建屏
    └── res/{layout,values,mipmap-*}
```

## 9. 常见问题

- **Q：CI 几十秒就结束，是不是没编译？** A：看 `Verify Debug APK was really produced`，会打印 APK 字节数、内部文件清单、`classes*.dex` 与 Kotlin `.class` 数量；缺任一项 job 变红。
- **Q：已授权仍闪退？** A：本轮已修根因 —— 授权只存凭证、`getMediaProjection()` 推迟到 FGS 前台之后的 GL 线程。若仍异常，点「导出诊断日志」，里面会有 `[ProjectionService]` / `[ProjectionHolder]` / `CRASH` 堆栈。
- **Q：VR 里只有一个窗口有内容 / 其余是灰的？** A：Android 14 只允许一个活跃 MediaProjection 会话，且一次同意只够一块屏。看诊断里的 `MediaProjection.onStop (session ended / superseded)`；彻底解决需要第 4 节第 2 条的 Shizuku UserService 方案。
- **Q：能同时开多个应用吗？** A：代码按「一屏一凭证」支持；Android 13 及以下可真多开，Android 14 受系统会话限制会降级（见第 4 节）。
- **Q：为什么每次进 VR 都要重新授权？** A：Android 14 的凭证是一次性的，GL context 重建必须重新建屏，这是系统规则。
- **Q：改了分辨率没生效？** A：虚拟屏在进入 VR 的 GL 线程创建，改完设置需**退出 VR 再重新进入**才会重建。
- **Q：手柄控制不了应用？** A：物理输入绑定（hidden `IInputManager`）尚未实现；当前单击会通过 `input -d <displayId> tap` 注入，需要 Shizuku。

## 10. 许可证

Apache 2.0
