package com.vr2dapplauncher.shizuku

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManagerGlobal
import android.hardware.display.IDisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.projection.IMediaProjection
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import dev.rikka.tools.refine.Refine
import java.lang.reflect.Constructor

/**
 * 虚拟显示器创建管理器
 * 对应屏连/屏易连的 CreateVirtualDisplay.smali
 * 支持双路径：Shizuku (IDisplayManager) 优先，MediaProjection 回退
 */
object VirtualDisplayManager {
    private const val TAG = "VirtualDisplayManager"

    // ===== 虚拟显示器标志位 (来自屏连/屏易连 smali 常量) =====
    private const val FLAG_PUBLIC = 0x1
    private const val FLAG_SUPPORTS_TOUCH = 0x40
    private const val FLAG_ROTATES_WITH_CONTENT = 0x80
    private const val FLAG_OWN_CONTENT_ONLY = 0x8
    private const val FLAG_TRUSTED = 0x400
    private const val FLAG_OWN_DISPLAY_GROUP = 0x800
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 0x100
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 0x2000
    private const val FLAG_ALWAYS_UNLOCKED = 0x1000
    private const val FLAG_OWN_FOCUS = 0x4000
    private const val FLAG_DEVICE_DISPLAY_GROUP = 0x8000

    // 基础标志位 (VR 场景适用)
    private const val BASE_FLAGS = FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_DESTROY_CONTENT_ON_REMOVAL
    // API 28+ 额外标志位
    private const val API_28_FLAGS = BASE_FLAGS or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_ALWAYS_UNLOCKED or FLAG_OWN_FOCUS
    // API 34+ 额外标志位
    private const val API_34_FLAGS = API_28_FLAGS or FLAG_DEVICE_DISPLAY_GROUP

    /**
     * 虚拟显示器配置参数
     */
    data class VirtualDisplayArgs(
        val name: String = "VR2DAppLauncher-Display",
        val width: Int = 1920,
        val height: Int = 1080,
        val dpi: Int = 320,
        val refreshRate: Int = 60,
        val rotatesWithContent: Boolean = false
    )

    /**
     * 创建虚拟显示器
     * @param args 显示器参数
     * @param surface 目标 Surface (来自 SurfaceTexture)
     * @param mediaProjection 可选的 MediaProjection (回退路径需要)
     * @return VirtualDisplay 或 null
     */
    fun createVirtualDisplay(
        args: VirtualDisplayArgs,
        surface: Surface,
        mediaProjection: MediaProjection? = null
    ): VirtualDisplay? {
        // 优先尝试 Shizuku 路径
        if (ShizukuUtils.hasPermission()) {
            try {
                return createByShizuku(args, surface, mediaProjection)
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku path failed, falling back to MediaProjection", e)
            }
        }

        // 回退到 MediaProjection (需要用户授权)
        return mediaProjection?.let { createByMediaProjection(args, surface, it) }
    }

    /**
     * Shizuku 路径：直接调用 IDisplayManager.createVirtualDisplay
     * 无需用户弹窗确认
     */
    private fun createByShizuku(
        args: VirtualDisplayArgs,
        surface: Surface,
        mediaProjection: MediaProjection?
    ): VirtualDisplay? {
        val displayManager = ShizukuServiceUtils.getDisplayManager() ?: return null
        val flags = computeFlags(args.rotatesWithContent)
        val config = buildConfig(args, surface, flags)

        val callback = object : android.hardware.display.IVirtualDisplayCallback.Stub() {
            override fun onPaused() {}
            override fun onResumed() {}
            override fun onStopped() {}
        }

        // 转换 MediaProjection 为 IMediaProjection
        val projection: IMediaProjection? = mediaProjection?.let {
            Refine.unsafeCast(it) as MediaProjection
        }?.projection

        val displayId: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
            displayId = displayManager.createVirtualDisplay(config, callback, projection, "com.android.shell")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28-29
            displayId = displayManager.createVirtualDisplay(
                callback, projection, "com.android.shell",
                args.name, args.width, args.height, args.dpi, surface, flags, "com.android.shell"
            )
        } else { // 旧版本反射回退
            displayId = createViaReflection(displayManager, config, callback, args, surface, flags)
        }

