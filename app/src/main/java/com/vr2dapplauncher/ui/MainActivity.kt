package com.vr2dapplauncher.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vr2dapplauncher.R
import com.vr2dapplauncher.shizuku.LaunchPlan
import com.vr2dapplauncher.shizuku.ProjectionHolder
import com.vr2dapplauncher.shizuku.ProjectionService
import com.vr2dapplauncher.shizuku.ShizukuUtils
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost
import com.vr2dapplauncher.util.Diagnostics
import com.vr2dapplauncher.vr.VrActivity
import java.io.File

/**
 * 2D 管理界面（保留，但不再是进入 VR 的必经步骤）。
 *
 * ★ 本版修掉真机日志里的「已授权仍闪退」：
 *   [MainActivity] getMediaProjection threw SecurityException:
 *       Media projections require a foreground service of type
 *       ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 *   根因：换取 MediaProjection 实例 在 ProjectionService.start() **之前**同步调用，
 *   而 startForegroundService() 是异步的，FGS 还没前台运行；异常后直接 return，
 *   前台服务一次都没启动（日志里完全没有 [ProjectionService] 记录）。
 *   修法：授权结果回来只把凭证存进 ProjectionHolder，绝不在这里 换取 MediaProjection 实例；
 *   用 ProjectionService.postWhenForeground() 把「刷新 / 进 VR」排到 FGS 真正
 *   startForeground() 成功之后；真正 换取 MediaProjection 实例 推迟到 VR 的 GL 线程建屏时。
 *
 * ★ Android 14 硬规则：一次用户同意 = 一次 createVirtualDisplay()，
 *   同一个 Intent 不能重复 换取 MediaProjection 实例。所以「多开独立虚拟屏」=
 *   一屏一个凭证：每点一次授权，池子里多一个凭证，进 VR 时逐块消费。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1002
        private const val PREFS = "main_prefs"
        private const val KEY_AUTO_VR = "auto_enter_vr"
    }

    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvVdStatus: TextView
    private lateinit var tvPlan: TextView
    private lateinit var btnAuthorize: Button
    private lateinit var btnAppList: Button
    private lateinit var btnEnterVr: Button
    private lateinit var btnVrSettings: Button
    private lateinit var btnExportDiag: Button
    private lateinit var switchAutoVr: Switch

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingEnterVr = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        tvShizukuStatus = findViewById(R.id.tv_shizuku_status)
        tvVdStatus = findViewById(R.id.tv_vd_status)
        tvPlan = findViewById(R.id.tv_plan)
        btnAuthorize = findViewById(R.id.btn_authorize)
        btnAppList = findViewById(R.id.btn_app_list)
        btnEnterVr = findViewById(R.id.btn_enter_vr)
        btnVrSettings = findViewById(R.id.btn_vr_settings)
        btnExportDiag = findViewById(R.id.btn_export_diag)
        switchAutoVr = findViewById(R.id.switch_auto_vr)

        switchAutoVr.isChecked = isAutoVr()
        switchAutoVr.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_VR, checked).apply()
            Toast.makeText(
                this,
                if (checked) "已开启：下次打开应用直接进入 VR" else "已关闭启动即 VR",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 点击 = 再加一个授权凭证（Android 14 每块虚拟屏一次同意）；
        // 长按 = 释放全部凭证并停止前台服务。
        btnAuthorize.setOnClickListener { requestProjection() }
        btnAuthorize.setOnLongClickListener {
            releaseProjection()
            true
        }
        btnAppList.setOnClickListener { startActivity(Intent(this, AppListActivity::class.java)) }
        btnEnterVr.setOnClickListener { enterVrMode() }
        btnVrSettings.setOnClickListener { startActivity(VrSettingsActivity.createIntent(this)) }
        btnExportDiag.setOnClickListener { exportDiagnostics() }

        refreshUi()

        // 启动即 VR：有凭证就直接进；没有凭证先自动请求授权，回调里再进。
        if (isAutoVr()) {
            pendingEnterVr = true
            if (ProjectionHolder.hasConsent()) enterVrMode() else requestProjection()
        }
    }

    private fun refreshUi() {
        val consents = ProjectionHolder.consentCount()
        val selected = LaunchPlan.load(this).size
        tvVdStatus.text = if (consents > 0) {
            "✅ 屏幕捕获凭证：$consents 个（可建 $consents 块独立虚拟屏）\n" +
                "已选 $selected 个应用；Android 14 每块屏需单独授权一次"
        } else {
            "屏幕捕获: 未授权（点下方按钮授权，无需 root）\n" +
                "Android 14：每次进入 VR 需重新授权一次"
        }
        tvVdStatus.setTextColor(if (consents > 0) 0xFF88CC88.toInt() else 0xFFFFAA00.toInt())
        btnAuthorize.text = if (ProjectionHolder.has()) {
            "再授权一次（+1 块虚拟屏）· 长按释放"
        } else {
            "1. 授权屏幕捕获（每次 = 1 块虚拟屏）"
        }
        btnEnterVr.isEnabled = true

        val apps = LaunchPlan.load(this)
        tvPlan.text = if (apps.isEmpty()) {
            "已选应用：0/${LaunchPlan.MAX_APPS}\n还没选应用，VR 里会显示一个占位窗口"
        } else {
            "已选应用：${apps.size}/${LaunchPlan.MAX_APPS}（每个应用一块独立虚拟屏）\n" +
                apps.joinToString("\n") { "· ${it.label}  (${it.packageName})" }
        }

        val shizuku = ShizukuUtils.hasPermission()
        tvShizukuStatus.text = if (shizuku) {
            "✅ Shizuku 已授权\n第三方应用可真正启动到各自独立虚拟屏"
        } else {
            "ℹ️ Shizuku 未授权（可选）\n没有它也能建屏 / 进 VR，但第三方应用内容无法可靠放入独立虚拟屏"
        }
        tvShizukuStatus.setTextColor(if (shizuku) 0xFF88CC88.toInt() else 0xFFAAAAAA.toInt())
    }

    @Suppress("DEPRECATION")
    private fun requestProjection() {
        try {
            Diagnostics.record(TAG, "requesting MediaProjection consent")
            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            val msg = "createScreenCaptureIntent failed: ${e.javaClass.simpleName}: ${e.message}"
            Diagnostics.record(TAG, msg)
            pendingEnterVr = false
            Toast.makeText(this, "无法请求屏幕捕获授权：$msg", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            Diagnostics.record(TAG, "MediaProjection consent cancelled/refused")
            pendingEnterVr = false
            Toast.makeText(this, "已取消屏幕捕获授权", Toast.LENGTH_SHORT).show()
            refreshUi()
            return
        }

        // ★ 只存凭证，不碰 换取 MediaProjection 实例。这是闪退根因的修复点：
        //   startForegroundService() 异步，此刻 FGS 还没前台，Android 14 会直接抛
        //   SecurityException，异常后 return 又会让 FGS 永远起不来。
        ProjectionHolder.addConsent(resultCode, data)
        ProjectionService.start(this)

        // FGS 真正 startForeground() 成功后才刷新 UI / 进 VR，
        // 保证 GL 线程建屏时已有 mediaProjection 类型的前台服务。
        ProjectionService.postWhenForeground {
            if (isFinishing || isDestroyed) return@postWhenForeground
            val fgsError = ProjectionService.foregroundError()
            Diagnostics.record(
                TAG,
                "consent stored + FGS ready; pool=${ProjectionHolder.consentCount()}, fgsError=$fgsError"
            )
            refreshUi()
            if (fgsError == null) {
                Toast.makeText(this, "授权成功，可以进入 VR 了", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "前台服务异常，请点「导出诊断」查看原因", Toast.LENGTH_LONG).show()
            }
            if (pendingEnterVr || isAutoVr()) enterVrMode()
        }
    }

    private fun releaseProjection() {
        VrVirtualDisplayHost.shared?.stop()
        ProjectionHolder.clear()
        ProjectionService.stop(this)
        Diagnostics.record(TAG, "all consents released by user")
        refreshUi()
        Toast.makeText(this, "已释放全部屏幕捕获授权", Toast.LENGTH_SHORT).show()
    }

    /** 一键进 VR：没有凭证就先自动走授权，授权回调里再进来。 */
    private fun enterVrMode() {
        if (!ProjectionHolder.hasConsent()) {
            pendingEnterVr = true
            Toast.makeText(this, "需要屏幕捕获授权（每块虚拟屏一次）", Toast.LENGTH_SHORT).show()
            requestProjection()
            return
        }
        pendingEnterVr = false
        try {
            Diagnostics.record(TAG, "starting VrActivity")
            startActivity(VrActivity.createIntent(this))
        } catch (e: Exception) {
            val msg = "startActivity(VrActivity) failed: ${e.javaClass.simpleName}: ${e.message}"
            Diagnostics.record(TAG, msg)
            Toast.makeText(this, "进入 VR 失败，请点「导出诊断」查看原因", Toast.LENGTH_LONG).show()
        }
    }

    /** 不连 adb logcat 也能把闪退原因交出来：弹窗显示 + 复制到剪贴板 + 写文件。 */
    private fun exportDiagnostics() {
        val path = Diagnostics.export(this)
        val content = if (path != null) {
            try {
                File(path).readText()
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
        val shown = if (content.length > 6000) content.takeLast(6000) else content
        AlertDialog.Builder(this)
            .setTitle("运行诊断日志")
            .setMessage(if (shown.isBlank()) "（暂无记录）" else shown)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制全部") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("vr2d diagnostics", content))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .show()
        Toast.makeText(
            this,
            if (path != null) "已导出到: $path" else "导出失败（见对话框）",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun isAutoVr(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_VR, false)

    override fun onResume() {
        super.onResume()
        refreshUi()
    }
}
