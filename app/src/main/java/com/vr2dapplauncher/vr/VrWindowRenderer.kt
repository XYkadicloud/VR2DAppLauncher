package com.vr2dapplauncher.vr

import android.content.Context
import android.graphics.Rect
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost
import com.vr2dapplauncher.util.Diagnostics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig

/**
 * VR 多窗口渲染器：每个窗口采样自己那块独立虚拟屏的 GL 纹理。
 *
 * 关键点：
 *  - 每块虚拟屏一个 SurfaceTexture / 一张 GL_TEXTURE_EXTERNAL_OES，互不共用；
 *  - onSurfaceCreated 在 GL 线程里建屏（有 EGL context），避免无 context 建 SurfaceTexture；
 *  - onDrawEye 内不 glClear（Cardboard SDK 共享 FBO 只清一次，否则右眼擦掉左眼）；
 *  - shader 编译/链接状态检查，失败写诊断日志，便于真机定位黑屏；
 *  - 无应用时画占位色窗口，保证不是全黑。
 */
class VrWindowRenderer(
    private val context: Context,
    private val virtualDisplayHost: VrVirtualDisplayHost,
    private val settings: VrSettings,
    private val windowManager: WindowManager
) : CardboardView.Renderer {

    companion object {
        private const val TAG = "VrWindowRenderer"

        private val WINDOW_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        // #extension 必须是 fragment shader 的第一行，前面不能有空行或缩进。
        private val WINDOW_FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform int uIsTexture;
            uniform vec4 uColor;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uVignette;
            uniform float uChromaticAberration;
            varying vec2 vTexCoord;
            void main() {
                // 用 if/else 而不是三元表达式：部分 GLES 驱动对
                // "三元表达式里采样 samplerExternalOES" 的编译行为不一致。
                vec4 color = uColor;
                if (uIsTexture == 1) {
                    color = texture2D(uTexture, vTexCoord);
                }
                color.rgb *= uBrightness;
                color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                color.rgb = mix(vec3(gray), color.rgb, uSaturation);
                float dist = length(vTexCoord - 0.5) * 2.0;
                float vig = 1.0 - uVignette * dist * dist;
                color.rgb *= vig;
                color.rg += uChromaticAberration * (vTexCoord - 0.5) * 0.01;
                gl_FragColor = color;
            }
        """.trimIndent()

        private val UI_VERTEX_SHADER = """
            attribute vec4 aPosition;
            uniform mat4 uMVP;
            void main() { gl_Position = uMVP * aPosition; }
        """.trimIndent()

        private val UI_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """.trimIndent()
    }

    private var windowProgram: Int = 0
    private var uiProgram: Int = 0

    private var wPositionHandle: Int = 0
    private var wTexCoordHandle: Int = 0
    private var wMvpHandle: Int = 0
    private var wTexHandle: Int = 0
    private var wStMatrixHandle: Int = 0
    private var wColorHandle: Int = 0
    private var wIsTextureHandle: Int = 0
    private var wBrightnessHandle: Int = 0
    private var wContrastHandle: Int = 0
    private var wSaturationHandle: Int = 0
    private var wVignetteHandle: Int = 0
    private var wChromaticAberrationHandle: Int = 0

    private var uPositionHandle: Int = 0
    private var uMvpHandle: Int = 0
    private var uColorHandle: Int = 0

    private val quadVertexBuffer: FloatBuffer
    private val quadTexCoordBuffer: FloatBuffer
    private val uiQuadBuffer: FloatBuffer

    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val headQuaternion = FloatArray(4).also { it[3] = 1f }

    var selectedWindowId: Long = -1
    private var isDragging = false
    private var isResizing = false
    private var dragWindowStartBounds: Rect? = null

    init {
        val vertices = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
        quadVertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }

        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        quadTexCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texCoords); position(0) }

        val uiVertices = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
        uiQuadBuffer = ByteBuffer.allocateDirect(uiVertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(uiVertices); position(0) }
    }

    override fun onSurfaceCreated(config: EGLConfig) {
        Log.d(TAG, "onSurfaceCreated")
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        windowProgram = createProgram(WINDOW_VERTEX_SHADER, WINDOW_FRAGMENT_SHADER)
        wPositionHandle = GLES20.glGetAttribLocation(windowProgram, "aPosition")
        wTexCoordHandle = GLES20.glGetAttribLocation(windowProgram, "aTexCoord")
        wMvpHandle = GLES20.glGetUniformLocation(windowProgram, "uMVP")
        wTexHandle = GLES20.glGetUniformLocation(windowProgram, "uTexture")
        wStMatrixHandle = GLES20.glGetUniformLocation(windowProgram, "uSTMatrix")
        wColorHandle = GLES20.glGetUniformLocation(windowProgram, "uColor")
        wIsTextureHandle = GLES20.glGetUniformLocation(windowProgram, "uIsTexture")
        wBrightnessHandle = GLES20.glGetUniformLocation(windowProgram, "uBrightness")
        wContrastHandle = GLES20.glGetUniformLocation(windowProgram, "uContrast")
        wSaturationHandle = GLES20.glGetUniformLocation(windowProgram, "uSaturation")
        wVignetteHandle = GLES20.glGetUniformLocation(windowProgram, "uVignette")
        wChromaticAberrationHandle = GLES20.glGetUniformLocation(windowProgram, "uChromaticAberration")

        uiProgram = createProgram(UI_VERTEX_SHADER, UI_FRAGMENT_SHADER)
        uPositionHandle = GLES20.glGetAttribLocation(uiProgram, "aPosition")
        uMvpHandle = GLES20.glGetUniformLocation(uiProgram, "uMVP")
        uColorHandle = GLES20.glGetUniformLocation(uiProgram, "uColor")

        Diagnostics.record(TAG, "programs: window=$windowProgram ui=$uiProgram")

        // ★ 在 GL 线程里创建所有独立虚拟屏，并按屏同步 VR 窗口
        val created = virtualDisplayHost.ensureDisplaysOnGlThread()
        val descriptors = virtualDisplayHost.descriptors()
        // 没授权 / 没建出任何屏时也要留一个占位窗口，避免 VR 全黑且毫无提示。
        windowManager.syncFromSlots(
            if (descriptors.isEmpty()) {
                listOf(
                    WindowManager.SlotDescriptor(
                        index = 0,
                        title = virtualDisplayHost.lastError ?: "未授权屏幕捕获 / 未选择应用",
                        packageName = "",
                        displayId = -1,
                        hasTexture = false
                    )
                )
            } else {
                descriptors
            }
        )
        val first = windowManager.getVisibleWindows().firstOrNull()
        if (first != null) selectedWindowId = first.id
        Diagnostics.record(TAG, "GL ready: $created displays, ${windowManager.getVisibleWindows().size} windows")
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: ${width}x$height")
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        try {
            headTransform.getQuaternion(headQuaternion, 0)
        } catch (e: Exception) {
            Log.w(TAG, "getQuaternion failed", e)
        }
        // 每块虚拟屏各自的纹理更新；单个失败不影响其它窗口
        virtualDisplayHost.updateTexImages()
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // 不要 glClear()：SDK 共享 FBO 只清一次，左右眼靠 glViewport 区分。
        val eyeView = eye.getEyeView()
        val projection = eye.getPerspective(0.1f, 100f)

        val headRotation = FloatArray(16)
        Matrix.setIdentityM(headRotation, 0)
        applyQuaternionRotation(headRotation, headQuaternion)

        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, headRotation, 0, eyeView, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projection, 0, tmp, 0)
        System.arraycopy(projection, 0, projectionMatrix, 0, 16)

        for (window in windowManager.getVisibleWindows()) {
            renderWindow(window)
        }
    }

    override fun onFinishFrame(viewport: Viewport) {
        // Cardboard SDK 处理畸变校正
    }

    override fun onRendererShutdown() {
        GLES20.glDeleteProgram(windowProgram)
        GLES20.glDeleteProgram(uiProgram)
        windowProgram = 0
        uiProgram = 0
    }

    private fun renderWindow(window: WindowManager.WindowInfo) {
        val isFocused = window.id == windowManager.focusedWindowId
        val isSelected = window.id == selectedWindowId
        val bounds = window.bounds
        val displayW = windowManager.displayWidth.toFloat()
        val displayH = windowManager.displayHeight.toFloat()

        val left = -1f + 2f * bounds.left / displayW
        val right = -1f + 2f * bounds.right / displayW
        val bottom = 1f - 2f * bounds.top / displayH
        val top = 1f - 2f * bounds.bottom / displayH

        val windowMatrix = FloatArray(16)
        Matrix.setIdentityM(windowMatrix, 0)
        Matrix.translateM(windowMatrix, 0, (left + right) / 2, (top + bottom) / 2, 0f)
        Matrix.scaleM(windowMatrix, 0, (right - left) / 2, (top - bottom) / 2, 1f)

        val finalMvp = FloatArray(16)
        Matrix.multiplyMM(finalMvp, 0, mvpMatrix, 0, windowMatrix, 0)

        val texId = virtualDisplayHost.texIdFor(window.slotIndex)
        val useTexture = texId > 0 && !window.isPlaceholder

        GLES20.glUseProgram(windowProgram)
        GLES20.glUniformMatrix4fv(wMvpHandle, 1, false, finalMvp, 0)
        GLES20.glUniformMatrix4fv(
            wStMatrixHandle, 1, false, virtualDisplayHost.stMatrixFor(window.slotIndex), 0
        )
        GLES20.glUniform1i(wIsTextureHandle, if (useTexture) 1 else 0)
        GLES20.glUniform4f(
            wColorHandle,
            if (window.isPlaceholder) 0.10f else 0.16f,
            if (window.isPlaceholder) 0.12f else 0.18f,
            if (window.isPlaceholder) 0.20f else 0.24f,
            1f
        )
        GLES20.glUniform1f(wBrightnessHandle, settings.brightness)
        GLES20.glUniform1f(wContrastHandle, settings.contrast)
        GLES20.glUniform1f(wSaturationHandle, settings.saturation)
        GLES20.glUniform1f(wVignetteHandle, settings.vignette)
        GLES20.glUniform1f(wChromaticAberrationHandle, settings.chromaticAberration)

        if (useTexture) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(wTexHandle, 0)
        }

        GLES20.glEnableVertexAttribArray(wPositionHandle)
        GLES20.glVertexAttribPointer(wPositionHandle, 3, GLES20.GL_FLOAT, false, 12, quadVertexBuffer)
        GLES20.glEnableVertexAttribArray(wTexCoordHandle)
        GLES20.glVertexAttribPointer(wTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, quadTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(wPositionHandle)
        GLES20.glDisableVertexAttribArray(wTexCoordHandle)

        if (useTexture) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        // 边框（焦点=蓝，选中=黄，普通=灰）
        val borderColor = if (isFocused) {
            floatArrayOf(0.2f, 0.6f, 1f, 1f)
        } else if (isSelected) {
            floatArrayOf(1f, 0.8f, 0f, 1f)
        } else {
            floatArrayOf(0.35f, 0.35f, 0.35f, 1f)
        }
        drawUiQuad(finalMvp, borderColor, outline = true)

        // 标题栏
        if (isFocused || isSelected) {
            val titleMatrix = windowMatrix.copyOf()
            Matrix.translateM(titleMatrix, 0, 0f, 1.06f, 0f)
            Matrix.scaleM(titleMatrix, 0, 1f, 0.10f, 1f)
            val titleMvp = FloatArray(16)
            Matrix.multiplyMM(titleMvp, 0, mvpMatrix, 0, titleMatrix, 0)
            drawUiQuad(titleMvp, floatArrayOf(0.15f, 0.35f, 0.7f, 0.95f), outline = false)
        }
    }

    private fun drawUiQuad(mvp: FloatArray, color: FloatArray, outline: Boolean) {
        GLES20.glUseProgram(uiProgram)
        GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(uPositionHandle)
        GLES20.glVertexAttribPointer(uPositionHandle, 3, GLES20.GL_FLOAT, false, 12, uiQuadBuffer)
        GLES20.glDrawArrays(
            if (outline) GLES20.GL_LINE_STRIP else GLES20.GL_TRIANGLE_STRIP, 0, 4
        )
        GLES20.glDisableVertexAttribArray(uPositionHandle)
    }

    private fun applyQuaternionRotation(matrix: FloatArray, q: FloatArray) {
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        matrix[0] = 1 - 2 * (yy + zz)
        matrix[1] = 2 * (xy - wz)
        matrix[2] = 2 * (xz + wy)
        matrix[3] = 0f
        matrix[4] = 2 * (xy + wz)
        matrix[5] = 1 - 2 * (xx + zz)
        matrix[6] = 2 * (yz - wx)
        matrix[7] = 0f
        matrix[8] = 2 * (xz - wy)
        matrix[9] = 2 * (yz + wx)
        matrix[10] = 1 - 2 * (xx + yy)
        matrix[11] = 0f
        matrix[12] = 0f
        matrix[13] = 0f
        matrix[14] = 0f
        matrix[15] = 1f
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "shader($type) compile failed: $info")
            Diagnostics.record(TAG, "shader($type) compile failed: $info")
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "program link failed: $info")
            Diagnostics.record(TAG, "program link failed: $info")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    fun setSelected(windowId: Long) {
        selectedWindowId = windowId
        if (windowId != -1L) windowManager.focusWindow(windowId)
    }

    /** @param nx @param ny 归一化累计位移（相对手势起点，屏幕坐标） */
    fun startDrag(windowId: Long) {
        val window = windowManager.getWindow(windowId)
        if (window != null) {
            isDragging = true
            dragWindowStartBounds = Rect(window.bounds)
        }
    }

    fun updateDrag(nx: Float, ny: Float) {
        val startBounds = dragWindowStartBounds ?: return
        if (!isDragging || selectedWindowId == -1L) return
        val dx = (nx * windowManager.displayWidth).toInt()
        val dy = (ny * windowManager.displayHeight).toInt()
        windowManager.moveWindow(selectedWindowId, startBounds.left + dx, startBounds.top + dy)
    }

    fun endDrag() {
        isDragging = false
        dragWindowStartBounds = null
    }

    fun startResize(windowId: Long) {
        val window = windowManager.getWindow(windowId)
        if (window != null) {
            isResizing = true
            dragWindowStartBounds = Rect(window.bounds)
        }
    }

    fun updateResize(nx: Float, ny: Float) {
        val startBounds = dragWindowStartBounds ?: return
        if (!isResizing || selectedWindowId == -1L) return
        val dw = (nx * windowManager.displayWidth).toInt()
        val dh = (ny * windowManager.displayHeight).toInt()
        windowManager.resizeWindow(
            selectedWindowId,
            startBounds.width() + dw,
            startBounds.height() + dh,
            0f,
            0f
        )
    }

    fun endResize() {
        isResizing = false
        dragWindowStartBounds = null
    }
}
