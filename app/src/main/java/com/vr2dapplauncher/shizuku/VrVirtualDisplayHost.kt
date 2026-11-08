package com.vr2dapplauncher.shizuku

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.vr2dapplauncher.util.Diagnostics
import com.vr2dapplauncher.vr.VrSettings
import com.vr2dapplauncher.vr.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 多虚拟屏宿主：一块虚拟屏 = 一个第三方应用 = VR 里的一个窗口。
 *
 * ★ 生命周期关键修正（修掉「已授权仍闪退」）：
 *   旧版在 2D 界面（IO 线程、无 EGL context）就 new SurfaceTexture(0) 并建 VirtualDisplay，
 *   随后 vd.display / InputRouter / AudioRouter 全在 try 之外，协程里未捕获异常直接杀进程。
 *   本版把「建屏」整个推迟到 CardboardView 的 GL 线程（onSurfaceCreated），
 *   SurfaceTexture 从创建起就 attach 到真实 GL 纹理，全流程 try/catch + 诊断记录。
 *
 * 主屏此时正被 VR 界面占满，因此每块虚拟屏都是 OWN_CONTENT_ONLY 独立内容屏，
 * 不镜像主屏；应用由 am start --display 显式放上去。
 */
class VrVirtualDisplayHost(private val context: Context) {

    companion object {
        private const val TAG = "VrVirtualDisplayHost"
        const val MAX_SCREENS = 4

        @Volatile
        var shared: VrVirtualDisplayHost? = null
    }

    /** 一块独立虚拟屏的完整状态。 */
    data class ScreenSlot(
        val index: Int,
        val packageName: String,
        val component: String,
        val title: String,
        var displayId: Int = -1,
        var texId: Int = 0,
        var surfaceTexture: SurfaceTexture? = null,
        var surface: Surface? = null,
        var virtualDisplay: VirtualDisplay? = null,
        val stMatrix: FloatArray = FloatArray(16).apply {
            this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
        }
    )

    private val settings = VrSettings(context)

    val displayWidth: Int get() = settings.virtualDisplayWidth
    val displayHeight: Int get() = settings.virtualDisplayHeight

    @Volatile
    var lastError: String? = null
        private set

    private val slots = ArrayList<ScreenSlot>()

    private fun args() = VirtualDisplayManager.VirtualDisplayArgs(
        width = settings.virtualDisplayWidth,
        height = settings.virtualDisplayHeight,
        dpi = settings.virtualDisplayDpi
    )

    fun getSlots(): List<ScreenSlot> = synchronized(slots) { slots.toList() }

    fun slotAt(index: Int): ScreenSlot? = synchronized(slots) {
        slots.firstOrNull { it.index == index }
    }

    fun texIdFor(index: Int): Int = slotAt(index)?.texId ?: 0
    fun stMatrixFor(index: Int): FloatArray =
        slotAt(index)?.stMatrix ?: FloatArray(16).apply {
            this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
        }
    fun displayIdFor(index: Int): Int = slotAt(index)?.displayId ?: -1
    fun hasTexture(index: Int): Boolean = (slotAt(index)?.texId ?: 0) > 0

    /** 给 WindowManager 用的轻量描述，避免 vr 包反向依赖 shizuku 包。 */
    fun descriptors(): List<WindowManager.SlotDescriptor> = getSlots().map {
        WindowManager.SlotDescriptor(
            index = it.index,
            title = it.title,
            packageName = it.packageName,
            displayId = it.displayId,
            // 只有真正建出虚拟屏（displayId > 0）才算有内容。
            // 占位槽虽然也生成了 texId，但从未 updateTexImage，采样它会花屏。
            hasTexture = it.texId > 0 && it.displayId > 0
        )
    }

    /**
     * ★ 必须由 GL 线程调用（CardboardView.Renderer.onSurfaceCreated）。
     * 为 LaunchPlan 里的每个应用创建一块独立虚拟屏 + 独立 GL 纹理，并启动应用。
     *
     * @return 成功创建的虚拟屏数量
     */
    fun ensureDisplaysOnGlThread(): Int {
        lastError = null
        val projection = ProjectionHolder.get()
        if (projection == null) {
            lastError = "没有屏幕捕获授权，无法创建虚拟屏"
            Diagnostics.record(TAG, lastError ?: "no projection")
            return 0
        }
        // 显式非空局部变量：createSlotLocked 要求非空 MediaProjection，
        // 用局部 val 固定下来，避免嵌套 lambda 里依赖智能转换的编译器边界差异。
        val proj: android.media.projection.MediaProjection = projection

        val requested = LaunchPlan.load(context).take(MAX_SCREENS)
        // 一个应用都没有时仍建 1 块占位屏，保证 VR 不是全黑且有明确提示。
        val plan = if (requested.isEmpty()) {
            listOf(AppInfo("", "", "未选择应用"))
        } else {
            requested
        }

        synchronized(slots) {
            releaseSlotsLocked()
            plan.forEachIndexed { index, app ->
                val slot = ScreenSlot(index, app.packageName, app.component, app.label)
                createSlotLocked(slot, proj)
                slots.add(slot)
            }
        }

        shared = this
        val created = getSlots().count { it.displayId > 0 }
        Diagnostics.record(TAG, "ensureDisplays done: ${getSlots().size} slots, $created displays, error=$lastError")
        return created
    }

