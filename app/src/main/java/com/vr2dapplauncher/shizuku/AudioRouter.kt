package com.vr2dapplauncher.shizuku

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * 音频路由（技术验证阶段）。
 *
 * 只使用公开 AudioManager API 枚举输出设备，不再 import 隐藏的 IAudioService /
 * 调用隐藏的 AudioManager.setPreferredDevice()。
 * 屏连/屏易连那种按虚拟显示器隔离音频的能力，后续可用
 * ShizukuServiceUtils.getAudioBinder() + AIDL transact 补齐。
 */
class AudioRouter(private val context: Context) {
    companion object {
        private const val TAG = "AudioRouter"
    }

    private var targetDeviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private var isRoutingEnabled = false

    fun enableRouting(deviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
        targetDeviceType = deviceType
        isRoutingEnabled = true
        Log.d(TAG, "Audio routing enabled to device type $deviceType")
    }

    fun disableRouting() {
        isRoutingEnabled = false
        Log.d(TAG, "Audio routing disabled")
    }

    fun getAvailableOutputDevices(): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }

    fun startPlaybackMonitoring(displayId: Int) {
        Log.d(
            TAG,
            "Playback monitoring (stub) for display $displayId; " +
                "routing=$isRoutingEnabled, target=$targetDeviceType"
        )
    }

    fun stopPlaybackMonitoring() {
        Log.d(TAG, "Audio playback monitoring stopped")
    }
}
