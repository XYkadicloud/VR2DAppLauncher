#!/usr/bin/env python3
"""VR2DAppLauncher 一次性全量静态自检（不跑 Gradle 的本地门禁）。

与 .github/workflows/build.yml 里的 "Pre-build static audit" 是等价逻辑：
本地跑通 == CI 静态审计必过。这样推送前一次就能把「旧 API 残留 / 缺文件 /
shader 首行 / Manifest 缺声明 / 主屏镜像回归 / FGS 类型缺失」全部拦下来，
不用再靠一次一次推上去试错。

用法：在仓库根目录执行 `python3 .static_audit.py`
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

root = "app/src/main"
java = os.path.join(root, "java")
res = os.path.join(root, "res")
errors = []


def K(rel):
    return os.path.normpath(os.path.join(java, "com/vr2dapplauncher", rel))


def txt(rel):
    p = K(rel)
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else ""


# ---------------------------------------------------------------- 1) 关键文件
# 与 CI inline 审计清单逐条对齐；少一个都说明重构漏了文件。
KT_REQUIRED = [
    "VR2DApp.kt",
    "util/Diagnostics.kt",
    "ui/MainActivity.kt",
    "ui/AppListActivity.kt",
    "ui/VrSettingsActivity.kt",
    "vr/VrActivity.kt",
    "vr/VrWindowRenderer.kt",
    "vr/WindowManager.kt",
    "vr/VrSettings.kt",
    "shizuku/VrVirtualDisplayHost.kt",
    "shizuku/VirtualDisplayManager.kt",
    "shizuku/AppLauncher.kt",
    "shizuku/ProjectionHolder.kt",
    "shizuku/ProjectionService.kt",
    "shizuku/LaunchPlan.kt",
    "shizuku/ShizukuUtils.kt",
    "shizuku/ShizukuServiceUtils.kt",
    "shizuku/InputRouter.kt",
    "shizuku/AudioRouter.kt",
]
XML_REQUIRED = [
    "res/layout/activity_main.xml",
    "res/layout/activity_app_list.xml",
    "res/layout/item_app.xml",
    "res/layout/activity_vr.xml",
    "res/layout/activity_vr_settings.xml",
    "res/layout/item_settings_number.xml",
    "res/layout/item_settings_seekbar.xml",
    "res/layout/item_settings_header.xml",
    "res/layout/item_settings_spinner.xml",
    "AndroidManifest.xml",
]
for rel in KT_REQUIRED:
    if not os.path.isfile(K(rel)):
        errors.append("missing Kotlin file: " + rel)
for rel in XML_REQUIRED:
    if not os.path.isfile(os.path.join(root, rel)):
        errors.append("missing XML file: " + rel)

# ------------------------------------------------- 2) 全量收集 XML / Kotlin
ids, layouts, strings, xml_files = set(), set(), set(), []
for dp, _, fs in os.walk(root):
    for f in fs:
        if f.endswith(".xml"):
            xml_files.append(os.path.join(dp, f))
for p in xml_files:
    try:
        ET.parse(p)
    except Exception as e:
        errors.append("XML parse failed: %s -> %s" % (p, e))
        continue
    t = open(p, encoding="utf-8").read()
    ids.update(re.findall(r"@\+id/(\w+)", t))
    if os.path.normpath(res) + os.sep + "layout" in os.path.normpath(p):
        layouts.add(os.path.splitext(os.path.basename(p))[0])
    strings.update(re.findall(r'<string\s+name="(\w+)"', t))

kt_files = []
for dp, _, fs in os.walk(java):
    for f in fs:
        if f.endswith(".kt"):
            kt_files.append(os.path.join(dp, f))
kt_text = {os.path.normpath(p): open(p, encoding="utf-8").read() for p in kt_files}

# ----------------------------------------------------------- 3) R.* 资源引用
# android.R.layout.simple_spinner_item 是系统资源，不是 app 资源；
# 用负向后顾跳过 "android." 前缀，避免脚本把系统资源误报成缺失的 app 资源。
RES_REF = re.compile(r"(?<!android\.)\bR\.(id|layout|string|color|mipmap)\.(\w+)")
for p, t in kt_text.items():
    for kind, name in RES_REF.findall(t):
        if kind == "id" and name not in ids:
            errors.append("R.id.%s missing in %s" % (name, p))
        if kind == "layout" and name not in layouts:
            errors.append("R.layout.%s missing in %s" % (name, p))
        if kind == "string" and name not in strings:
            errors.append("R.string.%s missing in %s" % (name, p))

# --------------------------------------------------- 4) 已删除的旧接口残留
# 多虚拟屏重构后这些签名都不存在了；哪怕只出现在注释里，CI 也会拦，
# 所以这里按「全文字符串」检查，与 CI 完全一致。
stale = [
    "createGlTextureOnGlThread", "onGlThreadReady",
    "getSurfaceTexture(", ".glTextureId", "getDisplayArgs(",
    ".isRunning", "VrVirtualDisplayHost(this, mediaProjection",
]
for p, t in kt_text.items():
    for s in stale:
        if s in t:
            errors.append("stale API %r in %s" % (s, p))

if os.path.isfile(K("vr/VrRenderer.kt")):
    errors.append("VrRenderer.kt still present (references removed host API)")

# ------------------------------------------- 5) GLSL / Cardboard 运行时纪律
r = txt("vr/VrWindowRenderer.kt")
if '"""#extension GL_OES_EGL_image_external : require' not in r:
    errors.append("VrWindowRenderer fragment shader missing first-line #extension")
