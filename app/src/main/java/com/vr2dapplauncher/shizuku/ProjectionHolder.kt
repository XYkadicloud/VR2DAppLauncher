package com.vr2dapplauncher.shizuku

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vr2dapplauncher.util.Diagnostics

/**
 * 屏幕捕获「授权凭证池」。
 *
 * ★ 为什么不再在这里直接 new 一个全局 MediaProjection：
 *   Android 14 起 getMediaProjection() 要求调用进程**已经**有一个
 *   FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 类型的前台服务在运行；而
 *   startForegroundService() 是异步的。旧版在 onActivityResult 里同步调
 *   getMediaProjection()，FGS 还没起来 → SecurityException → 直接闪退。
 *
 *   现在授权结果回来只把 (resultCode, data) 存进池子；真正调用
 *   getMediaProjection() 推迟到 VrVirtualDisplayHost 的 GL 线程建屏时，
 *   并且由 ProjectionService.postWhenForeground() 保证那时 FGS 已前台运行。
 *
 * ★ Android 14 另一条硬规则（官方文档「用户意见征求」）：
 *   「会话是指对 createVirtualDisplay() 的单次调用，MediaProjection 令牌必须仅
 *   使用一次」；把同一个 Intent 传给 getMediaProjection() 超过一次，或在同一
 *   MediaProjection 实例上多次 createVirtualDisplay()，都会抛 SecurityException。
 *   所以「多开独立虚拟屏」= 一屏一个凭证：用户每授权一次，池子里多一个凭证，
 *   建屏时每块屏消费一个凭证、只 createVirtualDisplay() 一次。
 */
object ProjectionHolder {

    private const val TAG = "ProjectionHolder"

    data class Consent(val resultCode: Int, val data: Intent)

    private val consents = ArrayList<Consent>()
    private val projections = ArrayList<MediaProjection>()

    @Volatile
    var lastError: String? = null
        private set

    /** 授权结果回来时调用；不做任何 Binder / getMediaProjection 操作。 */
    fun addConsent(resultCode: Int, data: Intent) {
        synchronized(consents) {
            consents.add(Consent(resultCode, data))
        }
        lastError = null
        Diagnostics.record(TAG, "consent added; pool size=${consentCount()}")
    }

    fun consentCount(): Int = synchronized(consents) { consents.size }

    fun hasConsent(): Boolean = consentCount() > 0

    /** 兼容旧调用点：有凭证，或已有创建好的 projection。 */
    fun has(): Boolean = hasConsent() || synchronized(projections) { projections.isNotEmpty() }

    /**
     * 消费一个授权凭证，返回一个「只允许 createVirtualDisplay 一次」的 MediaProjection。
     * 必须由 GL 线程（VrVirtualDisplayHost.ensureDisplaysOnGlThread）调用，
     * 且调用前 ProjectionService 已经前台运行。
     */
    fun takeProjection(context: Context): MediaProjection? {
        val consent = synchronized(consents) {
            if (consents.isEmpty()) null else consents.removeAt(0)
        }
        if (consent == null) {
            lastError = "屏幕捕获授权凭证已用完（每块虚拟屏需要单独授权一次）"
            Diagnostics.record(TAG, lastError ?: "no consent")
            return null
        }
        return try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            val maybe: MediaProjection? = manager.getMediaProjection(consent.resultCode, consent.data)
            if (maybe == null) {
                lastError = "getMediaProjection 返回 null（凭证可能已被系统作废）"
                Diagnostics.record(TAG, lastError ?: "null projection")
                return null
            }
            val proj: MediaProjection = maybe

            // 官方要求：createVirtualDisplay() 之前必须 registerCallback()，
            // 否则抛 IllegalStateException。
            try {
                proj.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection.onStop (session ended)")
                            Diagnostics.record(
                                TAG,
                                "MediaProjection.onStop (session ended / superseded / revoked)"
                            )
                            synchronized(projections) { projections.remove(proj) }
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                Log.w(TAG, "registerCallback failed (continuing)", e)
                Diagnostics.record(TAG, "registerCallback failed: ${e.javaClass.simpleName}")
            }

            synchronized(projections) { projections.add(proj) }
            Diagnostics.record(
                TAG,
                "consent consumed -> MediaProjection ready; remaining=${consentCount()}"
            )
            proj
        } catch (e: Exception) {
            val msg = "getMediaProjection threw ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, msg, e)
            Diagnostics.record(TAG, msg)
            lastError = msg
            null
        }
    }

    fun clear() {
        synchronized(consents) { consents.clear() }
        val list = synchronized(projections) {
            val copy = projections.toList()
            projections.clear()
            copy
        }
        for (p in list) {
            try {
                p.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stop projection failed", e)
            }
        }
        Diagnostics.record(TAG, "consents + projections cleared")
    }
}
