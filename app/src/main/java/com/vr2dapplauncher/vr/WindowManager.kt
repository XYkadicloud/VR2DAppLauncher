package com.vr2dapplauncher.vr

import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 虚拟显示器窗口管理器
 */
class WindowManager(private val context: android.content.Context, private val displayId: Int) {

    companion object {
        private const val TAG = "WindowManager"
        const val DEFAULT_WINDOW_WIDTH = 800
        const val DEFAULT_WINDOW_HEIGHT = 600
        const val MIN_WINDOW_WIDTH = 320
        const val MIN_WINDOW_HEIGHT = 240
        const val MAX_WINDOW_WIDTH = 1920
        const val MAX_WINDOW_HEIGHT = 1080
    }

    // 窗口存储
    private val windows = ConcurrentHashMap<Long, WindowInfo>()
    private val windowIdGenerator = AtomicLong(1)

    // 事件通道
    private val windowEventsChannel = Channel<WindowEvent>(100)
    val windowEvents: ReceiveChannel<WindowEvent> = windowEventsChannel

    // 焦点窗口
    @Volatile var focusedWindowId: Long = -1

    // 虚拟显示器尺寸（VrWindowRenderer 需要读取，故为 public）
    var displayWidth: Int = 1920
        private set
    var displayHeight: Int = 1080
        private set

