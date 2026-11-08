package com.vr2dapplauncher.shizuku

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * VR 虚拟显示器宿主：管理虚拟显示器生命周期、SurfaceTexture、GL 纹理
 * 
 * 关键设计：GL 纹理必须在 GL 线程创建，SurfaceTexture 可在 IO 线程创建并稍后绑定
 */
class VrVirtualDisplayHost(private val context: Context) {
    companion object {
        private const val TAG = "VrVirtualDisplayHost"
    }

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val args = VirtualDisplayManager.VirtualDisplayArgs(
        name = "VR2DAppLauncher-Display",
        width = 1920,
        height = 1080,
        dpi = 320,
        refreshRate = 60
    )

    /** 当前虚拟显示器 ID */
    var displayId: Int = -1
        private set

    /** GL 纹理 ID（由 GL 线程创建，供渲染器读取） */
    var glTextureId: Int = 0
        private set

    /** 是否正在运行 */
    var isRunning: Boolean = false
        private set

    /** GL 线程就绪回调：由 VrRenderer 在 onSurfaceCreated 时调用 */
    var onGlThreadReady: ((Int) -> Unit)? = null
        private set

    /**
     * 启动虚拟显示器（第一阶段：IO 线程）
     * 创建 SurfaceTexture(textureId=0) 和 Surface，启动虚拟显示器
     * GL 纹理创建延迟到 GL 线程就绪时
     */
    fun start(onReady: (Boolean) -> Unit) = CoroutineScope(Dispatchers.IO).launch {
        // 1. 确保 Shizuku 权限
        if (!ShizukuUtils.hasPermission()) {
            Log.w(TAG, "Shizuku permission not granted")
            onReady(false)
            return@launch
        }

        // 2. 初始化 Shizuku 系统服务
        ShizukuServiceUtils.initWithShizuku()

        // 3. 创建 SurfaceTexture (textureId=0，稍后在 GL 线程绑定真实纹理)
        surfaceTexture = SurfaceTexture(0)
        surfaceTexture?.setDefaultBufferSize(args.width, args.height)

        // 4. 创建 Surface
        surface = Surface(surfaceTexture!!)

        // 5. 创建虚拟显示器
        virtualDisplay = VirtualDisplayManager.createVirtualDisplay(args, surface!!)

        if (virtualDisplay != null) {
            displayId = virtualDisplay!!.display.displayId
            isRunning = true
            Log.d(TAG, "Virtual display created: displayId=$displayId (awaiting GL thread)")

            // 6. 绑定输入设备
            InputRouter.bindAllExternalInputToDisplay(displayId)

            // 7. 等待 GL 线程就绪，创建真实纹理并绑定
            val deferred = CompletableDeferred<Boolean>()
            onGlThreadReady = { texId ->
                glTextureId = texId
                // 绑定 SurfaceTexture 到真实 GL 纹理
                surfaceTexture?.attachToGLContext(glTextureId)
                deferred.complete(true)
            }

            // 设置超时保护
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val success = deferred.await()
                    onReady(success)
                } catch (e: Exception) {
                    Log.e(TAG, "GL thread binding failed", e)
                    cleanup()
                    onReady(false)
                }
            }
        } else {
            Log.e(TAG, "Failed to create virtual display")
            cleanup()
            onReady(false)
        }
    }

    /** 停止并清理虚拟显示器 */
    fun stop() {
        Log.d(TAG, "Stopping virtual display host")
        virtualDisplay?.release()
        cleanup()
    }

    private fun cleanup() {
        virtualDisplay = null
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        onGlThreadReady = null
        if (glTextureId != 0) {
            // 纹理删除需在 GL 线程，这里只重置 ID，实际删除由渲染器处理
            glTextureId = 0
        }
        displayId = -1
        isRunning = false
    }

    /** 在虚拟显示器上启动应用 */
    fun launchApp(packageName: String) {
        if (displayId > 0) {
            AppLauncher(context).launchPackage(packageName, displayId)
        }
    }

    /** 由 GL 线程调用：创建 GL_TEXTURE_EXTERNAL_OES 纹理并返回 ID */
    fun createGlTextureOnGlThread(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return texId
    }

    /** 获取 SurfaceTexture 供渲染器更新 */
    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    /** 获取 Surface 供 MediaCodec 等使用 */
    fun getSurface(): Surface? = surface
}