if "? texture2D" in r:
    errors.append("ternary sampler still present")
draw_eye = r.split("override fun onDrawEye")[1].split("override fun onFinishFrame")[0]
if "GLES20.glClear(" in draw_eye:
    errors.append("GLES20.glClear inside onDrawEye would erase the left eye")

# ------------------------------------------------------ 6) 独立虚拟屏架构
vdm = txt("shizuku/VirtualDisplayManager.kt")
if "VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR" in vdm:
    errors.append("AUTO_MIRROR is back - main screen is the VR UI")
if "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY" not in vdm:
    errors.append("OWN_CONTENT_ONLY missing - displays would mirror the VR UI")

# -------------------------------------------------------------- 7) Manifest
manifest = open(os.path.join(root, "AndroidManifest.xml"), encoding="utf-8").read()
for token in [
    "AppListActivity", "ProjectionService", "<queries>", "POST_NOTIFICATIONS",
    "FOREGROUND_SERVICE_MEDIA_PROJECTION", 'foregroundServiceType="mediaProjection"',
]:
    if token not in manifest:
        errors.append("AndroidManifest.xml missing " + token)
for cls in re.findall(r'android:name="\.([^"]+)"', manifest):
    if not os.path.isfile(K(cls.replace(".", "/") + ".kt")):
        errors.append("Manifest declares missing class .%s" % cls)

# -------------------------------------------------- 8) 关键交叉引用 / 门禁
main = "ui/MainActivity.kt"
vra = "vr/VrActivity.kt"
wm = "vr/WindowManager.kt"
host = "shizuku/VrVirtualDisplayHost.kt"
sa = "ui/VrSettingsActivity.kt"
ps = "shizuku/ProjectionService.kt"
T = {k: txt(k) for k in [main, vra, wm, host, sa, ps]}

if not re.search(r"fun createIntent\(context: Context\): Intent", T[vra]):
    errors.append("VrActivity.createIntent(context) definition missing")
if "VrActivity.createIntent(this, host.displayId)" in T[main]:
    errors.append("MainActivity still calls old 2-arg createIntent")
if not re.search(r"class WindowManager\(private val context: android\.content\.Context\)", T[wm]):
    errors.append("WindowManager constructor not single-arg")
if "WindowManager(this, displayId)" in T[vra]:
    errors.append("VrActivity still calls 2-arg WindowManager")
for token in ["NumberItem", "item_settings_number", "et_setting_value", "btn_apply"]:
    if token not in T[sa]:
        errors.append("VrSettingsActivity missing " + token)
for sub in ["Header", "SeekBarItem", "FloatSeekBarItem", "SpinnerItem", "NumberItem"]:
    if ("is SettingItem.%s ->" % sub) not in T[sa]:
        errors.append("getItemViewType missing branch for " + sub)
