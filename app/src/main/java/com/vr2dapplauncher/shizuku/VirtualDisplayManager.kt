package com.vr2dapplauncher.shizuku

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface

/**
 * 虚拟显示器创建。
 *
 * 技术验证阶段刻意只用 **公开 Android API**：
 *   MediaProjection.createVirtualDisplay(name, w, h, dpi, flags, surface, cb, handler)
 * 这样无需 root、无需 framework hidden API、无需反射，标准 SDK 一定能编译，
 * 且用户授权后能真正把 2D 应用画面投到虚拟屏幕。
 *
 * 屏连/屏易连的 Shizuku 路径（IDisplayManager + VirtualDisplayConfig.Builder）
 * 依赖隐藏接口，留待后续用 ShizukuBinderWrapper + AIDL transact 补齐；
 * 本项目其余部分（系统服务、输入注入、指定 display 启动应用）仍在使用 Shizuku。
 */
object VirtualDisplayManager {
    private const val TAG = "VirtualDisplayManager"

    data class VirtualDisplayArgs(
        val name: String = "VR2DAppLauncher-Display",
        val width: Int = 1920,
        val height: Int = 1080,
        val dpi: Int = 320,
        val refreshRate: Int = 60,
        val rotatesWithContent: Boolean = false
    )

    fun createVirtualDisplay(
        args: VirtualDisplayArgs,
        surface: Surface,
        mediaProjection: MediaProjection? = null
    ): VirtualDisplay? {
        val projection = mediaProjection ?: run {
            Log.w(TAG, "MediaProjection is null; user consent is required before creating a display")
            return null
        }
        return try {
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val vd = projection.createVirtualDisplay(
                args.name,
                args.width,
                args.height,
                args.dpi,
                flags,
                surface,
                null,
                null
            )
            if (vd != null) {
                Log.d(TAG, "VirtualDisplay created: ${args.width}x${args.height}@${args.dpi}dpi")
            } else {
                Log.w(TAG, "createVirtualDisplay returned null")
            }
            vd
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }
    }
}
