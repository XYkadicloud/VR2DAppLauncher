package com.vr2dapplauncher.vr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * VR 设置管理器：管理屏幕参数、畸变校正、舒适度设置等
 * 参考 OpenNOW-VR 的设置设计
 */
class VrSettings(private val context: Context) {

    companion object {
        private const val TAG = "VrSettings"
        private const val PREFS_NAME = "vr_settings"
        
        // 默认值
        private const val DEFAULT_SCREEN_DISTANCE = 0.05f      // 屏幕距离 (米)
        private const val DEFAULT_SCREEN_WIDTH = 0.24f         // 屏幕宽度 (米)
        private const val DEFAULT_SCREEN_HEIGHT = 0.135f       // 屏幕高度 (米)
        private const val DEFAULT_CURVATURE = 0.0f             // 屏幕曲率
        private const val DEFAULT_BRIGHTNESS = 1.0f            // 亮度倍数
        private const val DEFAULT_CONTRAST = 1.0f              // 对比度倍数
        private const val DEFAULT_SATURATION = 1.0f            // 饱和度倍数
        private const val DEFAULT_VIGNETTE = 0.3f              // 角渐晕强度
        private const val DEFAULT_CHROMATIC_ABERRATION = 0.0f  // 色差校正
        private const val DEFAULT_IPD = 0.064f                 // 瞳距 (米)
        private const val DEFAULT_YAW_OFFSET = 0.0f            // 偏航角偏移
        private const val DEFAULT_PITCH_OFFSET = 0.0f          // 俯仰角偏移
        private const val DEFAULT_ROLL_OFFSET = 0.0f           // 翻滚角偏移
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 屏幕几何参数
    var screenDistance: Float
        get() = prefs.getFloat("screen_distance", DEFAULT_SCREEN_DISTANCE)
        set(value) = prefs.edit().putFloat("screen_distance", value).apply()

    var screenWidth: Float
        get() = prefs.getFloat("screen_width", DEFAULT_SCREEN_WIDTH)
        set(value) = prefs.edit().putFloat("screen_width", value).apply()

    var screenHeight: Float
        get() = prefs.getFloat("screen_height", DEFAULT_SCREEN_HEIGHT)
        set(value) = prefs.edit().putFloat("screen_height", value).apply()

    var curvature: Float
        get() = prefs.getFloat("curvature", DEFAULT_CURVATURE)
        set(value) = prefs.edit().putFloat("curvature", value).apply()

    // 画面调整参数
    var brightness: Float
        get() = prefs.getFloat("brightness", DEFAULT_BRIGHTNESS)
        set(value) = prefs.edit().putFloat("brightness", value.coerceIn(0.1f, 2.0f)).apply()

    var contrast: Float
        get() = prefs.getFloat("contrast", DEFAULT_CONTRAST)
        set(value) = prefs.edit().putFloat("contrast", value.coerceIn(0.1f, 2.0f)).apply()

    var saturation: Float
        get() = prefs.getFloat("saturation", DEFAULT_SATURATION)
        set(value) = prefs.edit().putFloat("saturation", value.coerceIn(0.1f, 2.0f)).apply()

    var vignette: Float
        get() = prefs.getFloat("vignette", DEFAULT_VIGNETTE)
        set(value) = prefs.edit().putFloat("vignette", value.coerceIn(0.0f, 1.0f)).apply()

    var chromaticAberration: Float
        get() = prefs.getFloat("chromatic_aberration", DEFAULT_CHROMATIC_ABERRATION)
        set(value) = prefs.edit().putFloat("chromatic_aberration", value.coerceIn(-1.0f, 1.0f)).apply()

    // 头部姿态校正
    var ipd: Float
        get() = prefs.getFloat("ipd", DEFAULT_IPD)
        set(value) = prefs.edit().putFloat("ipd", value.coerceIn(0.05f, 0.08f)).apply()

    var yawOffset: Float
        get() = prefs.getFloat("yaw_offset", DEFAULT_YAW_OFFSET)
        set(value) = prefs.edit().putFloat("yaw_offset", value).apply()

    var pitchOffset: Float
        get() = prefs.getFloat("pitch_offset", DEFAULT_PITCH_OFFSET)
        set(value) = prefs.edit().putFloat("pitch_offset", value).apply()

    var rollOffset: Float
        get() = prefs.getFloat("roll_offset", DEFAULT_ROLL_OFFSET)
        set(value) = prefs.edit().putFloat("roll_offset", value).apply()

    // 虚拟显示器配置
    var virtualDisplayWidth: Int
        get() = prefs.getInt("vd_width", 1920)
        set(value) = prefs.edit().putInt("vd_width", value.coerceIn(720, 3840)).apply()

    var virtualDisplayHeight: Int
        get() = prefs.getInt("vd_height", 1080)
        set(value) = prefs.edit().putInt("vd_height", value.coerceIn(480, 2160)).apply()

    var virtualDisplayDpi: Int
        get() = prefs.getInt("vd_dpi", 320)
        set(value) = prefs.edit().putInt("vd_dpi", value.coerceIn(120, 640)).apply()

    var virtualDisplayRefreshRate: Int
        get() = prefs.getInt("vd_refresh_rate", 60)
        set(value) = prefs.edit().putInt("vd_refresh_rate", value.coerceIn(30, 120)).apply()

    /**
     * 重置为默认值
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Log.d(TAG, "VR settings reset to defaults")
    }

    /**
     * 导出设置为字符串（用于调试/分享）
     */
    fun exportSettings(): String {
        return prefs.all.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    /**
     * 从字符串导入设置
     */
    fun importSettings(data: String) {
        val editor = prefs.edit()
        data.lines().forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0]
                val value = parts[1]
                when {
                    value.toFloatOrNull() != null -> editor.putFloat(key, value.toFloat())
                    value.toIntOrNull() != null -> editor.putInt(key, value.toInt())
                    // Kotlin stdlib 没有 String.toBooleanOrNull()；直接判断字面量最稳。
                    value.equals("true", ignoreCase = true) ||
                        value.equals("false", ignoreCase = true) ->
                        editor.putBoolean(key, value.toBoolean())
                    else -> editor.putString(key, value)
                }
            }
        }
        editor.apply()
        Log.d(TAG, "VR settings imported")
    }
}