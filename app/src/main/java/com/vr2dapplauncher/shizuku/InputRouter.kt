package com.vr2dapplauncher.shizuku

import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.InputDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.HashMap

/**
 * 输入设备路由。
 *
 * 屏连/屏易连用 IInputManager.addUniqueIdAssociationByDescriptor() 把外部
 * 手柄/键盘绑定到虚拟显示器，该接口是 framework hidden API，标准 SDK 编译不过。
 *
 * 技术验证阶段的替代实现（全部使用公开 API / Shizuku 官方 API）：
 *  1) Shizuku.newProcess("dumpsys", "input") 解析 descriptor -> port 映射；
 *  2) Shizuku.newProcess("input", ...) 向指定 display 注入触摸/按键；
 *  3) InputDevice 公开静态方法枚举外部输入设备。
 * 说明：Regex 常量必须是 val 而不是 const val（const 只接受编译期常量）。
 */
object InputRouter {
    private const val TAG = "InputRouter"

    private val INPUT_DEVICE_PATTERN: Regex = "InputDevice\\s+(\\d+):\\s+([^\\n]+)".toRegex()
    private val DESCRIPTOR_PATTERN: Regex = "descriptor=([^\\s]+)".toRegex()
    private val PORT_PATTERN: Regex = "port=([^\\s]+)".toRegex()

    /** 绑定外部输入到虚拟显示器（尽力而为） */
    fun bindAllExternalInputToDisplay(displayId: Int) {
        if (!ShizukuUtils.hasPermission()) {
            Log.w(TAG, "Shizuku not granted; skip advanced input routing for display $displayId")
            return
        }
        val map = getInputDeviceDescriptorToPortMap()
        Log.d(
            TAG,
            "Input routing ready for display $displayId: ${map.size} descriptor->port entries. " +
                "Hidden IInputManager uniqueId/port association is deferred to a later version."
        )
    }

    fun refreshInputBindings(displayId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            bindAllExternalInputToDisplay(displayId)
        }
    }

    /** 通过 USB vendorId/productId 查找外部输入设备（公开 API） */
    fun findInputDeviceByUsb(usbDevice: UsbDevice): InputDevice? {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isExternal &&
                device.vendorId == usbDevice.vendorId &&
                device.productId == usbDevice.productId
            ) {
                return device
            }
        }
        return null
    }

    /** 用 Shizuku shell 身份向指定 display 注入一次点击 */
    fun injectTap(x: Int, y: Int, displayId: Int): Boolean = runInputCommand(
        arrayOf("input", "-d", displayId.toString(), "tap", x.toString(), y.toString())
    )

    /** 用 Shizuku shell 身份向指定 display 注入一个按键 */
    fun injectKey(keyCode: Int, displayId: Int): Boolean = runInputCommand(
        arrayOf("input", "-d", displayId.toString(), "keyevent", keyCode.toString())
    )

    private fun runInputCommand(command: Array<String>): Boolean = try {
        ShizukuUtils.runShell(command)
        true
    } catch (e: Exception) {
        Log.w(TAG, "input injection failed", e)
        false
    }

    /** 解析 `dumpsys input` 输出，得到 descriptor -> port 映射 */
    private fun getInputDeviceDescriptorToPortMap(): Map<String, String> {
        val map = HashMap<String, String>()
        val output = ShizukuUtils.runShell(arrayOf("dumpsys", "input")) ?: return map

        var descriptor: String? = null
        var port: String? = null
        fun flush() {
            val d = descriptor
            val p = port
            if (d != null && p != null) map[d] = p
        }

        output.lines().forEach { line ->
            val trimmed = line.trim()
            if (INPUT_DEVICE_PATTERN.matchEntire(trimmed) != null) {
                flush()
                descriptor = null
                port = null
            }
            DESCRIPTOR_PATTERN.find(trimmed)?.let { descriptor = it.groupValues[1] }
            PORT_PATTERN.find(trimmed)?.let { port = it.groupValues[1] }
        }
        flush()
        return map
    }
}
