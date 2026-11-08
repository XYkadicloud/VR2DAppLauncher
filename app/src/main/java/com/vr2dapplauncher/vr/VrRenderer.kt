package com.vr2dapplauncher.vr

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost
import javax.microedition.khronos.egl.EGLConfig

/**
 * 单窗口/全屏 VR 渲染器（旧版兼容保留，技术验证阶段不再作为主路径）。
 *
 * 全量审查修正：
 *  - shader 常量改用 `val`：`const val` 的初始化器必须是编译期常量，
 *    调用 trimIndent() 会直接编译失败；
 *  - fragment shader 首行加 #extension GL_OES_EGL_image_external : require
 *    （samplerExternalOES 必需，且 #extension 必须是第一条指令）；
 *  - vertex shader 补 uMVP：否则 glGetUniformLocation 返回 -1；
 *  - onDrawEye 不再 glClear()：Cardboard SDK 只清一次共享 FBO，
 *    在每只眼里 clear 会在画右眼时把左眼擦掉（原项目真机验证结论）。
 */
class VrRenderer(
    private val context: Context,
    private val virtualDisplayHost: VrVirtualDisplayHost,
    private val settings: VrSettings
) : CardboardView.Renderer {

    companion object {
        private const val TAG = "VrRenderer"

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        // #extension MUST be the first directive, with no leading newline.
        private val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                vec2 texCoord = (uSTMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(uTexture, texCoord);
            }
        """.trimIndent()
    }

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var texHandle: Int = 0
    private var stMatrixHandle: Int = 0
    private var mvpHandle: Int = 0

    private val quadVertexBuffer = createQuadVertexBuffer()
    private val quadTexCoordBuffer = createQuadTexCoordBuffer()

    private val stMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }

    private fun createQuadVertexBuffer(): java.nio.FloatBuffer {
        val vertices = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
        return java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(vertices); position(0) }
    }

    private fun createQuadTexCoordBuffer(): java.nio.FloatBuffer {
        val coords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        return java.nio.ByteBuffer.allocateDirect(coords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(coords); position(0) }
    }

    override fun onSurfaceCreated(config: EGLConfig) {
        Log.d(TAG, "onSurfaceCreated")
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texHandle = GLES20.glGetUniformLocation(program, "uTexture")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMVP")

        val texId = virtualDisplayHost.createGlTextureOnGlThread()
        virtualDisplayHost.onGlThreadReady?.invoke(texId)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: ${width}x$height")
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        val surfaceTexture = virtualDisplayHost.getSurfaceTexture()
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(stMatrix)
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // 不要 glClear()：SDK 的共享 FBO 只清一次，左右眼靠 glViewport 区分。
        // 黑色背景由 onSurfaceCreated 的 glClearColor + SDK 自己的 clear 提供。
        val eyeView = eye.getEyeView()
        val projection = eye.getPerspective(0.1f, 100f)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, virtualDisplayHost.glTextureId)
        GLES20.glUniform1i(texHandle, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projection, 0, eyeView, 0)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, quadVertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, quadTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    override fun onFinishFrame(viewport: Viewport) {
        // Cardboard SDK 处理畸变校正
    }

    override fun onRendererShutdown() {
        GLES20.glDeleteProgram(program)
        program = 0
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
}
