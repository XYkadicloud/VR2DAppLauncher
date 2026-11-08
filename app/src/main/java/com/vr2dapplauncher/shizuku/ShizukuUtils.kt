package com.vr2dapplauncher.shizuku

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限检查与请求工具类
 */
object ShizukuUtils {
    /**
     * 检查 Shizuku 权限是否已授予（Binder 是否存活）
     */
    fun hasPermission(): Boolean = Shizuku.pingBinder()

    /**
     * 请求 Shizuku 权限（会弹出 Shizuku Manager 授权对话框或引导用户 ADB 启动）
     */
    fun requestPermission(context: Context) {
        Shizuku.requestPermission(context)
    }

    /** 是否为 Shizuku v11 之前的版本 */
    fun isPreV11(): Boolean = Shizuku.isPreV11()
}