    private fun createSlotLocked(slot: ScreenSlot, projection: android.media.projection.MediaProjection) {
        val displayArgs = args()
        try {
            // 1) 每个虚拟屏一张独立的 GL_TEXTURE_EXTERNAL_OES 纹理
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
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            // 2) SurfaceTexture 直接以该 texId 构造（当前线程已有 EGL context）
            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(displayArgs.width, displayArgs.height)
            val surface = Surface(st)

            slot.texId = texId
            slot.surfaceTexture = st
            slot.surface = surface

            // 3) 建独立虚拟屏；占位槽不建屏、不启动应用
            if (slot.component.isEmpty()) {
                Diagnostics.record(TAG, "placeholder slot[${slot.index}] (no app selected)")
                return
            }

            val vd = VirtualDisplayManager.createVirtualDisplay(
                displayArgs, surface, projection, slot.index
            )
            if (vd == null) {
                if (lastError == null) {
                    lastError = "虚拟屏 #${slot.index} 创建失败：系统可能限制一次授权只能创建 1 块虚拟屏"
                }
                return
            }

            slot.virtualDisplay = vd
            slot.displayId = try {
                vd.display.displayId
            } catch (e: Exception) {
                val msg = "get displayId failed: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, msg, e)
                Diagnostics.record(TAG, msg)
                -1
            }

            // 4) 把应用启动到这块虚拟屏。
            //    am start 会起一个 shell 进程并 waitFor()，会阻塞调用线程；
            //    必须丢到 IO 协程，否则 GL 线程（onSurfaceCreated）被卡住，
            //    进入 VR 会明显卡顿甚至 ANR。
            if (slot.displayId >= 0) {
                val component = slot.component
                val targetDisplayId = slot.displayId
                CoroutineScope(Dispatchers.IO).launch {
                    AppLauncher(context).launchPackage(component, targetDisplayId)
                }
            }
        } catch (e: Exception) {
            val msg = "createSlot[${slot.index}] threw ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, msg, e)
            Diagnostics.record(TAG, msg)
            if (lastError == null) lastError = "虚拟屏 #${slot.index} 初始化失败：${e.javaClass.simpleName}"
        }
    }

    /** GL 线程每帧调用：每个虚拟屏各自的 SurfaceTexture 更新到各自纹理。 */
    fun updateTexImages() {
        val list = getSlots()
        for (slot in list) {
            val st = slot.surfaceTexture ?: continue
            if (slot.texId <= 0) continue
            try {
                st.updateTexImage()
                st.getTransformMatrix(slot.stMatrix)
            } catch (e: Exception) {
                // 未 attach / context 丢失时只记录，绝不让渲染线程崩掉
                Log.w(TAG, "updateTexImage[${slot.index}] failed: ${e.message}")
            }
        }
    }

    private fun releaseSlotsLocked() {
        for (slot in slots) {
            try {
                slot.virtualDisplay?.release()
            } catch (e: Exception) {
                Log.w(TAG, "release vd[${slot.index}] failed", e)
            }
            try {
                slot.surface?.release()
            } catch (e: Exception) {
                Log.w(TAG, "release surface[${slot.index}] failed", e)
            }
            try {
                slot.surfaceTexture?.release()
            } catch (e: Exception) {
                Log.w(TAG, "release surfaceTexture[${slot.index}] failed", e)
            }
            if (slot.texId > 0) {
                try {
                    GLES20.glDeleteTextures(1, intArrayOf(slot.texId), 0)
                } catch (e: Exception) {
                    Log.w(TAG, "glDeleteTextures[${slot.index}] failed", e)
                }
            }
        }
        slots.clear()
    }

    /** 2D 界面释放授权时调用；GL 资源随 GL context 销毁回收，这里不动 GL 调用。 */
    fun stop() {
        synchronized(slots) {
            for (slot in slots) {
                try {
                    slot.virtualDisplay?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "release vd failed", e)
                }
                try {
                    slot.surface?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "release surface failed", e)
                }
                try {
                    slot.surfaceTexture?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "release surfaceTexture failed", e)
                }
                slot.virtualDisplay = null
                slot.surface = null
                slot.surfaceTexture = null
                slot.displayId = -1
            }
            slots.clear()
        }
        if (shared === this) shared = null
        Diagnostics.record(TAG, "host stopped")
    }
}
