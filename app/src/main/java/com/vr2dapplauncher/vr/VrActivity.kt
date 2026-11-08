package com.vr2dapplauncher.vr

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.cardboard.sdk.CardboardView
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost
import com.vr2dapplauncher.ui.VrSettingsActivity

/**
 * VR 模式 Activity：全屏 CardboardView，渲染虚拟显示器内容
 * 
 * 控制方式：
 * - 头部追踪：Cardboard SDK 自动处理
 * - 手柄/键盘：传递给虚拟显示器（已通过 InputRouter 绑定）
 * - 返回键/菜单键：退出 VR 模式
 * - 长按音量键：显示 VR 设置菜单
 */
class VrActivity : AppCompatActivity() {

    private const val TAG = "VrActivity"
    private const val LONG_PRESS_TIMEOUT = 800L  // 长按判定时间 (ms)

    private lateinit var cardboardView: CardboardView
    private lateinit var virtualDisplayHost: VrVirtualDisplayHost
    private lateinit var renderer: VrRenderer
    private lateinit var settings: VrSettings

    // 音量键长按检测
    private var volumeKeyDownTime = 0L
    private var isVolumeKeyPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vr)

        cardboardView = findViewById(R.id.cardboard_view)

        // 从 Intent 获取虚拟显示器宿主
        virtualDisplayHost = intent.getParcelableExtra("virtual_display_host") 
            ?: VrVirtualDisplayHost(this).also { 
                // 如果没有传入，创建新的（不推荐）
                Log.w(TAG, "No virtual display host passed, creating new one")
            }

        // 初始化 VR 设置
        settings = VrSettings(this)

        renderer = VrRenderer(this, virtualDisplayHost, settings)
        cardboardView.setRenderer(renderer)
        cardboardView.setDistortionCorrectionEnabled(true)
        cardboardView.setAsyncReprojectionEnabled(true)

        Log.d(TAG, "VR Activity created, starting virtual display")
    }

    override fun onResume() {
        super.onResume()
        cardboardView.onResume()
        
        // 启动虚拟显示器
        virtualDisplayHost.start { success ->
            if (success) {
                Log.d(TAG, "Virtual display started successfully")
            } else {
                Log.e(TAG, "Failed to start virtual display")
                runOnUiThread { finish() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cardboardView.onPause()
        virtualDisplayHost.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplayHost.stop()
    }

    // 处理按键事件
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 记录按下时间，用于长按检测
                if (!isVolumeKeyPressed) {
                    isVolumeKeyPressed = true
                    volumeKeyDownTime = System.currentTimeMillis()
                }
                return true  // 消费事件，不传给系统音量控制
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (isVolumeKeyPressed) {
                    val pressDuration = System.currentTimeMillis() - volumeKeyDownTime
                    isVolumeKeyPressed = false
                    
                    if (pressDuration >= LONG_PRESS_TIMEOUT) {
                        // 长按音量键：打开 VR 设置
                        openVrSettings()
                        return true
                    }
                    // 短按：传给系统音量控制（这里不处理，让系统处理）
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // 触摸事件也传递给 CardboardView
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return cardboardView.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /** 打开 VR 设置界面 */
    private fun openVrSettings() {
        Log.d(TAG, "Opening VR settings from VR mode")
        val intent = VrSettingsActivity.createIntent(this)
        startActivity(intent)
    }

    companion object {
        fun createIntent(context: android.content.Context, host: VrVirtualDisplayHost): Intent {
            return Intent(context, VrActivity::class.java).apply {
                putExtra("virtual_display_host", host)
            }
        }
    }
}