if "hasTexture = it.texId > 0 && it.displayId > 0" not in T[host]:
    errors.append("placeholder hasTexture fix missing")
if "CoroutineScope(Dispatchers.IO).launch" not in T[host]:
    errors.append("GL-thread async app launch missing")
if "cardboardView.onTouchEvent(event)" not in T[vra]:
    errors.append("CardboardView touch forwarding missing")
if "InputRouter.injectTap(vx, vy, displayId)" not in T[vra]:
    errors.append("per-display tap injection missing")
if "windowRenderer.startDrag(selected)" not in T[vra]:
    errors.append("startDrag wiring missing")
# Android 14：声明了 foregroundServiceType 就必须用 3 参数 startForeground，
# 否则 FGS 起不来会被系统杀掉（「已授权仍闪退」）。
if "ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION" not in T[ps]:
    errors.append("ProjectionService missing typed startForeground (Android 14 FGS crash)")

# ------------------------------- 8b) Android 14 MediaProjection 顺序 / 一屏一凭证
# 真机日志：getMediaProjection() 早于 FGS 前台 -> SecurityException -> 闪退。
# 授权结果回来只能存凭证，真正的 getMediaProjection() 必须在 GL 线程建屏时。
if "getMediaProjection(" in T[main]:
    errors.append("MainActivity still calls getMediaProjection (FGS is async -> SecurityException)")
if "ProjectionHolder.addConsent(" not in T[main]:
    errors.append("MainActivity must store the consent via ProjectionHolder.addConsent")
if "ProjectionService.postWhenForeground(" not in T[main]:
    errors.append("MainActivity must defer VR entry until FGS is foreground")
ph = txt("shizuku/ProjectionHolder.kt")
for token in ["fun addConsent(", "fun takeProjection(", "fun consentCount(", "registerCallback"]:
    if token not in ph:
        errors.append("ProjectionHolder missing " + token)
if "fun postWhenForeground(" not in T[ps] or "fun notifyForeground(" not in T[ps]:
    errors.append("ProjectionService missing FGS-ready signal")
if "ProjectionHolder.takeProjection(context)" not in T[host]:
    errors.append("VrVirtualDisplayHost must consume one consent per virtual display")

# ------------------------------------------------------------ 9) 括号平衡
def balance(src):
    i, n = 0, len(src)
    stack = []
    mode = "code"
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if mode == "code":
            if c == chr(34) and src[i:i + 3] == chr(34) * 3:
                mode = "triple"
                i += 3
                continue
            if c == chr(34):
                mode = "str"
                i += 1
                continue
            if c == chr(39):
                mode = "char"
                i += 1
                continue
            if c == "/" and nxt == "/":
                mode = "line"
                i += 2
                continue
            if c == "/" and nxt == "*":
                mode = "block"
                i += 2
                continue
            if c in "({[":
                stack.append(c)
            elif c in ")}]":
                want = {")": "(", "}": "{", "]": "["}[c]
                if not stack or stack[-1] != want:
                    return False
                stack.pop()
        elif mode == "triple":
            if c == chr(34) and src[i:i + 3] == chr(34) * 3:
                mode = "code"
                i += 3
                continue
        elif mode == "str":
            if c == chr(92):
                i += 2
                continue
            if c == chr(34):
                mode = "code"
        elif mode == "char":
            if c == chr(92):
                i += 2
                continue
            if c == chr(39):
                mode = "code"
        elif mode == "line":
            if c == "\n":
                mode = "code"
        elif mode == "block":
            if c == "*" and nxt == "/":
                mode = "code"
                i += 2
                continue
        i += 1
    return len(stack) == 0


for p in kt_files:
    if not balance(open(p, encoding="utf-8").read()):
        errors.append("unbalanced brackets in " + p)

print("Kotlin files: %d | XML files: %d | @+id: %d | layouts: %d" %
      (len(kt_files), len(xml_files), len(ids), len(layouts)))
if errors:
    print("\n=== ERRORS (%d) ===" % len(errors))
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("\nStatic audit OK: no blocking issues found "
      "(equivalent to CI Pre-build static audit)")
