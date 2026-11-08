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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig

/**
 * VR 窗口渲染器：在 VR 中渲染多个应用窗口
 *
 * 全量审查修正：
 *  - 所有 shader 常量改用 `val`（const val 不能调用 trimIndent()，会编译失败）；
 *  - WINDOW_FRAGMENT_SHADER 首行加 #extension（samplerExternalOES 必需）；
 *  - onDrawEye 不再 glClear()（SDK 共享 FBO 只清一次，否则右眼擦掉左眼）；
 *  - selectedWindowId 改为 public（VrActivity 需要读写）；
 *  - dragWindowStartBounds 用局部 val 智能转换，避免 nullable var 属性无法 smart cast；
 *  - 移除对 UI shader 中不存在的 uTexture uniform 的 glUniform1i 调用。
 */
class VrWindowRenderer(
    private val context: Context,
    private val virtualDisplayHost: VrVirtualDisplayHost,
    private val settings: VrSettings,
    private val windowManager: WindowManager
) : CardboardView.Renderer {

    companion object {
        private val TAG = "VrWindowRenderer"

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

        // #extension MUST be the first directive in a GLES ES 1.00 fragment shader.
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
                vec4 color = uIsTexture == 1
                    ? texture2D(uTexture, vTexCoord)
                    : uColor;
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

        private val BORDER_VERTEX_SHADER = """
            attribute vec4 aPosition;
            uniform mat4 uMVP;
            void main() { gl_Position = uMVP * aPosition; }
        """.trimIndent()

        private val BORDER_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
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

    // 着色器程序
    private var windowProgram: Int = 0
    private var borderProgram: Int = 0
    private var uiProgram: Int = 0

    // Window 着色器句柄
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

    // Border 着色器句柄
    private var bPositionHandle: Int = 0
    private var bMvpHandle: Int = 0
    private var bColorHandle: Int = 0

    // UI 着色器句柄
    private var uPositionHandle: Int = 0
    private var uMvpHandle: Int = 0
    private var uColorHandle: Int = 0

    // 顶点缓冲区
    private val quadVertexBuffer: FloatBuffer
    private val quadTexCoordBuffer: FloatBuffer
    private val borderVertexBuffer: FloatBuffer
    private val uiQuadBuffer: FloatBuffer

    // MVP 矩阵
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // ST 矩阵
    private val stMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }

    // 头部姿态
    private val headQuaternion = FloatArray(4).also { it[3] = 1f }

    // 交互状态（selectedWindowId 为 public，VrActivity 会读写）
    private var hoveredWindowId: Long = -1
    var selectedWindowId: Long = -1
    private var isDragging = false
    private var isResizing = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragWindowStartBounds: Rect? = null

    // 虚拟显示器纹理
    private var displayTextureId = 0

    init {
        val vertices = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
        quadVertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(vertices); position(0) }

        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        quadTexCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(texCoords); position(0) }

        val borderVertices = floatArrayOf(
            -1.02f, -1.02f, 0.01f, 1.02f, -1.02f, 0.01f,
            -1.02f, 1.02f, 0.01f, 1.02f, 1.02f, 0.01f
        )
        borderVertexBuffer = ByteBuffer.allocateDirect(borderVertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(borderVertices); position(0) }

        val uiVertices = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
        uiQuadBuffer = ByteBuffer.allocateDirect(uiVertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(uiVertices); position(0) }
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

        borderProgram = createProgram(BORDER_VERTEX_SHADER, BORDER_FRAGMENT_SHADER)
        bPositionHandle = GLES20.glGetAttribLocation(borderProgram, "aPosition")
        bMvpHandle = GLES20.glGetUniformLocation(borderProgram, "uMVP")
        bColorHandle = GLES20.glGetUniformLocation(borderProgram, "uColor")

        uiProgram = createProgram(UI_VERTEX_SHADER, UI_FRAGMENT_SHADER)
        uPositionHandle = GLES20.glGetAttribLocation(uiProgram, "aPosition")
        uMvpHandle = GLES20.glGetUniformLocation(uiProgram, "uMVP")
        uColorHandle = GLES20.glGetUniformLocation(uiProgram, "uColor")

        Log.d(TAG, "Shaders created: window=$windowProgram, border=$borderProgram, ui=$uiProgram")

        displayTextureId = virtualDisplayHost.createGlTextureOnGlThread()
        virtualDisplayHost.onGlThreadReady?.invoke(displayTextureId)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: ${width}x$height")
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        headTransform.getQuaternion(headQuaternion, 0)

        val surfaceTexture = virtualDisplayHost.getSurfaceTexture()
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(stMatrix)
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // 不要 glClear()：Cardboard SDK 只清一次共享 FBO，左右眼靠 glViewport 区分；
        // 在这里 clear 会在画右眼时把左眼擦掉。黑底由 glClearColor + SDK 的 clear 提供。
        val eyeView = eye.getEyeView()
        val projection = eye.getPerspective(0.1f, 100f)

        val headRotation = FloatArray(16)
        Matrix.setIdentityM(headRotation, 0)
        applyQuaternionRotation(headRotation, headQuaternion)

        // MVP = projection * headRotation * eyeView
        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, headRotation, 0, eyeView, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projection, 0, tmp, 0)

        val windows = windowManager.getVisibleWindows()
        for (window in windows) {
            renderWindow(window)
        }
        renderWindowUI(windows)
    }

    override fun onFinishFrame(viewport: Viewport) {
        // Cardboard SDK 处理畸变校正
    }

    override fun onRendererShutdown() {
        GLES20.glDeleteProgram(windowProgram)
        GLES20.glDeleteProgram(borderProgram)
        GLES20.glDeleteProgram(uiProgram)
        windowProgram = 0; borderProgram = 0; uiProgram = 0
    }

    private fun renderWindow(window: WindowManager.WindowInfo) {
        val isFocused = (window.id == windowManager.focusedWindowId)
        val isSelected = (window.id == selectedWindowId)
        val isHovered = (window.id == hoveredWindowId)

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

        GLES20.glUseProgram(windowProgram)
        GLES20.glUniformMatrix4fv(wMvpHandle, 1, false, finalMvp, 0)
        GLES20.glUniformMatrix4fv(wStMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniform1i(wIsTextureHandle, 1)
        GLES20.glUniform4f(wColorHandle, 1f, 1f, 1f, 1f)
        GLES20.glUniform1f(wBrightnessHandle, settings.brightness)
        GLES20.glUniform1f(wContrastHandle, settings.contrast)
        GLES20.glUniform1f(wSaturationHandle, settings.saturation)
        GLES20.glUniform1f(wVignetteHandle, settings.vignette)
        GLES20.glUniform1f(wChromaticAberrationHandle, settings.chromaticAberration)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, displayTextureId)
        GLES20.glUniform1i(wTexHandle, 0)

        GLES20.glEnableVertexAttribArray(wPositionHandle)
        GLES20.glVertexAttribPointer(wPositionHandle, 3, GLES20.GL_FLOAT, false, 12, quadVertexBuffer)
        GLES20.glEnableVertexAttribArray(wTexCoordHandle)
        GLES20.glVertexAttribPointer(wTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, quadTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(wPositionHandle)
        GLES20.glDisableVertexAttribArray(wTexCoordHandle)

        // Border
        val borderColor = if (isFocused) floatArrayOf(0.2f, 0.6f, 1f, 1f)
        else if (isHovered) floatArrayOf(1f, 0.8f, 0f, 1f)
        else floatArrayOf(0.4f, 0.4f, 0.4f, 1f)
        GLES20.glUseProgram(borderProgram)
        GLES20.glUniformMatrix4fv(bMvpHandle, 1, false, finalMvp, 0)
        GLES20.glUniform4fv(bColorHandle, 1, borderColor, 0)
        GLES20.glEnableVertexAttribArray(bPositionHandle)
        GLES20.glVertexAttribPointer(bPositionHandle, 3, GLES20.GL_FLOAT, false, 12, borderVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bPositionHandle)

        // Title bar + buttons
        if (isFocused || isSelected || isHovered) {
            drawTitleBar(window, windowMatrix, isFocused)
            drawControlButtons(window, windowMatrix)
        }
    }

    private fun drawTitleBar(window: WindowManager.WindowInfo, windowMatrix: FloatArray, isFocused: Boolean) {
        // Matrix.multiplyMM 要求 result 与输入不是同一数组；这里等价于复制 windowMatrix。
        val titleMatrix = windowMatrix.copyOf()
        Matrix.translateM(titleMatrix, 0, 0f, 1.05f, 0f)
        Matrix.scaleM(titleMatrix, 0, 1f, 0.08f, 1f)

        val finalMvp = FloatArray(16)
        Matrix.multiplyMM(finalMvp, 0, mvpMatrix, 0, titleMatrix, 0)

        GLES20.glUseProgram(uiProgram)
        GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, finalMvp, 0)
        val titleColor = if (isFocused) floatArrayOf(0.2f, 0.4f, 0.8f, 0.9f)
        else floatArrayOf(0.3f, 0.3f, 0.3f, 0.9f)
        GLES20.glUniform4fv(uColorHandle, 1, titleColor, 0)
        // UI shader 没有 uTexture uniform，不能再调 glUniform1i(uTexHandle, ...)。

        GLES20.glEnableVertexAttribArray(uPositionHandle)
        GLES20.glVertexAttribPointer(uPositionHandle, 3, GLES20.GL_FLOAT, false, 12, uiQuadBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(uPositionHandle)
    }

    private fun drawControlButtons(window: WindowManager.WindowInfo, windowMatrix: FloatArray) {
        val buttonColors = arrayOf(
            floatArrayOf(1f, 0.8f, 0f, 1f),   // 黄色
            floatArrayOf(0f, 0.8f, 0f, 1f),   // 绿色
            floatArrayOf(1f, 0.2f, 0.2f, 1f)  // 红色
        )

        for (i in 0..2) {
            // 同上：直接复制，避免 multiplyMM 的 result/input 别名。
            val buttonMatrix = windowMatrix.copyOf()
            val buttonX = 0.9f - i * 0.1f
            Matrix.translateM(buttonMatrix, 0, buttonX, 1.05f, 0f)
            Matrix.scaleM(buttonMatrix, 0, 0.07f, 0.07f, 1f)

            val finalMvp = FloatArray(16)
            Matrix.multiplyMM(finalMvp, 0, mvpMatrix, 0, buttonMatrix, 0)

            GLES20.glUseProgram(uiProgram)
            GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, finalMvp, 0)
            GLES20.glUniform4fv(uColorHandle, 1, buttonColors[i], 0)

            GLES20.glEnableVertexAttribArray(uPositionHandle)
            GLES20.glVertexAttribPointer(uPositionHandle, 3, GLES20.GL_FLOAT, false, 12, uiQuadBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(uPositionHandle)
        }
    }

    private fun renderWindowUI(windows: List<WindowManager.WindowInfo>) {
        // UI 已在 renderWindow 内绘制
    }

    private fun applyQuaternionRotation(matrix: FloatArray, q: FloatArray) {
        // Kotlin 不支持 Java 那种 "val a = ..., b = ..." 一行多声明，必须逐个写。
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
        matrix[12] = 0f; matrix[13] = 0f; matrix[14] = 0f; matrix[15] = 1f
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vertexSource)
            GLES20.glCompileShader(it)
        }
        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fragmentSource)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    fun updateHover(windowId: Long) {
        hoveredWindowId = windowId
    }

    fun setSelected(windowId: Long) {
        selectedWindowId = windowId
        if (windowId != -1) windowManager.focusWindow(windowId)
    }

    fun startDrag(windowId: Long, startX: Float, startY: Float) {
        val window = windowManager.getWindow(windowId)
        if (window != null) {
            isDragging = true
            dragStartX = startX
            dragStartY = startY
            dragWindowStartBounds = Rect(window.bounds)
        }
    }

    fun updateDrag(currentX: Float, currentY: Float) {
        // nullable var 属性无法 smart cast，必须取局部 val
        val startBounds = dragWindowStartBounds ?: return
        if (!isDragging || selectedWindowId == -1L) return
        val dx = (currentX - dragStartX) * settings.screenWidth * 1000
        val dy = (dragStartY - currentY) * settings.screenHeight * 1000
        val newLeft = (startBounds.left + dx).toInt()
        val newTop = (startBounds.top - dy).toInt()
        windowManager.moveWindow(selectedWindowId, newLeft, newTop)
    }

    fun endDrag() {
        isDragging = false
        dragWindowStartBounds = null
    }

    fun startResize(windowId: Long, startX: Float, startY: Float) {
        val window = windowManager.getWindow(windowId)
        if (window != null) {
            isResizing = true
            dragStartX = startX
            dragStartY = startY
            dragWindowStartBounds = Rect(window.bounds)
        }
    }

    fun updateResize(currentX: Float, currentY: Float) {
        val startBounds = dragWindowStartBounds ?: return
        if (!isResizing || selectedWindowId == -1L) return
        val dx = (currentX - dragStartX) * settings.screenWidth * 1000
        val dy = (dragStartY - currentY) * settings.screenHeight * 1000
        val newWidth = (startBounds.width() + dx).toInt()
        val newHeight = (startBounds.height() - dy).toInt()
        windowManager.resizeWindow(selectedWindowId, newWidth, newHeight, 0f, 1f)
    }

    fun endResize() {
        isResizing = false
        dragWindowStartBounds = null
    }
}
