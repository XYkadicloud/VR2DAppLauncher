package com.vr2dapplauncher.vr

import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 虚拟屏窗口管理器：一块独立虚拟屏对应一个窗口。
 */
class WindowManager(private val context: android.content.Context) {

    companion object {
        private const val TAG = "WindowManager"
        const val DEFAULT_WINDOW_WIDTH = 800
        const val DEFAULT_WINDOW_HEIGHT = 600
        const val MIN_WINDOW_WIDTH = 320
        const val MIN_WINDOW_HEIGHT = 240
        const val MAX_WINDOW_WIDTH = 1920
        const val MAX_WINDOW_HEIGHT = 1080
    }

    /** VrVirtualDisplayHost 传来的轻量描述（避免 vr 包反向依赖 shizuku 包）。 */
    data class SlotDescriptor(
        val index: Int,
        val title: String,
        val packageName: String,
        val displayId: Int,
        val hasTexture: Boolean
    )

    private val windows = ConcurrentHashMap<Long, WindowInfo>()
    private val windowIdGenerator = AtomicLong(1)

    private val windowEventsChannel = Channel<WindowEvent>(100)
    val windowEvents: ReceiveChannel<WindowEvent> = windowEventsChannel

    @Volatile
    var focusedWindowId: Long = -1

    var displayWidth: Int = 1280
        private set
    var displayHeight: Int = 720
        private set

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
        var lastUpdateTime: Long = System.currentTimeMillis(),
        var displayId: Int = -1,
        var slotIndex: Int = -1,
        var isPlaceholder: Boolean = false
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
            lastUpdateTime = parcel.readLong(),
            displayId = parcel.readInt(),
            slotIndex = parcel.readInt(),
            isPlaceholder = parcel.readInt() == 1
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
            dest.writeInt(displayId)
            dest.writeInt(slotIndex)
            dest.writeInt(if (isPlaceholder) 1 else 0)
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
        displayWidth = width.coerceAtLeast(MIN_WINDOW_WIDTH)
        displayHeight = height.coerceAtLeast(MIN_WINDOW_HEIGHT)
    }

    /**
     * 按虚拟屏列表重建窗口（VR 进入 / 虚拟屏重建时调用）。
     * 多窗口平铺，保证每个应用窗口都有独立 bounds，可单独移动/缩放。
     */
    fun syncFromSlots(list: List<SlotDescriptor>) {
        clear()
        val count = list.size.coerceAtLeast(1)
        val cols = if (count <= 1) 1 else 2
        val rows = (count + cols - 1) / cols
        val cellW = (displayWidth / cols).coerceAtLeast(MIN_WINDOW_WIDTH)
        val cellH = (displayHeight / rows).coerceAtLeast(MIN_WINDOW_HEIGHT)

        list.forEachIndexed { i, descriptor ->
            val col = i % cols
            val row = i / cols
            val left = (col * cellW).coerceAtMost(displayWidth - MIN_WINDOW_WIDTH)
            val top = (row * cellH).coerceAtMost(displayHeight - MIN_WINDOW_HEIGHT)
            val right = (left + cellW).coerceAtMost(displayWidth)
            val bottom = (top + cellH).coerceAtMost(displayHeight)
            val bounds = Rect(left, top, right.coerceAtLeast(left + MIN_WINDOW_WIDTH), bottom.coerceAtLeast(top + MIN_WINDOW_HEIGHT))
            addWindow(
                packageName = descriptor.packageName,
                activityName = "",
                title = if (descriptor.title.isEmpty()) "窗口 ${i + 1}" else descriptor.title,
                initialBounds = bounds,
                displayId = descriptor.displayId,
                slotIndex = descriptor.index,
                isPlaceholder = !descriptor.hasTexture
            )
        }
        Log.d(TAG, "syncFromSlots: ${list.size} slots -> ${windows.size} windows")
    }

    fun addWindow(
        packageName: String,
        activityName: String,
        title: String,
        initialBounds: Rect? = null,
        displayId: Int = -1,
        slotIndex: Int = -1,
        isPlaceholder: Boolean = false
    ): WindowInfo {
        val id = windowIdGenerator.getAndIncrement()
        val bounds = initialBounds ?: calculateDefaultBounds()
        val window = WindowInfo(
            id = id,
            packageName = packageName,
            activityName = activityName,
            title = title,
            bounds = bounds,
            zOrder = windows.size,
            displayId = displayId,
            slotIndex = slotIndex,
            isPlaceholder = isPlaceholder
        )
        windows[id] = window
        focusWindow(id)
        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowAdded(window))
        }
        Log.d(TAG, "Window added: $title (id=$id, pkg=$packageName, slot=$slotIndex, bounds=$bounds)")
        return window
    }

    fun removeWindow(windowId: Long): Boolean {
        val window = windows.remove(windowId) ?: return false
        if (focusedWindowId == windowId) {
            focusedWindowId = -1
            windows.values.maxByOrNull { it.zOrder }?.let { focusWindow(it.id) }
        }
        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowClosed(windowId))
        }
        return true
    }

    fun moveWindow(windowId: Long, newX: Int, newY: Int): Boolean {
        val window = windows[windowId] ?: return false
        val maxX = (displayWidth - window.bounds.width()).coerceAtLeast(0)
        val maxY = (displayHeight - window.bounds.height()).coerceAtLeast(0)
        val clampedX = newX.coerceIn(0, maxX)
        val clampedY = newY.coerceIn(0, maxY)
        val newBounds = Rect(clampedX, clampedY, clampedX + window.bounds.width(), clampedY + window.bounds.height())
        window.bounds = newBounds
        window.lastUpdateTime = System.currentTimeMillis()
        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowMoved(windowId, newBounds))
            windowEventsChannel.send(WindowEvent.WindowUpdated(window))
        }
        return true
    }

    fun resizeWindow(
        windowId: Long,
        newWidth: Int,
        newHeight: Int,
        anchorX: Float = 0.5f,
        anchorY: Float = 0.5f
    ): Boolean {
        val window = windows[windowId] ?: return false
        val maxW = MAX_WINDOW_WIDTH.coerceAtMost(displayWidth)
        val maxH = MAX_WINDOW_HEIGHT.coerceAtMost(displayHeight)
        val clampedWidth = newWidth.coerceIn(MIN_WINDOW_WIDTH, maxW.coerceAtLeast(MIN_WINDOW_WIDTH))
        val clampedHeight = newHeight.coerceIn(MIN_WINDOW_HEIGHT, maxH.coerceAtLeast(MIN_WINDOW_HEIGHT))
        val oldBounds = window.bounds
        val dx = ((clampedWidth - oldBounds.width()) * anchorX).toInt()
        val dy = ((clampedHeight - oldBounds.height()) * anchorY).toInt()
        val newLeft = (oldBounds.left - dx).coerceIn(0, (displayWidth - clampedWidth).coerceAtLeast(0))
        val newTop = (oldBounds.top - dy).coerceIn(0, (displayHeight - clampedHeight).coerceAtLeast(0))
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

    /** 置顶但不递归聚焦（旧版 focusWindow 调用它又回调 focusWindow 会栈溢出）。 */
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

    fun focusWindow(windowId: Long): Boolean {
        val window = windows[windowId] ?: return false
        if (focusedWindowId != -1L && focusedWindowId != windowId) {
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
        bringToFront(windowId)
        CoroutineScope(Dispatchers.IO).launch {
            windowEventsChannel.send(WindowEvent.WindowFocusChanged(windowId))
            windowEventsChannel.send(WindowEvent.WindowUpdated(window))
        }
        return true
    }

    fun getWindow(windowId: Long): WindowInfo? = windows[windowId]
    fun getAllWindows(): List<WindowInfo> = windows.values.toList().sortedByDescending { it.zOrder }
    fun getVisibleWindows(): List<WindowInfo> =
        windows.values.filter { it.isVisible && !it.isMinimized }.sortedBy { it.zOrder }
    fun getFocusedWindow(): WindowInfo? =
        if (focusedWindowId != -1L) windows[focusedWindowId] else null
    fun findWindowsByPackage(packageName: String): List<WindowInfo> =
        windows.values.filter { it.packageName == packageName }
    fun closeWindow(windowId: Long): Boolean = removeWindow(windowId)

    /** 命中测试：VR 屏幕归一化坐标 (0..1) -> 虚拟屏像素 -> 最上层窗口 */
    fun hitTest(nx: Float, ny: Float): WindowInfo? {
        val px = (nx * displayWidth).toInt()
        val py = (ny * displayHeight).toInt()
        return getVisibleWindows().lastOrNull { it.bounds.contains(px, py) }
            ?: getFocusedWindow()
    }

    private fun calculateDefaultBounds(): Rect {
        val w = DEFAULT_WINDOW_WIDTH.coerceAtMost(displayWidth)
        val h = DEFAULT_WINDOW_HEIGHT.coerceAtMost(displayHeight)
        val offset = (windows.size * 30) % 200
        val left = ((displayWidth - w) / 2 + offset).coerceIn(0, (displayWidth - w).coerceAtLeast(0))
        val top = ((displayHeight - h) / 2 + offset).coerceIn(0, (displayHeight - h).coerceAtLeast(0))
        return Rect(left, top, left + w, top + h)
    }

    fun onDisplayResized(newWidth: Int, newHeight: Int) {
        val oldWidth = displayWidth
        val oldHeight = displayHeight
        displayWidth = newWidth.coerceAtLeast(MIN_WINDOW_WIDTH)
        displayHeight = newHeight.coerceAtLeast(MIN_WINDOW_HEIGHT)
        val scaleX = displayWidth.toFloat() / oldWidth.coerceAtLeast(1)
        val scaleY = displayHeight.toFloat() / oldHeight.coerceAtLeast(1)
        windows.values.forEach { window ->
            window.bounds = if (window.isMaximized) {
                Rect(0, 0, displayWidth, displayHeight)
            } else {
                val b = Rect(
                    (window.bounds.left * scaleX).toInt(),
                    (window.bounds.top * scaleY).toInt(),
                    (window.bounds.right * scaleX).toInt(),
                    (window.bounds.bottom * scaleY).toInt()
                )
                Rect(
                    b.left.coerceIn(0, displayWidth - MIN_WINDOW_WIDTH),
                    b.top.coerceIn(0, displayHeight - MIN_WINDOW_HEIGHT),
                    b.right.coerceIn(MIN_WINDOW_WIDTH, displayWidth),
                    b.bottom.coerceIn(MIN_WINDOW_HEIGHT, displayHeight)
                )
            }
            window.lastUpdateTime = System.currentTimeMillis()
            CoroutineScope(Dispatchers.IO).launch {
                windowEventsChannel.send(WindowEvent.WindowUpdated(window))
            }
        }
    }

    fun clear() {
        windows.clear()
        focusedWindowId = -1
    }
}
