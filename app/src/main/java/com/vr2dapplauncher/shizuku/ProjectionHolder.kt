package com.vr2dapplauncher.shizuku

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vr2dapplauncher.util.Diagnostics

/**
 * 全局持有用户授权的 MediaProjection。
 *
 * MediaProjection 不是 Parcelable，不能塞进 Intent；用 Application 级单例在
 * MainActivity（授权）与 VrActivity（GL 线程创建虚拟屏）之间共享。
 *
 * Android 14 起 framework 要求 createVirtualDisplay() 之前先 registerCallback()，
 * 否则抛 IllegalStateException("Must register a callback before starting projection")。
 * 这里统一注册，且所有调用都包 try/catch，避免旧版本行为差异导致崩溃。
 */
object ProjectionHolder {

    private const val TAG = "ProjectionHolder"

    @Volatile
    private var projection: MediaProjection? = null

    fun set(value: MediaProjection) {
        projection = value
        try {
            value.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection stopped by system")
                        Diagnostics.record(TAG, "MediaProjection.onStop (system revoked)")
                        projection = null
                    }
                },
                Handler(Looper.getMainLooper())
            )
            Diagnostics.record(TAG, "MediaProjection set + callback registered")
        } catch (e: Exception) {
            // 某些旧 ROM 上 registerCallback 可能抛异常；记录但不阻塞功能。
            Log.w(TAG, "registerCallback failed (continuing)", e)
            Diagnostics.record(TAG, "registerCallback failed: ${e.javaClass.simpleName}")
        }
    }

    fun get(): MediaProjection? = projection
    fun has(): Boolean = projection != null

    fun clear() {
        try {
            projection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop MediaProjection failed", e)
        }
        projection = null
        Diagnostics.record(TAG, "MediaProjection cleared")
    }
}
