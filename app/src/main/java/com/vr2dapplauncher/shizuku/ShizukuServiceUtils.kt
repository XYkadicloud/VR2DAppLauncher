package com.vr2dapplauncher.shizuku

import android.os.IBinder
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 通过 Shizuku 拿到系统服务的 Binder 代理。
 *
 * 对应屏连/屏易连反编译里的 ServiceUtils.smali：他们把 binder 转成
 * IActivityManager / IDisplayManager / IInputManager 等 **framework hidden 接口**。
 * 标准 Android SDK 不提供这些类，直接 import 会导致 Kotlin 编译期
 * "Unresolved reference"。
 *
 * 因此这里只保留 Binder 获取层（SystemServiceHelper + ShizukuBinderWrapper
 * 都是 Shizuku 公开 API），隐藏接口的调用由调用方用反射 / shell 命令完成。
 */
object ShizukuServiceUtils {
    private const val TAG = "ShizukuServiceUtils"

    private val serviceNames = arrayOf(
        "activity_task", "activity", "window", "display",
        "input", "package", "audio", "permissionmgr"
    )

    @Volatile
    private var initialized = false

    fun initWithShizuku() {
        if (!ShizukuUtils.hasPermission()) {
            Log.w(TAG, "Shizuku permission not granted; system service proxies unavailable")
            return
        }
        val available = serviceNames.mapNotNull { name ->
            if (getServiceBinder(name) != null) name else null
        }
        initialized = true
        Log.d(TAG, "Shizuku services reachable: $available")
    }

    fun isInitialized(): Boolean = initialized

    /**
     * 获取用 ShizukuBinderWrapper 包装过的系统服务 Binder。
     * 调用方可在此基础上用 Class.forName + Stub.asInterface 反射调用隐藏方法。
     */
    fun getServiceBinder(name: String): IBinder? = try {
        val raw = SystemServiceHelper.getSystemService(name)
        if (raw != null) ShizukuBinderWrapper(raw) else null
    } catch (e: Exception) {
        Log.w(TAG, "getSystemService($name) failed", e)
        null
    }

    fun getDisplayBinder(): IBinder? = getServiceBinder("display")
    fun getInputBinder(): IBinder? = getServiceBinder("input")
    fun getActivityBinder(): IBinder? = getServiceBinder("activity")
    fun getActivityTaskBinder(): IBinder? = getServiceBinder("activity_task")
    fun getWindowBinder(): IBinder? = getServiceBinder("window")
    fun getPackageBinder(): IBinder? = getServiceBinder("package")
    fun getAudioBinder(): IBinder? = getServiceBinder("audio")
    fun getPermissionBinder(): IBinder? = getServiceBinder("permissionmgr")
}
