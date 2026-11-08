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
 * ★ 本版关键修正（修掉「已授权仍闪退」）：
 *   - 虚拟屏不再在 2D 界面创建（那里没有 EGL context，SurfaceTexture(0) 必然失败）；
 *     授权只把 MediaProjection 交给 ProjectionHolder，真正的建屏推迟到
 *     VrActivity 的 GL 线程 onSurfaceCreated()。
 *   - 启动 ProjectionService 前台服务，MediaProjection 才能持续存活。
 *   - getMediaProjection / 授权弹窗 / 启动 VR 全部 try/catch + Diagnostics 记录。
 *   - 提供「一键进入 VR」「应用列表」「导出诊断」「启动即 VR」。
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

        btnAuthorize.setOnClickListener {
            if (ProjectionHolder.has()) releaseProjection() else requestProjection()
        }
        btnAppList.setOnClickListener { startActivity(Intent(this, AppListActivity::class.java)) }
        btnEnterVr.setOnClickListener { enterVrMode() }
        btnVrSettings.setOnClickListener { startActivity(VrSettingsActivity.createIntent(this)) }
        btnExportDiag.setOnClickListener { exportDiagnostics() }

        refreshUi()

        // 启动即 VR：已授权就直接进；未授权先自动请求授权，回调里再进。
        if (isAutoVr()) {
            pendingEnterVr = true
            if (ProjectionHolder.has()) enterVrMode() else requestProjection()
        }
    }

    private fun refreshUi() {
        val hasProjection = ProjectionHolder.has()
        tvVdStatus.text = if (hasProjection) {
            "✅ 屏幕捕获已授权\n虚拟屏将在进入 VR 时按应用列表逐块创建"
        } else {
            "屏幕捕获: 未授权（点下方按钮授权，无需 root）"
        }
        tvVdStatus.setTextColor(if (hasProjection) 0xFF88CC88.toInt() else 0xFFFFAA00.toInt())
        btnAuthorize.text = if (hasProjection) "释放屏幕捕获授权" else "1. 授权屏幕捕获（创建虚拟屏）"
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

        val projection = try {
            mediaProjectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Diagnostics.record(TAG, "getMediaProjection threw ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (projection == null) {
            pendingEnterVr = false
            Toast.makeText(this, "无法获取 MediaProjection", Toast.LENGTH_LONG).show()
            refreshUi()
            return
        }

        // ★ 全局单例持有 + 统一注册 Callback（Android 14 要求 createVirtualDisplay 前注册）
        ProjectionHolder.set(projection)
        ProjectionService.start(this)
        Diagnostics.record(TAG, "MediaProjection granted + FGS started")
        refreshUi()
        Toast.makeText(this, "授权成功，可以进入 VR 了", Toast.LENGTH_SHORT).show()

        if (pendingEnterVr || isAutoVr()) enterVrMode()
    }

    private fun releaseProjection() {
        VrVirtualDisplayHost.shared?.stop()
        ProjectionHolder.clear()
        ProjectionService.stop(this)
        Diagnostics.record(TAG, "projection released by user")
        refreshUi()
        Toast.makeText(this, "已释放屏幕捕获授权", Toast.LENGTH_SHORT).show()
    }

    /** 一键进 VR：没授权就先自动走授权，授权回调里再进来。 */
    private fun enterVrMode() {
        if (!ProjectionHolder.has()) {
            pendingEnterVr = true
            Toast.makeText(this, "需要先授权屏幕捕获", Toast.LENGTH_SHORT).show()
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
