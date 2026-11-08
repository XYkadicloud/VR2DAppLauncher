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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * VR 渲染器：将虚拟显示器的 SurfaceTexture 作为 GL_TEXTURE_EXTERNAL_OES
 * 渲染到 CardboardView 的左右眼
 */
class VrRenderer(private val context: Context, private val virtualDisplayHost: VrVirtualDisplayHost) : CardboardView.Renderer {

    private const val TAG = "VrRenderer"

    // 着色器程序
    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var mvpHandle: Int = 0
    private var texHandle: Int = 0
    private var stMatrixHandle: Int = 0

    // 顶点数据：全屏四边形
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // MVP 矩阵
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // ST 矩阵 (SurfaceTexture 变换)
    private val stMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }

    // 头部姿态
    private val headQuaternion = FloatArray(4).also { it[3] = 1f }
    private val centerOffsetQuaternion = FloatArray(4).also { it[3] = 1f }

    // 表面尺寸
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        // 全屏四边形顶点 (-1,-1) 到 (1,1)
        val vertices = floatArrayOf(
            -1f, -1f, 0f,
             1f, -1f, 0f,
            -1f,  1f, 0f,
             1f,  1f, 0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(vertices); position(0) }

        // 纹理坐标 (翻转 Y，因为 SurfaceTexture 原点在左上角)
        val texCoords = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(texCoords); position(0) }
    }

    override fun onSurfaceCreated(config: EGLConfig) {
        Log.d(TAG, "onSurfaceCreated")
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        // 编译着色器
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMVP")
        texHandle = GLES20.glGetUniformLocation(program, "uTexture")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

        Log.d(TAG, "Shader program created: $program")

        // 通知虚拟显示器宿主 GL 线程就绪，创建纹理
        val texId = virtualDisplayHost.createGlTextureOnGlThread()
        virtualDisplayHost.onGlThreadReady?.invoke(texId)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        Log.d(TAG, "onSurfaceChanged: ${width}x$height")
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        headTransform.getQuaternion(headQuaternion, 0)
        
        // 更新 SurfaceTexture 获取最新帧
        val surfaceTexture = virtualDisplayHost.getSurfaceTexture()
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(stMatrix)
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // 清屏
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 获取当前眼的视图矩阵
        val eyeView = FloatArray(16)
        eye.getEyeView(eyeView, 0)
        
        // 应用头部姿态
        val headRotation = FloatArray(16)
        Matrix.setIdentityM(headRotation, 0)
        // 简化：直接使用四元数旋转（实际应用中需要完整的姿态矩阵）
        applyQuaternionRotation(headRotation, headQuaternion, centerOffsetQuaternion)
        
        Matrix.multiplyMM(viewMatrix, 0, eyeView, 0, headRotation, 0)
        
        // 投影矩阵
        eye.getPerspective(0.1f, 100f, projectionMatrix, 0)
        
        // MVP
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 绘制
        drawFrame()
    }

    override fun onFinishFrame(viewport: Viewport) {
        // Cardboard SDK 处理畸变校正
    }

    override fun onRendererShutdown() {
        Log.d(TAG, "onRendererShutdown")
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        virtualDisplayHost.stop()
    }

    private fun drawFrame() {
        val textureId = virtualDisplayHost.glTextureId
        if (textureId == 0 || !virtualDisplayHost.isRunning) return

        GLES20.glUseProgram(program)

        // MVP
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        // ST 矩阵
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(texHandle, 0)

        // 顶点属性
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        // 绘制三角形条带
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun applyQuaternionRotation(matrix: FloatArray, q: FloatArray, centerOffset: FloatArray) {
        // 简化的四元数转旋转矩阵
        val x = q[0], y = q[1], z = q[2], w = q[3]
        val xx = x * x, yy = y * y, zz = z * z
        val xy = x * y, xz = x * z, yz = y * z
        val wx = w * x, wy = w * y, wz = w * z

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

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            return 0
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMVP;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
    }
}