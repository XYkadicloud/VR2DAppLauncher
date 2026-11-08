package com.vr2dapplauncher.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限与 shell 能力封装。
 *
 * 只使用 dev.rikka.shizuku:api:13.1.5 的真实公开签名（已用 AAR 常量池核对）：
 *   pingBinder() : Boolean
 *   checkSelfPermission() : Int
 *   requestPermission(Int) : Unit          <-- 注意是 int requestCode，不是 Context
 *   isPreV11() : Boolean
 *   newProcess(String[], String[], String) : ShizukuRemoteProcess
 *
 * 不再引用任何 Android framework hidden 类。
 */
object ShizukuUtils {

    const val REQUEST_CODE_PERMISSION = 1001

    /** Binder 是否存活（Shizuku 服务是否已启动） */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    /** 是否已拿到 Shizuku 授权 */
    fun hasPermission(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /**
     * 请求授权。结果通过 Shizuku.OnRequestPermissionResultListener 或
     * 下次 [hasPermission] 轮询感知（MainActivity 在 onResume 会重新检查）。
     */
    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
        } catch (e: Exception) {
            android.util.Log.w("ShizukuUtils", "requestPermission failed (is Shizuku running?)", e)
        }
    }

    /** 兼容旧调用：忽略传入的 Context */
    fun requestPermission(ignored: android.content.Context?) = requestPermission()

    fun isPreV11(): Boolean = try {
        Shizuku.isPreV11()
    } catch (e: Exception) {
        false
    }

    /**
     * 以 shell 身份执行一条命令（Shizuku 提供的官方能力，替代反射 hidden API）。
     * @return stdout 文本，失败返回 null
     */
    fun runShell(command: Array<String>): String? = try {
        val process = Shizuku.newProcess(command, null, null)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        try {
            process.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        process.destroy()
        output
    } catch (e: Exception) {
        android.util.Log.w("ShizukuUtils", "runShell failed: ${command.joinToString(" ")}", e)
        null
    }
}
