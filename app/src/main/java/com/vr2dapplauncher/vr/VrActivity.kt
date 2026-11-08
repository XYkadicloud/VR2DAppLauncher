package com.vr2dapplauncher.vr

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.cardboard.sdk.CardboardView
import com.vr2dapplauncher.R
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost
import com.vr2dapplauncher.ui.VrSettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * VR 模式 Activity：全屏 CardboardView，渲染虚拟显示器上的多窗口
 *
 * 全量审查修正：
 *  - 补 import com.vr2dapplauncher.R（原来 R.layout / R.id 无法解析）；
 *  - 生命周期不再 stop / 重新 start 虚拟显示器：host 由 MainActivity 创建并持有，
 *    VrActivity 只负责 GL surface 的 pause/resume，返回 2D 后虚拟显示器仍在运行；
 *  - showWindowMenu 去掉对非空 Long 的冗余 ?.let。
 */
class VrActivity : AppCompatActivity() {

    private val TAG = "VrActivity"
    private val LONG_PRESS_TIMEOUT = 800L
    private val DOUBLE_CLICK_TIMEOUT = 300L

    private lateinit var cardboardView: CardboardView
    private lateinit var virtualDisplayHost: VrVirtualDisplayHost
    private lateinit var windowRenderer: VrWindowRenderer
    private lateinit var settings: VrSettings
    private lateinit var windowManager: WindowManager

    // 音量键长按检测
    private var volumeKeyDownTime = 0L
    private var isVolumeKeyPressed = false

