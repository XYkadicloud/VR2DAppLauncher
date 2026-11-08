package com.vr2dapplauncher.shizuku

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vr2dapplauncher.util.Diagnostics

/**
 * 把第三方应用启动到指定虚拟屏。
 *
 * 主路径（需要 Shizuku）：shell 身份执行 `am start --display <id> -n <component>`。
 *   普通应用调用 ActivityOptions.setLaunchDisplayId() 启动别的包到 secondary display
 *   会被 framework 的 isCallerAllowedToLaunchOnDisplay() 拒绝（需要 ADD_TRUSTED_DISPLAY），
 *   因此只有 shell/root 身份可靠。这也是「屏连 / 屏易连」的做法。
 *
 * 兜底路径（无 Shizuku）：公开 API 尽力尝试；失败只记录、不抛异常。
 *   此时用户仍能在 VR 里看到窗口、调整窗口，但窗口内不会有第三方应用内容。
 */
class AppLauncher(private val context: Context) {

    companion object {
        private const val TAG = "AppLauncher"
    }

    /** @return 是否成功发起启动 */
    fun launchPackage(component: String, displayId: Int): Boolean {
        if (displayId < 0 || component.isEmpty()) return false

        if (ShizukuUtils.hasPermission()) {
            val output = ShizukuUtils.runShell(
                arrayOf("am", "start", "--display", displayId.toString(), "-n", component)
            )
            val ok = output != null && !output.contains("Error", ignoreCase = true)
            val msg = "am start --display $displayId -n $component -> ok=$ok out=${output?.take(180)}"
            Log.d(TAG, msg)
            Diagnostics.record(TAG, msg)
            if (ok) return true
        } else {
            val msg = "Shizuku not granted: cannot launch $component on display $displayId"
            Log.w(TAG, msg)
            Diagnostics.record(TAG, msg)
        }

        return launchNormally(component, displayId)
    }

    private fun launchNormally(component: String, displayId: Int): Boolean = try {
        val cn = ComponentName.unflattenFromString(component)
        if (cn == null) {
            Diagnostics.record(TAG, "bad component string: $component")
            false
        } else {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                this.component = cn
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            context.startActivity(intent, options.toBundle())
            Diagnostics.record(TAG, "startActivity fallback launched $component on $displayId")
            true
        }
    } catch (e: Exception) {
        val msg = "fallback startActivity failed: ${e.javaClass.simpleName}: ${e.message}"
        Log.w(TAG, msg, e)
        Diagnostics.record(TAG, msg)
        false
    }
}
