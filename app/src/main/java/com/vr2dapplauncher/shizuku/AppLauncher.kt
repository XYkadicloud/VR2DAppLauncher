package com.vr2dapplauncher.shizuku

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import dev.rikka.tools.refine.Refine

/**
 * 在虚拟显示器上启动应用
 * 对应屏连/屏易连的 ServiceUtils._launchPackage / launchAppWithShizuku
 */
class AppLauncher(private val context: Context) {
    companion object {
        private const val TAG = "AppLauncher"
        private const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        private const val WINDOWING_MODE_FULLSCREEN = 1
    }

    /**
     * 在虚拟显示器上启动指定包名的应用
     */
    fun launchPackage(packageName: String, displayId: Int) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            Log.w(TAG, "Display $displayId not found")
            return
        }
        _launchPackage(packageName, displayId)
    }

    private fun _launchPackage(packageName: String, displayId: Int) {
        if (ShizukuUtils.hasPermission()) {
            launchWithShizuku(packageName, displayId)
        } else {
            launchNormally(packageName, displayId)
        }
    }

    /** 标准路径：Context.startActivity + ActivityOptions.setLaunchDisplayId */
    private fun launchNormally(packageName: String, displayId: Int) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
        } ?: return

        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(displayId)
        }

        context.startActivity(intent, options.toBundle())

        // 启动后绑定输入设备
        InputRouter.bindAllExternalInputToDisplay(displayId)
    }

    /** Shizuku 路径：IActivityManager.startActivityAsUserWithFeature */
    private fun launchWithShizuku(packageName: String, displayId: Int) {
        val pm = context.packageManager
        val baseIntent = pm.getLaunchIntentForPackage(packageName) ?: return
        val component = baseIntent.component ?: return

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_LAUNCHER)
            setComponent(component)
            setPackage(packageName)
        }

        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(displayId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
                setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN)
            }
        }

        try {
            val activityManager = ShizukuServiceUtils.getActivityManager() ?: throw IllegalStateException("ActivityManager not available")
            val unsafeOptions = Refine.unsafeCast(options) as ActivityOptions

            val result: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29+
                result = activityManager.startActivityAsUserWithFeature(
                    null, // caller thread
                    packageName, // featureId
                    null, // callingPackage
                    intent,
                    null, // resolvedType
                    null, // resultTo
                    null, // callingFeatureId
                    0, // userId = current
                    0, // flags
                    null, // profilerInfo
                    unsafeOptions.toBundle(),
                    android.os.UserHandle.USER_CURRENT
                )
            } else {
                result = activityManager.startActivity(
                    null, packageName, intent, null, null, null, 0, 0, null, unsafeOptions.toBundle()
                )
            }

            if (result >= 0) {
                Log.d(TAG, "Shizuku launch successful: $packageName on display $displayId")
                InputRouter.bindAllExternalInputToDisplay(displayId)
            } else {
                Log.w(TAG, "Shizuku launch failed with code: $result")
                showToast("使用 Shizuku 启动应用失败，返回值: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku launch exception", e)
            showToast("使用 Shizuku 启动应用失败: ${e.message}")
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}