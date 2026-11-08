package com.vr2dapplauncher.shizuku

import android.app.IActivityManager
import android.app.IActivityTaskManager
import android.content.pm.IPackageManager
import android.hardware.display.IDisplayManager
import android.hardware.input.IInputManager
import android.media.IAudioService
import android.os.IBinder
import android.permission.IPermissionManager
import android.util.Log
import android.view.IWindowManager
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 通过 Shizuku 获取系统服务 Binder 代理
 * 对应屏连/屏易连的 ServiceUtils.smali
 */
object ShizukuServiceUtils {
    private const val TAG = "ShizukuServiceUtils"

    @Volatile private var activityManager: IActivityManager? = null
    @Volatile private var activityTaskManager: IActivityTaskManager? = null
    @Volatile private var windowManager: IWindowManager? = null
    @Volatile private var displayManager: IDisplayManager? = null
    @Volatile private var inputManager: IInputManager? = null
    @Volatile private var packageManager: IPackageManager? = null
    @Volatile private var permissionManager: IPermissionManager? = null
    @Volatile private var audioManager: IAudioService? = null

    /**
     * 初始化所有系统服务代理
     * 必须在 Shizuku 权限授予后调用
     */
    fun initWithShizuku() {
        if (!ShizukuUtils.hasPermission()) {
            Log.w(TAG, "Shizuku permission not granted, skipping init")
            return
        }

        activityTaskManager = getService("activity_task", IActivityTaskManager.Stub::asInterface)
        activityManager = getService("activity", IActivityManager.Stub::asInterface)
        windowManager = getService("window", IWindowManager.Stub::asInterface)
        displayManager = getService("display", IDisplayManager.Stub::asInterface)
        inputManager = getService("input", IInputManager.Stub::asInterface)

        try {
            permissionManager = getService("permissionmgr", IPermissionManager.Stub::asInterface)
        } catch (e: Exception) {
            Log.w(TAG, "permissionmgr service not available", e)
        }

        packageManager = getService("package", IPackageManager.Stub::asInterface)
        audioManager = getService("audio", IAudioService.Stub::asInterface)

        Log.d(TAG, "Shizuku services initialized: display=${displayManager!=null}, input=${inputManager!=null}, activity=${activityManager!=null}, activityTask=${activityTaskManager!=null}, window=${windowManager!=null}, package=${packageManager!=null}, audio=${audioManager!=null}")
    }

    private fun <T> getService(name: String, asInterface: (IBinder) -> T): T? {
        val binder = SystemServiceHelper.getSystemService(name)
        return if (binder != null) asInterface(ShizukuBinderWrapper(binder)) else null
    }

    // 惰性初始化的获取器

    fun getDisplayManager(): IDisplayManager? {
        if (displayManager == null) initWithShizuku()
        return displayManager
    }

    fun getInputManager(): IInputManager? {
        if (inputManager == null) initWithShizuku()
        return inputManager
    }

    fun getActivityManager(): IActivityManager? {
        if (activityManager == null) initWithShizuku()
        return activityManager
    }

    fun getActivityTaskManager(): IActivityTaskManager? {
        if (activityTaskManager == null) initWithShizuku()
        return activityTaskManager
    }

    fun getWindowManager(): IWindowManager? {
        if (windowManager == null) initWithShizuku()
        return windowManager
    }

    fun getPackageManager(): IPackageManager? {
        if (packageManager == null) initWithShizuku()
        return packageManager
    }

    fun getAudioManager(): IAudioService? {
        if (audioManager == null) initWithShizuku()
        return audioManager
    }

    fun getPermissionManager(): IPermissionManager? {
        if (permissionManager == null) initWithShizuku()
        return permissionManager
    }
}