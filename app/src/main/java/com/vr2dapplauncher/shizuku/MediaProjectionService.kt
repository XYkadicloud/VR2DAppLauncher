package com.vr2dapplauncher.shizuku

import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log

/**
 * MediaProjection 服务：作为回退路径使用
 * 当 Shizuku 不可用时，通过 MediaProjection 创建虚拟显示器
 */
class MediaProjectionService : Service() {

    private const val TAG = "MediaProjectionService"
    private const val REQUEST_MEDIA_PROJECTION = 1001

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var pendingResult: ((MediaProjection?) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 请求 MediaProjection 权限并返回 MediaProjection
     * 必须在 Activity 上下文中调用
     */
    fun requestMediaProjection(activity: Activity, result: (MediaProjection?) -> Unit) {
        pendingResult = result
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null) {
            activity.startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
        } else {
            result(null)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            val projection = if (resultCode == Activity.RESULT_OK) {
                mediaProjectionManager?.getMediaProjection(resultCode, data!!)
            } else {
                null
            }
            pendingResult?.invoke(projection)
            pendingResult = null
        }
    }
}