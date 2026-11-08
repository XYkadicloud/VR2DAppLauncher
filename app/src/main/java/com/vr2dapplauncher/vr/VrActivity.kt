package com.vr2dapplauncher.vr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.cardboard.sdk.CardboardView
import com.vr2dapplauncher.R
import com.vr2dapplauncher.shizuku.InputRouter
import com.vr2dapplauncher.shizuku.VrVirtualDisplayHost
import com.vr2dapplauncher.ui.VrSettingsActivity
import com.vr2dapplauncher.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * VR 模式 Activity：全屏 CardboardView，渲染「一块独立虚拟屏 = 一个窗口」的多窗口。
 *
 * 全量审查修正：
 *  - 去掉旧的单屏 displayId 取值接口 / WindowManager(context, displayId)，
 *    改为 WindowManager(context) + host.displayWidth/Height；
 *  - 触摸事件先交给 CardboardView（旧版全吞掉，SDK 收不到 magnet/触控板事件）；
 *  - 单击用 WindowManager.hitTest() 选中窗口，并把点击注入到该窗口对应的
 *    displayId（input -d <id> tap x y），实现「在 VR 里操作应用」；
 *  - 拖拽 / 缩放的 startDrag/startResize 在首次 MOVE 时接通（旧版从未调用）。
 */
class VrActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VrActivity"
        private const val LONG_PRESS_TIMEOUT = 800L
        private const val DOUBLE_CLICK_TIMEOUT = 300L

        fun createIntent(context: Context): Intent = Intent(context, VrActivity::class.java)
    }

    private lateinit var cardboardView: CardboardView
    private lateinit var host: VrVirtualDisplayHost
    private lateinit var windowRenderer: VrWindowRenderer
    private lateinit var settings: VrSettings
    private lateinit var windowManager: WindowManager

    private var volumeKeyDownTime = 0L
    private var isVolumeKeyPressed = false

    private var lastClickTime = 0L
    private var clickCount = 0
    private var isTouchpadPressed = false
    private var touchpadPressTime = 0L
    private var touchpadStartX = 0f
    private var touchpadStartY = 0f
    private var menuShown = false
    private var gestureMode = 0 // 0=none 1=drag 2=resize

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vr)

        cardboardView = findViewById(R.id.cardboard_view)

        // host 由 VrWindowRenderer.onSurfaceCreated 在 GL 线程建屏并设置 shared；
        // 若用户没经过 MainActivity 直接进来，这里补一个空宿主（显示占位窗口）。
        host = VrVirtualDisplayHost.shared ?: VrVirtualDisplayHost(this).also {
            VrVirtualDisplayHost.shared = it
        }
        settings = VrSettings(this)
        windowManager = WindowManager(this)
        windowManager.setDisplaySize(host.displayWidth, host.displayHeight)

        windowRenderer = VrWindowRenderer(this, host, settings, windowManager)
        cardboardView.setRenderer(windowRenderer)

        observeWindowEvents()
        Diagnostics.record(TAG, "VrActivity created")
    }

    private fun observeWindowEvents() {
        CoroutineScope(Dispatchers.IO).launch {
            for (event in windowManager.windowEvents) {
                when (event) {
                    is WindowManager.WindowEvent.WindowAdded ->
                        Log.d(TAG, "window added: ${event.window.title}")
                    is WindowManager.WindowEvent.WindowFocusChanged ->
                        Log.d(TAG, "focus: ${event.windowId}")
                    is WindowManager.WindowEvent.WindowClosed ->
                        Log.d(TAG, "window closed: ${event.windowId}")
                    else -> Unit
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cardboardView.onResume()
    }

    override fun onPause() {
        super.onPause()
        cardboardView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不要在这里 stop()：返回 2D 界面后虚拟屏与授权仍要可用。
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                finish()
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!isVolumeKeyPressed) {
                    isVolumeKeyPressed = true
                    volumeKeyDownTime = System.currentTimeMillis()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                handleSelectAction()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleDpadNavigation(keyCode)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isVolumeKeyPressed) {
                val duration = System.currentTimeMillis() - volumeKeyDownTime
                isVolumeKeyPressed = false
                if (duration >= LONG_PRESS_TIMEOUT) {
                    openVrSettings()
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // CardboardView 自己要处理 magnet / 触控板，必须先把事件交给它。
        cardboardView.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(event)
            MotionEvent.ACTION_MOVE -> handleTouchMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleTouchUp(event)
        }
        return true
    }

    private fun handleTouchDown(event: MotionEvent) {
        isTouchpadPressed = true
        touchpadPressTime = System.currentTimeMillis()
        touchpadStartX = event.x
        touchpadStartY = event.y
        menuShown = false
        gestureMode = 0

        val now = System.currentTimeMillis()
        clickCount = if (now - lastClickTime < DOUBLE_CLICK_TIMEOUT) clickCount + 1 else 1
        lastClickTime = now
    }

    private fun handleTouchMove(event: MotionEvent) {
        if (!isTouchpadPressed) return

        if (System.currentTimeMillis() - touchpadPressTime > LONG_PRESS_TIMEOUT) {
            if (!menuShown) {
                menuShown = true
                showWindowMenu()
            }
            return
        }

        val w = cardboardView.width.toFloat().coerceAtLeast(1f)
        val h = cardboardView.height.toFloat().coerceAtLeast(1f)
        val dx = event.x - touchpadStartX
        val dy = event.y - touchpadStartY
        val nx = dx / w
        val ny = dy / h
        val selected = windowRenderer.selectedWindowId
        if (selected == -1L) return

        when (clickCount) {
            1 -> {
                if (gestureMode != 1) {
                    gestureMode = 1
                    windowRenderer.startDrag(selected)
                }
                windowRenderer.updateDrag(nx, ny)
            }
            2 -> {
                if (gestureMode != 2) {
                    gestureMode = 2
                    windowRenderer.startResize(selected)
                }
                windowRenderer.updateResize(nx, ny)
            }
        }
    }

    private fun handleTouchUp(event: MotionEvent) {
        isTouchpadPressed = false
        val duration = System.currentTimeMillis() - touchpadPressTime
        if (duration < LONG_PRESS_TIMEOUT && !menuShown) {
            when (clickCount) {
                1 -> handleSingleTap(event.x, event.y)
                2 -> handleDoubleTap()
            }
        }
        windowRenderer.endDrag()
        windowRenderer.endResize()
        gestureMode = 0
        menuShown = false
    }

    /** 单击：命中窗口 -> 选中 -> 把点击注入到该窗口对应的独立虚拟屏。 */
    private fun handleSingleTap(x: Float, y: Float) {
        val w = cardboardView.width.toFloat().coerceAtLeast(1f)
        val h = cardboardView.height.toFloat().coerceAtLeast(1f)
        val nx = x / w
        val ny = y / h
        val hit = windowManager.hitTest(nx, ny) ?: return
        windowRenderer.setSelected(hit.id)

        if (hit.isPlaceholder || hit.displayId < 0) {
            Toast.makeText(this, "该窗口没有关联虚拟屏，请先在应用列表里选择应用", Toast.LENGTH_SHORT).show()
            return
        }

        // VR 屏幕归一化坐标 -> 窗口内相对位置 -> 虚拟屏分辨率坐标。
        val bounds = hit.bounds
        val px = nx * windowManager.displayWidth - bounds.left
        val py = ny * windowManager.displayHeight - bounds.top
        val bw = bounds.width().coerceAtLeast(1)
        val bh = bounds.height().coerceAtLeast(1)
        val vx = (px / bw * host.displayWidth).toInt().coerceIn(0, host.displayWidth - 1)
        val vy = (py / bh * host.displayHeight).toInt().coerceIn(0, host.displayHeight - 1)
        val displayId = hit.displayId
        CoroutineScope(Dispatchers.IO).launch {
            InputRouter.injectTap(vx, vy, displayId)
        }
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
        val focused = windowManager.getFocusedWindow() ?: return
        windowRenderer.setSelected(focused.id)
    }

    private fun handleDpadNavigation(keyCode: Int) {
        val windows = windowManager.getVisibleWindows()
        if (windows.isEmpty()) return

        val focusedWindow = windowManager.getFocusedWindow()
        val nextWindow: WindowManager.WindowInfo? = if (focusedWindow != null) {
            val currentIndex = windows.indexOfFirst { it.id == focusedWindow.id }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (currentIndex > 0) windows[currentIndex - 1] else windows.last()
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (currentIndex < windows.size - 1) windows[currentIndex + 1] else windows.first()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (currentIndex > 0) windows[currentIndex - 1] else windows.last()
                else -> null
            }
        } else {
            windows.first()
        }

        nextWindow?.let { windowManager.focusWindow(it.id) }
    }

    private fun openVrSettings() {
        Log.d(TAG, "Opening VR settings from VR mode")
        startActivity(VrSettingsActivity.createIntent(this))
    }
}