        Log.d(TAG, "Virtual display created via Shizuku: displayId=$displayId, uniqueId will be queried")

        // 包装为 VirtualDisplay 对象
        return wrapVirtualDisplay(displayId, config, callback, args, surface)
    }

    /** 根据 API 级别计算标志位 */
    private fun computeFlags(rotatesWithContent: Boolean): Int {
        var flags = if (rotatesWithContent) BASE_FLAGS or FLAG_ROTATES_WITH_CONTENT else BASE_FLAGS
        return when {
            Build.VERSION.SDK_INT >= 34 -> flags or (FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_ALWAYS_UNLOCKED or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP)
            Build.VERSION.SDK_INT >= 28 -> flags or (FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_ALWAYS_UNLOCKED or FLAG_OWN_FOCUS)
            else -> flags
        }
    }

    /** 构建 VirtualDisplayConfig (API 28+) */
    private fun buildConfig(args: VirtualDisplayArgs, surface: Surface, flags: Int): VirtualDisplayConfig {
        return VirtualDisplayConfig.Builder(args.name, args.width, args.height, args.dpi)
            .setSurface(surface)
            .setFlags(flags)
            .setRequestedRefreshRate(args.refreshRate.toFloat())
            .build()
    }

    /** MediaProjection 回退路径 */
    private fun createByMediaProjection(
        args: VirtualDisplayArgs,
        surface: Surface,
        mediaProjection: MediaProjection
    ): VirtualDisplay {
        return mediaProjection.createVirtualDisplay(
            args.name,
            args.width,
            args.height,
            args.dpi,
            1, // VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            surface,
            null, // callback
            null  // handler
        )
    }

    /** 旧版本 API 反射创建 (API < 28) */
    @Suppress("DEPRECATION")
    private fun createViaReflection(
        displayManager: IDisplayManager,
        config: VirtualDisplayConfig?,
        callback: android.hardware.display.IVirtualDisplayCallback,
        args: VirtualDisplayArgs,
        surface: Surface,
        flags: Int
    ): Int {
        val dmGlobal = DisplayManagerGlobal.getInstance()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> { // API 22+
                dmGlobal.createVirtualDisplayWrapper(config, callback, args.name).displayId
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> { // API 21+
                dmGlobal.createVirtualDisplayWrapper(config, null, callback, args.name).displayId
            }
            else -> {
                // 极旧版本：反射 VirtualDisplay 构造函数
                val clazz = Class.forName("android.hardware.display.VirtualDisplay")
                val constructor = clazz.getDeclaredConstructor(
                    DisplayManagerGlobal::class.java,
                    android.view.Display::class.java,
                    android.hardware.display.IVirtualDisplayCallback::class.java,
                    android.view.Surface::class.java
                )
                constructor.isAccessible = true
                val realDisplay = dmGlobal.getRealDisplay(0)
                val vd = constructor.newInstance(dmGlobal, realDisplay, callback, surface) as VirtualDisplay
                vd.displayId
            }
        }
    }

    /** 将 displayId 包装为 VirtualDisplay 对象 */
    @Suppress("DEPRECATION")
    private fun wrapVirtualDisplay(
        displayId: Int,
        config: VirtualDisplayConfig?,
        callback: android.hardware.display.IVirtualDisplayCallback,
        args: VirtualDisplayArgs,
        surface: Surface
    ): VirtualDisplay {
        val dmGlobal = DisplayManagerGlobal.getInstance()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> { // API 30+
                dmGlobal.createVirtualDisplayWrapper(config, callback, displayId)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> { // API 22-29
                dmGlobal.createVirtualDisplayWrapper(config, null, callback, displayId)
            }
            else -> {
                val clazz = Class.forName("android.hardware.display.VirtualDisplay")
                val constructor = clazz.getDeclaredConstructor(
                    DisplayManagerGlobal::class.java,
                    android.view.Display::class.java,
                    android.hardware.display.IVirtualDisplayCallback::class.java,
                    android.view.Surface::class.java
                )
                constructor.isAccessible = true
                val realDisplay = dmGlobal.getRealDisplay(displayId)
                constructor.newInstance(dmGlobal, realDisplay, callback, surface) as VirtualDisplay
            }
        }
    }
}