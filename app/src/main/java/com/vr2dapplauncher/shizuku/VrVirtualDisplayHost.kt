package com.vr2dapplauncher.shizuku

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioDeviceInfo
import android.media.projection.MediaProjection
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.vr2dapplauncher.vr.VrSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 虚拟显示器宿主：管理 VirtualDisplay 生命周期、SurfaceTexture 与 GL 纹理桥接。
 *
 * 关键修正（相对早期版本）：
 *  - 不再把 host 通过 Intent 传递（它不是 Parcelable），改为 companion 单例共享；
 *  - start() 不再等待 GL 线程：2D 界面没有 GL 线程，等待会导致回调永不触发、
 *    "创建虚拟显示器"按钮一直卡在"正在创建"。GL 纹理在进入 VR 后按需绑定；
 *  - 不强制要求 Shizuku：技术验证阶段用 MediaProjection 公开 API 建立虚拟屏幕，
 *    Shizuku 可用时额外初始化系统服务代理。
 */
class VrVirtualDisplayHost(
    private val context: Context,
    private val mediaProjection: MediaProjection? = null
) {
    companion object {
        private const val TAG = "VrVirtualDisplayHost"

        /** MainActivity 与 VrActivity 共享同一个宿主实例 */
        @Volatile
        var shared: VrVirtualDisplayHost? = null
    }

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private val settings = VrSettings(context)
    private val args = VirtualDisplayManager.VirtualDisplayArgs(
        name = "VR2DAppLauncher-Display",
        width = settings.virtualDisplayWidth,
        height = settings.virtualDisplayHeight,
        dpi = settings.virtualDisplayDpi,
        refreshRate = settings.virtualDisplayRefreshRate
    )

    /** 当前虚拟显示器 ID */
    var displayId: Int = -1
        private set

    /** GL_TEXTURE_EXTERNAL_OES 纹理 ID */
    var glTextureId: Int = 0
        private set

    var isRunning: Boolean = false
        private set

    /** 可选：GL 线程就绪回调（VrWindowRenderer.onSurfaceCreated 会触发） */
    var onGlThreadReady: ((Int) -> Unit)? = null

    private val audioRouter = AudioRouter(context)

    /** 启动虚拟显示器（回调在 IO 线程） */
    fun start(onReady: (Boolean) -> Unit) = CoroutineScope(Dispatchers.IO).launch {
        if (isRunning) {
            onReady(true)
            return@launch
        }

        // Shizuku 是增强项，不是前置条件
        if (ShizukuUtils.hasPermission()) {
            ShizukuServiceUtils.initWithShizuku()
        } else {
            Log.w(TAG, "Shizuku not available; continuing with MediaProjection path")
        }

        try {
            surfaceTexture = SurfaceTexture(0)
            surfaceTexture?.setDefaultBufferSize(args.width, args.height)
            surface = Surface(surfaceTexture!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SurfaceTexture/Surface", e)
            cleanup()
            onReady(false)
            return@launch
        }

        virtualDisplay = VirtualDisplayManager.createVirtualDisplay(args, surface!!, mediaProjection)
        val vd = virtualDisplay
        if (vd == null) {
            Log.e(TAG, "Failed to create virtual display (MediaProjection consent missing?)")
            cleanup()
            onReady(false)
            return@launch
        }

        displayId = vd.display.displayId
        isRunning = true
        VrVirtualDisplayHost.shared = this@VrVirtualDisplayHost
        Log.d(
            TAG,
            "Virtual display created: id=$displayId, ${args.width}x${args.height}@${args.dpi}dpi"
        )

        InputRouter.bindAllExternalInputToDisplay(displayId)
        audioRouter.enableRouting(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        audioRouter.startPlaybackMonitoring(displayId)

        onReady(true)
    }

    fun stop() {
        Log.d(TAG, "Stopping virtual display host")
        audioRouter.stopPlaybackMonitoring()
        audioRouter.disableRouting()
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release virtual display failed", e)
        }
        cleanup()
        if (shared === this) shared = null
    }

    private fun cleanup() {
        virtualDisplay = null
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        onGlThreadReady = null
        glTextureId = 0
        displayId = -1
        isRunning = false
    }

    fun launchApp(packageName: String) {
        if (displayId > 0) {
            AppLauncher(context).launchPackage(packageName, displayId)
        } else {
            Log.w(TAG, "No virtual display to launch on")
        }
    }

    /**
     * 由 GL 线程调用：创建 GL_TEXTURE_EXTERNAL_OES 纹理并把
     * 虚拟显示器的 SurfaceTexture 绑定到它（帧才会流进纹理）。
     */
    fun createGlTextureOnGlThread(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        glTextureId = texId
        try {
            surfaceTexture?.attachToGLContext(texId)
            Log.d(TAG, "SurfaceTexture attached to GL texture $texId")
        } catch (e: Exception) {
            Log.e(TAG, "attachToGLContext failed", e)
        }
        return texId
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture
    fun getSurface(): Surface? = surface
    fun getDisplayArgs(): VirtualDisplayManager.VirtualDisplayArgs = args
}
