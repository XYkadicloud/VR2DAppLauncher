package com.vr2dapplauncher.shizuku

import android.hardware.input.IInputManager
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.DisplayInfo
import android.view.InputDevice
import java.util.HashMap

/**
 * 输入设备路由：将外部手柄/键盘/鼠标绑定到虚拟显示器
 * 对应屏连/屏易连的 InputRouting.smali
 */
object InputRouter {
    private const val TAG = "InputRouter"

    /**
     * 将所有外部输入设备绑定到指定虚拟显示器
     * @param displayId 虚拟显示器 ID
     */
    fun bindAllExternalInputToDisplay(displayId: Int) {
        val displayManager = ShizukuServiceUtils.getDisplayManager() ?: return
        val inputManager = ShizukuServiceUtils.getInputManager() ?: return

        val displayInfo = displayManager.getDisplayInfo(displayId) ?: return
        val descriptorToPortMap = getInputDeviceDescriptorToPortMap()

        val deviceIds = inputManager.getInputDeviceIds()
        for (deviceId in deviceIds) {
            val device = inputManager.getInputDevice(deviceId)
            if (device != null && device.isExternal) {
                bindInputToDisplay(displayInfo, device, inputManager, descriptorToPortMap)
            }
        }
    }

    /**
     * 绑定单个输入设备到虚拟显示器
     * 尝试三种绑定方式：descriptor -> uniqueId -> port
     */
    private fun bindInputToDisplay(
        displayInfo: DisplayInfo,
        inputDevice: InputDevice,
        inputManager: IInputManager,
        descriptorToPortMap: Map<String, String>
    ) {
        val descriptor = inputDevice.descriptor
        val displayUniqueId = displayInfo.uniqueId

        Log.d(TAG, "Attempting to bind device ${inputDevice.id} ($descriptor) to display $displayUniqueId")

        // 方式 1：按描述符绑定 (最常用，屏连/屏易连主用)
        try {
            inputManager.removeUniqueIdAssociationByDescriptor(descriptor)
            inputManager.addUniqueIdAssociationByDescriptor(descriptor, displayUniqueId)
            Log.d(TAG, "Successfully bound ${inputDevice.name} ($descriptor) => $displayUniqueId via descriptor")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Descriptor binding failed for ${inputDevice.name}", e)
        }

        // 方式 2：按 uniqueId 绑定 (需要从 dumpsys input 解析映射)
        try {
            val inputUniqueId = descriptorToPortMap[descriptor]
            if (inputUniqueId != null) {
                inputManager.removeUniqueIdAssociation(inputUniqueId)
                inputManager.addUniqueIdAssociation(inputUniqueId, displayUniqueId)
                Log.d(TAG, "Bound via uniqueId: $inputUniqueId => $displayUniqueId")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "UniqueId binding failed", e)
        }

        // 方式 3：按物理端口绑定 (HDMI/DP 显示器)
        try {
            val displayAddress = displayInfo.address
            if (displayAddress is android.view.DisplayAddress.Physical) {
                val port = displayAddress.port
                val inputPort = descriptorToPortMap[descriptor]
                if (inputPort != null) {
                    inputManager.removePortAssociation(inputPort)
                    inputManager.addPortAssociation(inputPort, port)
                    Log.d(TAG, "Bound via port: $inputPort => $port")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Port binding failed", e)
        }

        Log.e(TAG, "All binding methods failed for ${inputDevice.name} ($descriptor)")
    }

    /**
     * 解析 `dumpsys input` 获取 descriptor -> port 映射
     * 屏连通过 Shizuku userService.executeCommand("dumpsys input") 获取
     * 此处预留接口，实际项目可通过 Shizuku 执行 shell 命令实现
     */
    private fun getInputDeviceDescriptorToPortMap(): Map<String, String> {
        val map = HashMap<String, String>()
        // TODO: 通过 Shizuku 执行 `dumpsys input` 并解析
        // 格式参考屏连 ServiceUtils.smali getInputDeviceDescriptorToPortMap()
        return map
    }

    /**
     * 通过 USB vendorId/productId 查找输入设备
     */
    fun findInputDeviceByUsb(usbDevice: UsbDevice): InputDevice? {
        val inputManager = android.hardware.input.InputManager.getInstance()
        val deviceIds = inputManager.getInputDeviceIds()
        for (deviceId in deviceIds) {
            val device = inputManager.getInputDevice(deviceId)
            if (device != null && device.isExternal &&
                device.vendorId == usbDevice.vendorId &&
                device.productId == usbDevice.productId) {
                return device
            }
        }
        return null
    }
}