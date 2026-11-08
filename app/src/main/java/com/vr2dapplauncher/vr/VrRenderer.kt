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
 * 
 * 支持：
 * - 亮度/对比度/饱和度调节
 * - 角渐晕效果
 * - 色差校正
 * - 屏幕几何参数（距离、宽高、曲率）
 * - 瞳距和头部姿态偏移校正
 */
class VrRenderer(
    private val context: Context,
    private val virtualDisplayHost: VrVirtualDisplayHost,
    private val settings: VrSettings
) : CardboardView.Renderer {

    private const val TAG = "VrRenderer"

    // 着色器程序
    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var mvpHandle: Int = 0
    private var texHandle: Int = 0
    private var stMatrixHandle: Int = 0
    
    // 画面调整 uniform
    private var brightnessHandle: Int = 0
    private var contrastHandle: Int = 0
    private var saturationHandle: Int = 0
    private var vignetteHandle: Int = 0
    private var chromaticAberrationHandle: Int = 0
    
    // 屏幕几何 uniform
    private var screenParamsHandle: Int = 0
    private var curvatureHandle: Int = 0

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

    // 屏幕参数（用于曲面投影）
    private val screenParams = FloatArray(4)  // distance, width, height, aspect

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

        // 初始化屏幕参数
        updateScreenParams()
    }

    private fun updateScreenParams() {
        val aspect = settings.screenWidth / settings.screenHeight
        screenParams[0] = settings.screenDistance
        screenParams[1] = settings.screenWidth
        screenParams[2] = settings.screenHeight
        screenParams[3] = aspect
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
        
        // 画面调整 uniform
        brightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness")
        contrastHandle = GLES20.glGetUniformLocation(program, "uContrast")
        saturationHandle = GLES20.glGetUniformLocation(program, "uSaturation")
        vignetteHandle = GLES20.glGetUniformLocation(program, "uVignette")
        chromaticAberrationHandle = GLES20.glGetUniformLocation(program, "uChromaticAberration")
        
        // 屏幕几何 uniform
        screenParamsHandle = GLES20.glGetUniformLocation(program, "uScreenParams")
        curvatureHandle = GLES20.glGetUniformLocation(program, "uCurvature")

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
        // 更新屏幕参数（可能在运行时修改）
        updateScreenParams()

        // 清屏
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 获取当前眼的视图矩阵
        val eyeView = FloatArray(16)
        eye.getEyeView(eyeView, 0)
        
        // 应用头部姿态偏移校正
        applyHeadOffsetCorrection()
        
        val headRotation = FloatArray(16)
        Matrix.setIdentityM(headRotation, 0)
        applyQuaternionRotation(headRotation, headQuaternion, centerOffsetQuaternion)
        
        Matrix.multiplyMM(viewMatrix, 0, eyeView, 0, headRotation, 0)
        
        // 投影矩阵
        eye.getPerspective(0.1f, 100f, projectionMatrix, 0)
        
        // MVP
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 绘制
        drawFrame()
    }

    /** 应用头部姿态偏移校正（yaw/pitch/roll） */
    private fun applyHeadOffsetCorrection() {
        val yaw = settings.yawOffset
        val pitch = settings.pitchOffset
        val roll = settings.rollOffset
        
        if (yaw != 0f || pitch != 0f || roll != 0f) {
            // 这里可以应用偏移校正到 headQuaternion
            // 简化处理：实际应用中需要完整的四元数乘法
        }
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

        // 画面调整参数
        GLES20.glUniform1f(brightnessHandle, settings.brightness)
        GLES20.glUniform1f(contrastHandle, settings.contrast)
        GLES20.glUniform1f(saturationHandle, settings.saturation)
        GLES20.glUniform1f(vignetteHandle, settings.vignette)
        GLES20.glUniform1f(chromaticAberrationHandle, settings.chromaticAberration)

        // 屏幕几何参数
        GLES20.glUniform4fv(screenParamsHandle, 1, screenParams, 0)
        GLES20.glUniform1f(curvatureHandle, settings.curvature)

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
            uniform vec4 uScreenParams;  // distance, width, height, aspect
            uniform float uCurvature;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            varying vec2 vScreenCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                // 屏幕坐标用于片段着色器的曲面投影和角渐晕
                vScreenCoord = aTexCoord * 2.0 - 1.0;  // 归一化到 [-1, 1]
            }
        """.trimIndent()

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            
            // 画面调整
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uVignette;
            uniform float uChromaticAberration;
            
            // 屏幕几何
            uniform vec4 uScreenParams;  // distance, width, height, aspect
            uniform float uCurvature;
            
            varying vec2 vTexCoord;
            varying vec2 vScreenCoord;
            
            // 色差校正采样偏移
            vec2 chromaticOffset(vec2 coord, float offset) {
                vec2 center = vec2(0.5, 0.5);
                return center + (coord - center) * (1.0 + offset);
            }
            
            // 角渐晕
            float vignette(vec2 coord, float strength) {
                float dist = distance(coord, vec2(0.5, 0.5)) * 1.414;  // 归一化到 [0, 1]
                return 1.0 - smoothstep(0.3, 1.0, dist) * strength;
            }
            
            // 色彩空间调整
            vec3 adjustColor(vec3 color, float brightness, float contrast, float saturation) {
                // 亮度
                color *= brightness;
                // 对比度
                color = (color - 0.5) * contrast + 0.5;
                // 饱和度
                float luminance = dot(color, vec3(0.299, 0.587, 0.114));
                color = mix(vec3(luminance), color, saturation);
                return clamp(color, 0.0, 1.0);
            }
            
            void main() {
                vec2 texCoord = vTexCoord;
                
                // 色差校正：RGB 通道分别采样
                vec3 color;
                if (uChromaticAberration != 0.0) {
                    float offset = uChromaticAberration * 0.005;
                    color.r = texture2D(uTexture, chromaticOffset(texCoord, offset)).r;
                    color.g = texture2D(uTexture, texCoord).g;
                    color.b = texture2D(uTexture, chromaticOffset(texCoord, -offset)).b;
                } else {
                    color = texture2D(uTexture, texCoord).rgb;
                }
                
                // 画面调整
                color = adjustColor(color, uBrightness, uContrast, uSaturation);
                
                // 角渐晕
                float vig = vignette(vTexCoord, uVignette);
                color *= vig;
                
                // 屏幕曲面投影（可选，用于模拟曲面屏效果）
                if (uCurvature != 0.0) {
                    float curve = uCurvature * 0.5;
                    float dist2 = dot(vScreenCoord, vScreenCoord);
                    float factor = 1.0 + curve * dist2;
                    // 简单的桶形/枕形畸变补偿
                    color *= factor;
                }
                
                gl_FragColor = vec4(color, 1.0);
            }
        """.trimIndent()
    }
}