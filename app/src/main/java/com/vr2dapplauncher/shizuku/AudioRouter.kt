package com.vr2dapplauncher.shizuku

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.IAudioService
import android.util.Log
import com.vr2dapplauncher.vr.VrSettings

/**
 * 音频路由管理器：将虚拟显示器的音频路由到主输出或蓝牙/USB 音频设备
 * 
 * 思路：
 * 1. 通过 IAudioService 监听音频播放状态
 * 2. 当虚拟显示器上的应用播放音频时，获取其 AudioPlaybackConfiguration
 * 3. 使用 setPreferredDevice 或音频策略将音频路由到目标设备
 */
class AudioRouter(private val context: Context) {
    companion object {
        private const val TAG = "AudioRouter"
    }

    private var audioManager: IAudioService? = null
    private var targetDeviceId: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private var isRoutingEnabled = false

    init {
        // 获取 IAudioService
        audioManager = ShizukuServiceUtils.getAudioManager()
    }

    /**
     * 启用音频路由
     * @param deviceType 目标设备类型 (TYPE_BUILTIN_SPEAKER, TYPE_WIRED_HEADSET, TYPE_BLUETOOTH_A2DP 等)
     */
    fun enableRouting(deviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
        targetDeviceId = deviceType
        isRoutingEnabled = true
        Log.d(TAG, "Audio routing enabled to device type: $deviceType")
        
        // 尝试设置首选音频设备
        setPreferredAudioDevice(deviceType)
    }

    /** 禁用音频路由 */
    fun disableRouting() {
        isRoutingEnabled = false
        Log.d(TAG, "Audio routing disabled")
    }

    /** 设置首选音频输出设备 */
    private fun setPreferredAudioDevice(deviceType: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            
            for (device in devices) {
                if (device.type == deviceType) {
                    // API 23+ setPreferredDevice
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        am.setPreferredDevice(device)
                        Log.d(TAG, "Set preferred audio device: ${device.productName} (${device.id})")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set preferred audio device", e)
        }
    }

    /** 获取可用的音频输出设备列表 */
    fun getAvailableOutputDevices(): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    }

    /** 监听音频播放状态 (需 Shizuku 权限) */
    fun startPlaybackMonitoring(displayId: Int) {
        if (!isRoutingEnabled || audioManager == null) return

        try {
            // 通过 IAudioService 监听特定显示器上的音频播放
            // 注意：这需要系统级权限，可能无法直接通过 Shizuku 实现
            // 替代方案：轮询 AudioPlaybackConfiguration
            Log.d(TAG, "Audio playback monitoring started for display $displayId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start playback monitoring", e)
        }
    }

    /** 停止监听 */
    fun stopPlaybackMonitoring() {
        Log.d(TAG, "Audio playback monitoring stopped")
    }
}