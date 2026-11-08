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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.vr2dapplauncher.ui.MainActivity
import com.vr2dapplauncher.util.Diagnostics

/**
 * 前台服务：让 MediaProjection 在 Android 10+ / Android 14+ 上合法持续运行。
 *
 * ★ 修「已经授权了，一进 VR 就闪退」的真正根因（真机日志）：
 *   [MainActivity] getMediaProjection threw SecurityException:
 *       Media projections require a foreground service of type
 *       ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 *
 *   Context.startForegroundService() 是**异步**的：Service 的 onCreate()/startForeground()
 *   排在主线程消息队列里，而旧版 onActivityResult 里同步就调用了 getMediaProjection()，
 *   此刻 FGS 还没前台运行 → Android 14 直接抛 SecurityException；异常后 return，
 *   ProjectionService 一次都没启动（日志里完全没有 [ProjectionService] 记录）。
 *
 *   修法有两层：
 *   1. MainActivity 授权结果回来后不再调用 getMediaProjection()，只把凭证存进
 *      ProjectionHolder；真正的 getMediaProjection() 推迟到 VR 的 GL 线程建屏时。
 *   2. 用 postWhenForeground() 把「刷新 UI / 进入 VR」排到 FGS 真正 startForeground()
 *      成功之后，保证 GL 线程建屏时进程里已有 mediaProjection 类型的 FGS。
 *
 *   postWhenForeground() 同时处理两种时序：
 *   - Service 首次创建：onCreate -> startForeground -> notifyForeground() -> flush 队列
 *   - Service 已运行：ready=true，任务立即执行
 *   再挂一个 4 秒超时兜底，startForeground 若因权限/系统限制失败也不会把用户卡死。
 */
class ProjectionService : Service() {

    companion object {
        private const val TAG = "ProjectionService"
        private const val CHANNEL_ID = "vr2d_projection"
        private const val NOTIFICATION_ID = 1001
        private const val READY_TIMEOUT_MS = 4000L

        private val handler = Handler(Looper.getMainLooper())
        private val pending = ArrayList<() -> Unit>()

        @Volatile
        private var ready = false

        @Volatile
        private var lastForegroundError: String? = null

        /** 最后一次 startForeground 的错误（供诊断导出），null 表示成功。 */
        fun foregroundError(): String? = lastForegroundError

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ProjectionService::class.java))
                Diagnostics.record(TAG, "startForegroundService requested")
            } catch (e: Exception) {
                val msg = "startForegroundService failed: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, msg, e)
                Diagnostics.record(TAG, msg)
                lastForegroundError = msg
                // 起不来就别让调用方无限等；4 秒超时兜底会放行并给出诊断。
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ProjectionService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stopService failed", e)
            }
            synchronized(pending) { pending.clear() }
            ready = false
            lastForegroundError = null
        }

        /**
         * 把任务排到「FGS 已真正前台运行」之后执行。绝不在调用线程同步执行，
         * 因为 startForegroundService() 是异步的。
         */
        fun postWhenForeground(block: () -> Unit) {
            handler.post {
                var runNow = false
                synchronized(pending) {
                    if (ready) runNow = true else pending.add(block)
                }
                if (runNow) {
                    try {
                        block()
                    } catch (e: Exception) {
                        Diagnostics.record(
                            TAG,
                            "postWhenForeground task threw ${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }
            // 兜底：FGS 迟迟没就绪（startForeground 失败 / 系统限制）也要放行，
            // 让后续真实异常写进诊断，而不是静默卡死。
            handler.postDelayed({
                val waiting = synchronized(pending) { pending.isNotEmpty() }
                if (waiting) {
                    Diagnostics.record(
                        TAG,
                        "FGS ready timeout (${READY_TIMEOUT_MS}ms), err=$lastForegroundError; flushing pending task(s)"
                    )
                    notifyForeground()
                }
            }, READY_TIMEOUT_MS)
        }

        /** 由 Service 在 startForeground() 成功后（或失败兜底时）调用。 */
        fun notifyForeground() {
            handler.post {
                val tasks = synchronized(pending) {
                    ready = true
                    val copy = pending.toList()
                    pending.clear()
                    copy
                }
                for (task in tasks) {
                    try {
                        task()
                    } catch (e: Exception) {
                        Diagnostics.record(
                            TAG,
                            "deferred task threw ${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            val notification = buildNotification()
            // 用 >= Q(29) 而不是 >= U(34)：部分厂商 ROM 的 SDK_INT 不等于 34
            // 却同样要求 typed FGS，>= Q 能全部覆盖；低版本才退回 2 参数。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            lastForegroundError = null
            Diagnostics.record(TAG, "startForeground ok (typed mediaProjection)")
            notifyForeground()
        } catch (e: Exception) {
            val msg = "startForeground failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, msg, e)
            Diagnostics.record(TAG, msg)
            lastForegroundError = msg
            // 即使失败也放行，让后续真实异常进诊断，不让 UI 卡死。
            notifyForeground()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service 已存在时 startForegroundService 只回调这里，不会再走 onCreate。
        // 补一次 flush：若此时 pending 里还有任务（竞态窗口）立即放行。
        notifyForeground()
        return START_STICKY
    }

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
