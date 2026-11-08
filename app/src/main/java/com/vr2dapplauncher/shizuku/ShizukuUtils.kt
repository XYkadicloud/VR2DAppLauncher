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
     * 以 shell 身份执行一条命令。
     *
     * ★ dev.rikka.shizuku:api:13.1.5 里 Shizuku.newProcess() 是 **private**
     *   （run #12 编译器实测：Cannot access ... it is private in 'rikka/shizuku/Shizuku'）。
     *   之前只按 AAR 常量池核对了方法存在性，没核对可见性。这里改用反射取到
     *   ShizukuRemoteProcess（继承 java.lang.Process）；反射失败或 Shizuku 未启动时
     *   降级为普通进程，保证 Shizuku 作为"可选增强"不会让功能硬崩。
     * @return stdout 文本，失败返回 null
     */
    fun runShell(command: Array<String>): String? = try {
        val process: Process = newShizukuProcess(command) ?: Runtime.getRuntime().exec(command)
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

    /** 反射调用 private static Shizuku.newProcess；不可用返回 null */
    private fun newShizukuProcess(command: Array<String>): Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(null, command, null, null) as? Process
    } catch (e: Exception) {
        android.util.Log.w("ShizukuUtils", "Shizuku.newProcess unavailable, fallback to plain process", e)
        null
    }
}
