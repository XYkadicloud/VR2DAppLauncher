package com.vr2dapplauncher.shizuku

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import com.vr2dapplauncher.util.Diagnostics

/**
 * 独立虚拟显示器创建（★ 本版架构核心）。
 *
 * 为什么不能用 AUTO_MIRROR 镜像主屏：
 *   进入 VR 模式后，物理主屏被全屏 CardboardView 占满，主屏显示的就是 VR 界面本身。
 *   镜像主屏 = 把 VR 界面投回虚拟屏 = 看不到任何第三方应用，且形成自我镜像。
 *   因此每个虚拟屏必须是 OWN_CONTENT_ONLY 的独立内容屏，第三方应用通过
 *   `am start --display <displayId>`（需要 Shizuku/shell 权限）显式启动到该屏。
 *
 * flags 说明：
 *   - VIRTUAL_DISPLAY_FLAG_PUBLIC：系统可见、可作为启动目标
 *   - VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY：只显示放到本屏的内容，不镜像主屏
 *   - 不使用 AUTO_MIRROR（镜像主屏，错误）
 *   - 不使用 PRESENTATION（与 OWN_CONTENT_ONLY 语义冲突，部分 ROM 会拒绝创建）
 */
object VirtualDisplayManager {

    private const val TAG = "VirtualDisplayManager"

    data class VirtualDisplayArgs(
        val name: String = "VR2D-Display",
        val width: Int = 1280,
        val height: Int = 720,
        val dpi: Int = 320
    )

    /**
     * 创建一块虚拟屏。任何失败都返回 null 并记录原因，绝不向外抛异常。
     *
     * Android 14 起「一次用户同意 = 一次 createVirtualDisplay()」：同一个
     * MediaProjection 实例上第二次调用会抛 SecurityException。因此调用方
     * （VrVirtualDisplayHost）必须为每块屏消费一个独立的授权凭证，每个
     * MediaProjection 只在这里用一次；凭证不足的屏不建屏、只留占位并写诊断。
     */
    fun createVirtualDisplay(
        args: VirtualDisplayArgs,
        surface: Surface,
        mediaProjection: MediaProjection,
        index: Int
    ): VirtualDisplay? = try {
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val display = mediaProjection.createVirtualDisplay(
            "${args.name}-$index",
            args.width,
            args.height,
            args.dpi,
            flags,
            surface,
            null,
            null
        )
        if (display == null) {
            val msg = "createVirtualDisplay[$index] returned null (consent invalid or system refused)"
            Log.w(TAG, msg)
            Diagnostics.record(TAG, msg)
        } else {
            val msg = "VirtualDisplay[$index] OK: ${args.width}x${args.height}@${args.dpi}dpi"
            Log.d(TAG, msg)
            Diagnostics.record(TAG, msg)
        }
        display
    } catch (e: Exception) {
        val msg = "createVirtualDisplay[$index] threw ${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, msg, e)
        Diagnostics.record(TAG, msg)
        null
    }
}
