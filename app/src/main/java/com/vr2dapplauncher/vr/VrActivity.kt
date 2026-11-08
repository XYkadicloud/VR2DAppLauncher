package com.vr2dapplauncher.vr

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.cardboard.sdk.CardboardView
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost

/**
 * VR 模式 Activity：全屏 CardboardView，渲染虚拟显示器内容
 * 
 * 控制方式：
 * - 头部追踪：Cardboard SDK 自动处理
 * - 手柄/键盘：传递给虚拟显示器（已通过 InputRouter 绑定）
 * - 返回键/菜单键：退出 VR 模式
 */
class VrActivity : AppCompatActivity() {

    private const val TAG = "VrActivity"

    private lateinit var cardboardView: CardboardView
    private lateinit var virtualDisplayHost: VrVirtualDisplayHost
    private lateinit var renderer: VrRenderer

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

        renderer = VrRenderer(this, virtualDisplayHost)
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

    // 处理返回键退出 VR
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // 触摸事件也传递给 CardboardView
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return cardboardView.onTouchEvent(event) || super.onTouchEvent(event)
    }

    companion object {
        fun createIntent(context: android.content.Context, host: VrVirtualDisplayHost): Intent {
            return Intent(context, VrActivity::class.java).apply {
                putExtra("virtual_display_host", host)
            }
        }
    }
}