    // 触控板交互状态
    private var lastClickTime = 0L
    private var clickCount = 0
    private var isTouchpadPressed = false
    private var touchpadPressTime = 0L
    private var touchpadStartX = 0f
    private var touchpadStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vr)

        cardboardView = findViewById(R.id.cardboard_view)

        // 从单例获取虚拟显示器宿主（不再通过 Intent 传递，它不是 Parcelable）
        virtualDisplayHost = VrVirtualDisplayHost.shared ?: run {
            Log.w(TAG, "No shared virtual display host, creating fallback")
            VrVirtualDisplayHost(this)
        }

        settings = VrSettings(this)

        val displayId = intent.getIntExtra("display_id", virtualDisplayHost.displayId)
        windowManager = WindowManager(this, displayId)
        windowManager.setDisplaySize(
            virtualDisplayHost.getDisplayArgs().width,
            virtualDisplayHost.getDisplayArgs().height
        )

        windowRenderer = VrWindowRenderer(this, virtualDisplayHost, settings, windowManager)
        cardboardView.setRenderer(windowRenderer)
        // v1.36.0 无 setDistortionCorrectionEnabled / setAsyncReprojectionEnabled

        observeWindowEvents()

        Log.d(TAG, "VR Activity created, displayId=$displayId")
    }

    private fun observeWindowEvents() {
        CoroutineScope(Dispatchers.IO).launch {
            for (event in windowManager.windowEvents) {
                when (event) {
                    is WindowManager.WindowEvent.WindowAdded ->
                        Log.d(TAG, "Window added: ${event.window.title} (${event.window.packageName})")
                    is WindowManager.WindowEvent.WindowRemoved ->
                        Log.d(TAG, "Window removed: ${event.windowId}")
                    is WindowManager.WindowEvent.WindowFocusChanged ->
                        Log.d(TAG, "Focus changed to window: ${event.windowId}")
                    is WindowManager.WindowEvent.WindowClosed ->
                        Log.d(TAG, "Window closed: ${event.windowId}")
                    else -> Unit
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 虚拟显示器由 MainActivity 创建并持续运行；这里只恢复 GL 渲染。
        cardboardView.onResume()
    }

    override fun onPause() {
        super.onPause()
        cardboardView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不要在这里 stop()：host 归 MainActivity 所有，返回 2D 界面后仍要可用。
    }

    // 按键事件
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!isVolumeKeyPressed) {
                    isVolumeKeyPressed = true
                    volumeKeyDownTime = System.currentTimeMillis()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                handleSelectAction()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleDpadNavigation(keyCode)
                return true
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
                        openVrSettings()
                        return true
                    }
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // 触摸/触控板事件
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleTouchDown(e)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleTouchMove(e)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handleTouchUp(e)
                    return true
                }
            }
        }
        return cardboardView.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun handleTouchDown(event: MotionEvent) {
        isTouchpadPressed = true
        touchpadPressTime = System.currentTimeMillis()
        touchpadStartX = event.x
        touchpadStartY = event.y

        val now = System.currentTimeMillis()
        if (now - lastClickTime < DOUBLE_CLICK_TIMEOUT) {
            clickCount++
        } else {
            clickCount = 1
        }
        lastClickTime = now
    }

    private fun handleTouchMove(event: MotionEvent) {
        if (!isTouchpadPressed) return

        val currentX = event.x
        val currentY = event.y
        val dx = currentX - touchpadStartX
        val dy = currentY - touchpadStartY

        // 长按触发窗口菜单
        if (System.currentTimeMillis() - touchpadPressTime > LONG_PRESS_TIMEOUT) {
            showWindowMenu()
            return
        }

        when (clickCount) {
            1 -> {
                if (windowRenderer.selectedWindowId != -1L) {
                    windowRenderer.updateDrag(dx / cardboardView.width, dy / cardboardView.height)
                }
            }
            2 -> {
                if (windowRenderer.selectedWindowId != -1L) {
                    windowRenderer.updateResize(dx / cardboardView.width, dy / cardboardView.height)
                }
            }
        }
    }

    private fun handleTouchUp(event: MotionEvent) {
        isTouchpadPressed = false
        val pressDuration = System.currentTimeMillis() - touchpadPressTime

        if (pressDuration < LONG_PRESS_TIMEOUT) {
            if (clickCount == 1) {
                handleSingleTap(event.x, event.y)
            } else if (clickCount == 2) {
                handleDoubleTap()
            }
        }

        windowRenderer.endDrag()
        windowRenderer.endResize()
    }

    private fun handleSingleTap(x: Float, y: Float) {
        val focusedWindow = windowManager.getFocusedWindow()
        focusedWindow?.let { windowRenderer.setSelected(it.id) }
    }

    private fun handleDoubleTap() {
        val selectedId = windowRenderer.selectedWindowId
        if (selectedId != -1L) {
            val window = windowManager.getWindow(selectedId)
            window?.let { w ->
                if (w.isMaximized) windowManager.restoreWindow(selectedId)
                else windowManager.maximizeWindow(selectedId)
            }
        }
        clickCount = 0
    }

    private fun showWindowMenu() {
        val selectedId = windowRenderer.selectedWindowId
        if (selectedId == -1L) return
        val w = windowManager.getWindow(selectedId) ?: return
        when {
            w.isMinimized -> windowManager.restoreWindow(selectedId)
            w.isMaximized -> windowManager.restoreWindow(selectedId)
            else -> windowManager.minimizeWindow(selectedId)
        }
    }

    private fun handleSelectAction() {
        windowManager.getFocusedWindow()?.let { windowManager.focusWindow(it.id) }
    }

    private fun handleDpadNavigation(keyCode: Int) {
        val windows = windowManager.getVisibleWindows()
        if (windows.isEmpty()) return

        val focusedWindow = windowManager.getFocusedWindow()
        var nextWindow: WindowManager.WindowInfo? = null

        if (focusedWindow != null) {
            val currentIndex = windows.indexOfFirst { it.id == focusedWindow.id }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    nextWindow = if (currentIndex > 0) windows[currentIndex - 1] else windows.last()
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    nextWindow = if (currentIndex < windows.size - 1) windows[currentIndex + 1] else windows.first()
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    nextWindow = if (currentIndex > 0) windows[currentIndex - 1] else windows.last()
                }
            }
        } else {
            nextWindow = windows.first()
        }

        nextWindow?.let { windowManager.focusWindow(it.id) }
    }

    private fun openVrSettings() {
        Log.d(TAG, "Opening VR settings from VR mode")
        val intent = VrSettingsActivity.createIntent(this)
        startActivity(intent)
    }

    /** 供外部调用：在虚拟显示器上启动应用后添加窗口 */
    fun onAppLaunched(packageName: String, activityName: String, title: String) {
        windowManager.addWindow(packageName, activityName, title)
    }

    companion object {
        fun createIntent(context: android.content.Context, displayId: Int): Intent {
            return Intent(context, VrActivity::class.java).apply {
                putExtra("display_id", displayId)
            }
        }
    }
}
