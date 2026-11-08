package com.vr2dapplauncher.shizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vr2dapplauncher.ui.MainActivity
import com.vr2dapplauncher.util.Diagnostics

/**
 * 前台服务：让 MediaProjection 在 Android 10+ 上合法持续运行。
 *
 * ★ 修「已经授权了，一点创建虚拟屏 / 进 VR 就闪退」：
 *   Manifest 已声明 foregroundServiceType="mediaProjection"。Android 14 起只要
 *   声明了 type，2 参数 startForeground() 就可能抛
 *   MissingForegroundServiceTypeException；FGS 没真正起来，系统会在数秒内杀掉
 *   进程，表现就是授权完立刻闪退。所以 API 29+ 统一走 3 参数重载并显式传 type，
 *   低版本再用 2 参数。onCreate 里的 try/catch 只记录不吞掉崩溃，但这里必须
 *   让 startForeground 本身成功，不能靠 catch 兜底。
 */
class ProjectionService : Service() {

    companion object {
        private const val TAG = "ProjectionService"
        private const val CHANNEL_ID = "vr2d_projection"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ProjectionService::class.java))
                Diagnostics.record(TAG, "startForegroundService")
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                Diagnostics.record(TAG, "startForegroundService failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ProjectionService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stopService failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Diagnostics.record(TAG, "startForeground ok")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            Diagnostics.record(TAG, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ProjectionHolder.clear()
        Diagnostics.record(TAG, "service destroyed")
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VR 虚拟屏幕",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VR 2D 应用启动器")
            .setContentText("虚拟屏幕服务运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
