package com.vr2dapplauncher.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vr2dapplauncher.R
import com.vr2dapplauncher.shizuku.AppLauncher
import com.vr2dapplauncher.shizuku.InputRouter
import com.vr2dapplauncher.shizuku.ShizukuUtils
import com.vr2dapplauncher.shizuku.VirtualDisplayManager
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost
import com.vr2dapplauncher.vr.VrActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主 Activity：2D 启动器界面
 * 功能：
 * 1. Shizuku 权限检查与请求
 * 2. 虚拟显示器创建/销毁
 * 3. 在虚拟显示器上启动应用
 * 4. 进入 VR 模式
 */
class MainActivity : AppCompatActivity() {

    private const val TAG = "MainActivity"

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

    // 虚拟显示器宿主
    private var virtualDisplayHost: VrVirtualDisplayHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        // 初始状态：虚拟显示器未创建时禁用相关按钮
        setAppButtonsEnabled(false)
        btnDestroyVd.isEnabled = false
        btnEnterVr.isEnabled = false
    }

    private fun setupClickListeners() {
        // 创建虚拟显示器
        btnCreateVd.setOnClickListener { createVirtualDisplay() }

        // 销毁虚拟显示器
        btnDestroyVd.setOnClickListener { destroyVirtualDisplay() }

        // 启动 Chrome
        btnChrome.setOnClickListener { launchApp("com.android.chrome") }

        // 启动 YouTube
        btnYouTube.setOnClickListener { launchApp("com.google.android.youtube") }

        // 启动浏览器
        btnBrowser.setOnClickListener { launchApp("com.android.browser") }

        // 自定义包名
        btnCustom.setOnClickListener { showCustomPackageDialog() }

        // 进入 VR 模式
        btnEnterVr.setOnClickListener { enterVrMode() }

        // VR 设置
        btnVrSettings.setOnClickListener { openVrSettings() }
    }

    /** 检查 Shizuku 权限 */
    private fun checkShizukuPermission() {
        CoroutineScope(Dispatchers.IO).launch {
            val hasPermission = ShizukuUtils.hasPermission()
            runOnUiThread {
                updateShizukuStatus(hasPermission)
                if (!hasPermission) {
                    ShizukuUtils.requestPermission(this@MainActivity)
                }
            }
        }
    }

    private fun updateShizukuStatus(hasPermission: Boolean) {
        if (hasPermission) {
            tvShizukuStatus.text = "✅ Shizuku 权限已授予"
            tvShizukuStatus.setTextColor(0xFF88CC88.toInt())
            btnCreateVd.isEnabled = true
        } else {
            tvShizukuStatus.text = "❌ Shizuku 权限未授予\n请安装 Shizuku Manager 并通过 ADB 激活"
            tvShizukuStatus.setTextColor(0xFFFF6666.toInt())
            btnCreateVd.isEnabled = false
        }
    }

    /** 创建虚拟显示器 */
    private fun createVirtualDisplay() {
        if (!ShizukuUtils.hasPermission()) {
            Toast.makeText(this, "请先授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            ShizukuUtils.requestPermission(this)
            return
        }

        btnCreateVd.isEnabled = false
        tvVdStatus.text = "正在创建虚拟显示器..."
        tvVdStatus.setTextColor(0xFFFFAA00.toInt())

        virtualDisplayHost = VrVirtualDisplayHost(this)
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
                    tvVdStatus.text = "❌ 虚拟显示器创建失败"
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
                if (packageName.isNotEmpty()) {
                    launchApp(packageName)
                }
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

        val intent = VrActivity.createIntent(this, host)
        startActivity(intent)
    }

    private fun setAppButtonsEnabled(enabled: Boolean) {
        btnChrome.isEnabled = enabled
        btnYouTube.isEnabled = enabled
        btnBrowser.isEnabled = enabled
        btnCustom.isEnabled = enabled
    }

    /** 打开 VR 设置 */
    private fun openVrSettings() {
        val intent = VrSettingsActivity.createIntent(this)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // 重新检查权限状态
        checkShizukuPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplayHost?.stop()
    }
}