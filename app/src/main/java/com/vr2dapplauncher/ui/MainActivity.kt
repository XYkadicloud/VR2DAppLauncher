package com.vr2dapplauncher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vr2dapplauncher.R
import com.vr2dapplauncher.shizuku.ShizukuUtils
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost
import com.vr2dapplauncher.vr.VrActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主 Activity：2D 启动器界面
 *
 * 全量审查修正（技术验证阶段的关键闭环）：
 *  - 创建虚拟显示器前先走 MediaProjection 用户授权，把拿到的 MediaProjection
 *    传给 VrVirtualDisplayHost；否则 VirtualDisplayManager 直接返回 null，
 *    "创建虚拟显示器"永远失败；
 *  - Shizuku 改为可选增强（用于 am start --display / 输入路由），
 *    不再作为创建虚拟显示器的硬前置条件。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1002
    }

    // UI 组件
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvVdStatus: TextView
    private lateinit var btnChrome: Button
    private lateinit var btnYouTube: Button
    private lateinit var btnBrowser: Button
    private lateinit var btnCustom: Button
    private lateinit var btnCreateVd: Button
    private lateinit var btnDestroyVd: Button
    private lateinit var btnEnterVr: Button
    private lateinit var btnVrSettings: Button

    // 虚拟显示器宿主 / 屏幕捕获授权
    private var virtualDisplayHost: VrVirtualDisplayHost? = null
    private var mediaProjection: MediaProjection? = null
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initViews()
        setupClickListeners()
        checkShizukuPermission()
    }

    private fun initViews() {
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status)
        tvVdStatus = findViewById(R.id.tv_vd_status)
        btnChrome = findViewById(R.id.btn_chrome)
        btnYouTube = findViewById(R.id.btn_youtube)
        btnBrowser = findViewById(R.id.btn_browser)
        btnCustom = findViewById(R.id.btn_custom)
        btnCreateVd = findViewById(R.id.btn_create_vd)
        btnDestroyVd = findViewById(R.id.btn_destroy_vd)
        btnEnterVr = findViewById(R.id.btn_enter_vr)
        btnVrSettings = findViewById(R.id.btn_vr_settings)

        setAppButtonsEnabled(false)
        btnDestroyVd.isEnabled = false
        btnEnterVr.isEnabled = false
        btnCreateVd.isEnabled = true
    }

    private fun setupClickListeners() {
        btnCreateVd.setOnClickListener { createVirtualDisplay() }
        btnDestroyVd.setOnClickListener { destroyVirtualDisplay() }
        btnChrome.setOnClickListener { launchApp("com.android.chrome") }
        btnYouTube.setOnClickListener { launchApp("com.google.android.youtube") }
        btnBrowser.setOnClickListener { launchApp("com.android.browser") }
        btnCustom.setOnClickListener { showCustomPackageDialog() }
        btnEnterVr.setOnClickListener { enterVrMode() }
        btnVrSettings.setOnClickListener { openVrSettings() }
    }

    /** 检查 Shizuku 权限（增强项，不阻塞虚拟显示器） */
    private fun checkShizukuPermission() {
        CoroutineScope(Dispatchers.IO).launch {
            val hasPermission = ShizukuUtils.hasPermission()
            runOnUiThread { updateShizukuStatus(hasPermission) }
        }
    }

    private fun updateShizukuStatus(hasPermission: Boolean) {
        if (hasPermission) {
            tvShizukuStatus.text = "✅ Shizuku 权限已授予（增强：指定 display 启动 / 输入路由）"
            tvShizukuStatus.setTextColor(0xFF88CC88.toInt())
        } else {
            tvShizukuStatus.text = "ℹ️ Shizuku 未授权（可选，虚拟显示器仍可用）\n如需增强能力，请安装 Shizuku 并授权"
            tvShizukuStatus.setTextColor(0xFFAAAAAA.toInt())
        }
        // MediaProjection 不依赖 Shizuku，创建按钮始终可用
        btnCreateVd.isEnabled = true
    }

    /** 创建虚拟显示器：先请求屏幕捕获授权 */
    private fun createVirtualDisplay() {
        if (virtualDisplayHost?.isRunning == true) {
            Toast.makeText(this, "虚拟显示器已在运行", Toast.LENGTH_SHORT).show()
            return
        }

        tvVdStatus.text = "正在请求屏幕捕获授权..."
        tvVdStatus.setTextColor(0xFFFFAA00.toInt())
        btnCreateVd.isEnabled = false

        @Suppress("DEPRECATION")
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            btnCreateVd.isEnabled = true
            tvVdStatus.text = "虚拟显示器: 未创建（屏幕捕获授权被取消）"
            tvVdStatus.setTextColor(0xFFFF6666.toInt())
            Toast.makeText(this, "已取消屏幕捕获授权", Toast.LENGTH_SHORT).show()
            return
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            btnCreateVd.isEnabled = true
            tvVdStatus.text = "❌ 无法获取 MediaProjection"
            tvVdStatus.setTextColor(0xFFFF6666.toInt())
            return
        }
        reallyCreateVirtualDisplay()
    }

    private fun reallyCreateVirtualDisplay() {
        tvVdStatus.text = "正在创建虚拟显示器..."
        tvVdStatus.setTextColor(0xFFFFAA00.toInt())

        virtualDisplayHost = VrVirtualDisplayHost(this, mediaProjection)
        virtualDisplayHost?.start { success ->
            runOnUiThread {
                if (success) {
                    tvVdStatus.text = "✅ 虚拟显示器已创建 (ID: ${virtualDisplayHost?.displayId})"
                    tvVdStatus.setTextColor(0xFF88CC88.toInt())
                    setAppButtonsEnabled(true)
                    btnDestroyVd.isEnabled = true
                    btnEnterVr.isEnabled = true
                    btnCreateVd.isEnabled = false
                    Toast.makeText(this, "虚拟显示器创建成功", Toast.LENGTH_SHORT).show()
                } else {
                    tvVdStatus.text = "❌ 虚拟显示器创建失败（检查日志）"
                    tvVdStatus.setTextColor(0xFFFF6666.toInt())
                    btnCreateVd.isEnabled = true
                    virtualDisplayHost = null
                    Toast.makeText(this, "创建失败，请检查日志", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 销毁虚拟显示器 */
    private fun destroyVirtualDisplay() {
        virtualDisplayHost?.stop()
        virtualDisplayHost = null
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop MediaProjection failed", e)
        }
        mediaProjection = null
        tvVdStatus.text = "虚拟显示器: 未创建"
        tvVdStatus.setTextColor(0xFFAAAAAA.toInt())
        setAppButtonsEnabled(false)
        btnDestroyVd.isEnabled = false
        btnEnterVr.isEnabled = false
        btnCreateVd.isEnabled = true
        Toast.makeText(this, "虚拟显示器已销毁", Toast.LENGTH_SHORT).show()
    }

    /** 在虚拟显示器上启动应用 */
    private fun launchApp(packageName: String) {
        virtualDisplayHost?.launchApp(packageName)
        Toast.makeText(this, "正在启动 $packageName...", Toast.LENGTH_SHORT).show()
    }

    /** 显示自定义包名对话框 */
    private fun showCustomPackageDialog() {
        val input = EditText(this).apply {
            hint = "输入包名 (如: com.example.app)"
        }
        AlertDialog.Builder(this)
            .setTitle("启动自定义应用")
            .setView(input)
            .setPositiveButton("启动") { _, _ ->
                val packageName = input.text.toString().trim()
                if (packageName.isNotEmpty()) launchApp(packageName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 进入 VR 模式 */
    private fun enterVrMode() {
        val host = virtualDisplayHost ?: return
        if (!host.isRunning) {
            Toast.makeText(this, "虚拟显示器未运行", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(VrActivity.createIntent(this, host.displayId))
    }

    private fun setAppButtonsEnabled(enabled: Boolean) {
        btnChrome.isEnabled = enabled
        btnYouTube.isEnabled = enabled
        btnBrowser.isEnabled = enabled
        btnCustom.isEnabled = enabled
    }

    /** 打开 VR 设置 */
    private fun openVrSettings() {
        startActivity(VrSettingsActivity.createIntent(this))
    }

    override fun onResume() {
        super.onResume()
        checkShizukuPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplayHost?.stop()
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop MediaProjection failed", e)
        }
    }
}