    /**
     * 窗口信息数据类
     */
    data class WindowInfo(
        val id: Long,
        val packageName: String,
        val activityName: String,
        val title: String,
        var bounds: Rect,
        var zOrder: Int = 0,
        var isVisible: Boolean = true,
        var isMinimized: Boolean = false,
        var isMaximized: Boolean = false,
        var isFocused: Boolean = false,
        var lastUpdateTime: Long = System.currentTimeMillis()
    ) : Parcelable {
        constructor(parcel: Parcel) : this(
            id = parcel.readLong(),
            packageName = parcel.readString()!!,
            activityName = parcel.readString()!!,
            title = parcel.readString()!!,
            bounds = parcel.readParcelable(Rect::class.java.classLoader)!!,
            zOrder = parcel.readInt(),
            isVisible = parcel.readInt() == 1,
            isMinimized = parcel.readInt() == 1,
            isMaximized = parcel.readInt() == 1,
            isFocused = parcel.readInt() == 1,
            lastUpdateTime = parcel.readLong()
        )

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeLong(id)
            dest.writeString(packageName)
            dest.writeString(activityName)
            dest.writeString(title)
            dest.writeParcelable(bounds, flags)
            dest.writeInt(zOrder)
            dest.writeInt(if (isVisible) 1 else 0)
            dest.writeInt(if (isMinimized) 1 else 0)
            dest.writeInt(if (isMaximized) 1 else 0)
            dest.writeInt(if (isFocused) 1 else 0)
            dest.writeLong(lastUpdateTime)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<WindowInfo> = object : Parcelable.Creator<WindowInfo> {
                override fun createFromParcel(parcel: Parcel): WindowInfo = WindowInfo(parcel)
                override fun newArray(size: Int): Array<WindowInfo?> = arrayOfNulls(size)
            }
        }
    }

    /**
     * 窗口事件
     */
    sealed class WindowEvent {
        data class WindowAdded(val window: WindowInfo) : WindowEvent()
        data class WindowRemoved(val windowId: Long) : WindowEvent()
        data class WindowUpdated(val window: WindowInfo) : WindowEvent()
        data class WindowFocusChanged(val windowId: Long) : WindowEvent()
        data class WindowMoved(val windowId: Long, val newBounds: Rect) : WindowEvent()
        data class WindowResized(val windowId: Long, val newBounds: Rect) : WindowEvent()
        data class WindowMinimized(val windowId: Long) : WindowEvent()
        data class WindowMaximized(val windowId: Long) : WindowEvent()
        data class WindowRestored(val windowId: Long) : WindowEvent()
        data class WindowClosed(val windowId: Long) : WindowEvent()
    }

    fun setDisplaySize(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
    }

    fun addWindow(
        packageName: String,
        activityName: String,
        title: String,
        initialBounds: Rect? = null
    ): WindowInfo {
        val id = windowIdGenerator.getAndIncrement()
        val bounds = initialBounds ?: calculateDefaultBounds()

        val window = WindowInfo(
            id = id,
            packageName = packageName,
            activityName = activityName,
            title = title,
            bounds = bounds,
            zOrder = windows.size
        )

        windows[id] = window
        focusWindow(id)

        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowAdded(window))
        }

        Log.d(TAG, "Window added: $title (id=$id, pkg=$packageName, bounds=$bounds)")
        return window
    }

    fun removeWindow(windowId: Long): Boolean {
        val window = windows.remove(windowId)
        if (window != null) {
            if (focusedWindowId == windowId) {
                focusedWindowId = -1
                val topWindow = windows.values.maxByOrNull { it.zOrder }
                topWindow?.let { focusWindow(it.id) }
            }

            CoroutineScope(Dispatchers.IO).launch {
                windowEventsChannel.send(WindowEvent.WindowClosed(windowId))
            }

            Log.d(TAG, "Window removed: ${window.title} (id=$windowId)")
            return true
        }
        return false
    }

    fun moveWindow(windowId: Long, newX: Int, newY: Int): Boolean {
        val window = windows[windowId] ?: return false

        val clampedX = newX.coerceIn(0, displayWidth - window.bounds.width())
        val clampedY = newY.coerceIn(0, displayHeight - window.bounds.height())

        val newBounds = Rect(clampedX, clampedY, clampedX + window.bounds.width(), clampedY + window.bounds.height())
        window.bounds = newBounds
        window.lastUpdateTime = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowMoved(windowId, newBounds))
            windowEventsChannel.send(WindowEvent.WindowUpdated(window))
        }

        return true
    }

    fun resizeWindow(windowId: Long, newWidth: Int, newHeight: Int, anchorX: Float = 0.5f, anchorY: Float = 0.5f): Boolean {
        val window = windows[windowId] ?: return false

        val clampedWidth = newWidth.coerceIn(MIN_WINDOW_WIDTH, MAX_WINDOW_WIDTH)
        val clampedHeight = newHeight.coerceIn(MIN_WINDOW_HEIGHT, MAX_WINDOW_HEIGHT)

        val oldBounds = window.bounds
        // 全部保持 Int：Rect 只接受 Int，Float 会导致类型不匹配编译失败。
        val dx = ((clampedWidth - oldBounds.width()) * anchorX).toInt()
        val dy = ((clampedHeight - oldBounds.height()) * anchorY).toInt()

        val newLeft = (oldBounds.left - dx).coerceIn(0, displayWidth - clampedWidth)
        val newTop = (oldBounds.top - dy).coerceIn(0, displayHeight - clampedHeight)

        val newBounds = Rect(newLeft, newTop, newLeft + clampedWidth, newTop + clampedHeight)
        window.bounds = newBounds
        window.lastUpdateTime = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowResized(windowId, newBounds))
            windowEventsChannel.send(WindowEvent.WindowUpdated(window))
        }

        return true
    }

    fun setZOrder(windowId: Long, zOrder: Int): Boolean {
        val window = windows[windowId] ?: return false
        window.zOrder = zOrder
        window.lastUpdateTime = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowUpdated(window))
        }

        return true
    }

    /** 将窗口置顶（不自动聚焦，避免递归） */
    fun bringToFront(windowId: Long): Boolean {
        val window = windows[windowId] ?: return false
        val maxZ = windows.values.maxByOrNull { it.zOrder }?.zOrder ?: 0
        window.zOrder = maxZ + 1
        window.lastUpdateTime = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowUpdated(window))
        }

        return true
    }

    fun minimizeWindow(windowId: Long): Boolean {
        val window = windows[windowId] ?: return false
        if (!window.isMinimized) {
            window.isMinimized = true
            window.isVisible = false
            window.lastUpdateTime = System.currentTimeMillis()

            CoroutineScope(Dispatchers.IO).launch {
                windowEventsChannel.send(WindowEvent.WindowMinimized(windowId))
                windowEventsChannel.send(WindowEvent.WindowUpdated(window))
            }
        }
        return true
    }

    fun maximizeWindow(windowId: Long): Boolean {
        val window = windows[windowId] ?: return false
        if (!window.isMaximized) {
            window.isMaximized = true
            window.bounds = Rect(0, 0, displayWidth, displayHeight)
            window.lastUpdateTime = System.currentTimeMillis()

            CoroutineScope(Dispatchers.IO).launch {
                windowEventsChannel.send(WindowEvent.WindowMaximized(windowId))
                windowEventsChannel.send(WindowEvent.WindowUpdated(window))
            }
        }
        return true
    }

    fun restoreWindow(windowId: Long): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isMinimized || window.isMaximized) {
            window.isMinimized = false
            window.isMaximized = false
            window.isVisible = true
            window.bounds = calculateDefaultBounds()
            window.lastUpdateTime = System.currentTimeMillis()

            CoroutineScope(Dispatchers.IO).launch {
                windowEventsChannel.send(WindowEvent.WindowRestored(windowId))
                windowEventsChannel.send(WindowEvent.WindowUpdated(window))
            }
        }
        return true
    }

    /** 设置窗口焦点（聚焦时自动置顶） */
    fun focusWindow(windowId: Long): Boolean {
        val window = windows[windowId] ?: return false

        if (focusedWindowId != -1 && focusedWindowId != windowId) {
            windows[focusedWindowId]?.let { prev ->
                prev.isFocused = false
                CoroutineScope(Dispatchers.IO).launch {
                    windowEventsChannel.send(WindowEvent.WindowUpdated(prev))
                }
            }
        }

        focusedWindowId = windowId
        window.isFocused = true
        window.isVisible = true
        window.lastUpdateTime = System.currentTimeMillis()
        bringToFront(windowId)  // 置顶但不再递归调用 focusWindow

        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowFocusChanged(windowId))
            windowEventsChannel.send(WindowEvent.WindowUpdated(window))
        }

        return true
    }

    fun getWindow(windowId: Long): WindowInfo? = windows[windowId]

    fun getAllWindows(): List<WindowInfo> {
        return windows.values.toList().sortedByDescending { it.zOrder }
    }

    fun getVisibleWindows(): List<WindowInfo> {
        return windows.values.filter { it.isVisible && !it.isMinimized }
            .sortedByDescending { it.zOrder }
    }

    fun getFocusedWindow(): WindowInfo? = if (focusedWindowId != -1) windows[focusedWindowId] else null

    fun findWindowsByPackage(packageName: String): List<WindowInfo> {
        return windows.values.filter { it.packageName == packageName }
    }

    fun closeWindow(windowId: Long): Boolean = removeWindow(windowId)

    private fun calculateDefaultBounds(): Rect {
        val offset = (windows.size * 30) % 200
        val left = ((displayWidth - DEFAULT_WINDOW_WIDTH) / 2 + offset).coerceIn(0, displayWidth - DEFAULT_WINDOW_WIDTH)
        val top = ((displayHeight - DEFAULT_WINDOW_HEIGHT) / 2 + offset).coerceIn(0, displayHeight - DEFAULT_WINDOW_HEIGHT)
        return Rect(left, top, left + DEFAULT_WINDOW_WIDTH, top + DEFAULT_WINDOW_HEIGHT)
    }

    fun onDisplayResized(newWidth: Int, newHeight: Int) {
        val oldWidth = displayWidth
        val oldHeight = displayHeight
        displayWidth = newWidth
        displayHeight = newHeight

        val scaleX = newWidth.toFloat() / oldWidth
        val scaleY = newHeight.toFloat() / oldHeight

        windows.values.forEach { window ->
            if (!window.isMaximized) {
                val newBounds = Rect(
                    (window.bounds.left * scaleX).toInt(),
                    (window.bounds.top * scaleY).toInt(),
                    (window.bounds.right * scaleX).toInt(),
                    (window.bounds.bottom * scaleY).toInt()
                )
                val clampedBounds = Rect(
                    newBounds.left.coerceIn(0, newWidth - MIN_WINDOW_WIDTH),
                    newBounds.top.coerceIn(0, newHeight - MIN_WINDOW_HEIGHT),
                    newBounds.right.coerceIn(MIN_WINDOW_WIDTH, newWidth),
                    newBounds.bottom.coerceIn(MIN_WINDOW_HEIGHT, newHeight)
                )
                window.bounds = clampedBounds
            } else {
                window.bounds = Rect(0, 0, newWidth, newHeight)
            }
            window.lastUpdateTime = System.currentTimeMillis()

            CoroutineScope(Dispatchers.IO).launch {
                windowEventsChannel.send(WindowEvent.WindowUpdated(window))
            }
        }
    }

    fun clear() {
        windows.keys.forEach { id ->
            CoroutineScope(Dispatchers.IO).launch {
                windowEventsChannel.send(WindowEvent.WindowClosed(id))
            }
        }
        windows.clear()
        focusedWindowId = -1
    }
}