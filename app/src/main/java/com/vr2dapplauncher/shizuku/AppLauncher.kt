package com.vr2dapplauncher.shizuku

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * 在虚拟显示器上启动应用。
 *
 * 公开 API 路径：ActivityOptions.setLaunchDisplayId(displayId) + startActivity。
 * Shizuku 增强路径：有权限时用 `am start --display <id> -n <component>` 由 shell 侧再尝试。
 * 不再使用 Refine / IActivityManager / setLaunchWindowingMode（均为隐藏 API，编译不过）。
 */
class AppLauncher(private val context: Context) {
    companion object {
        private const val TAG = "AppLauncher"
        private const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }

    fun launchPackage(packageName: String, displayId: Int) {
        val displayManager =
            context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        if (displayManager.getDisplay(displayId) == null) {
            Log.w(TAG, "Display $displayId not found")
            Toast.makeText(context, "虚拟显示器 $displayId 不存在", Toast.LENGTH_SHORT).show()
            return
        }

        launchNormally(packageName, displayId)
        if (ShizukuUtils.hasPermission()) {
            launchWithShizuku(packageName, displayId)
        }
        InputRouter.bindAllExternalInputToDisplay(displayId)
    }

    private fun launchNormally(packageName: String, displayId: Int) {
        val intent: Intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(FLAG_ACTIVITY_NEW_TASK) }
            ?: run {
                Toast.makeText(context, "找不到应用: $packageName", Toast.LENGTH_SHORT).show()
                return
            }
        try {
            val options = ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(displayId)
            }
            context.startActivity(intent, options.toBundle())
            Log.d(TAG, "Launched $packageName on display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "startActivity on display $displayId failed", e)
            Toast.makeText(context, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchWithShizuku(packageName: String, displayId: Int) {
        val component = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.component?.flattenToShortString() ?: return
        val result = ShizukuUtils.runShell(
            arrayOf("am", "start", "--display", displayId.toString(), "-n", component)
        )
        Log.d(TAG, "Shizuku am start result: ${result?.take(200) ?: "no output"}")
    }
}
