package com.vr2dapplauncher.shizuku

import android.hardware.input.IInputManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.util.Log
import android.view.DisplayInfo
import android.view.InputDevice
import com.vr2dapplauncher.vr.VrSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.HashMap
import java.util.regex.Pattern

/**
 * 输入设备路由：将外部手柄/键盘/鼠标绑定到虚拟显示器
 * 对应屏连/屏易连的 InputRouting.smali
 * 
 * 核心功能：
 * 1. 三重绑定策略：descriptor -> uniqueId -> port
 * 2. 通过 Shizuku 执行 `dumpsys input` 解析 descriptor -> port 映射
 * 3. 支持 USB 设备按 vendorId/productId 查找
 */
object InputRouter {
    private const val TAG = "InputRouter"

    // dumpsys input 解析正则
    private const val INPUT_DEVICE_PATTERN = "InputDevice\\s+(\\d+):\\s+([^\\n]+)".toRegex()
    private const val DESCRIPTOR_PATTERN = "descriptor=([^\\s]+)".toRegex()
    private const val UNIQUE_ID_PATTERN = "uniqueId=([^\\s]+)".toRegex()
    private const val PORT_PATTERN = "port=([^\\s]+)".toRegex()
    private const val VENDOR_PATTERN = "vendor=0x([0-9a-fA-F]+)".toRegex()
    private const val PRODUCT_PATTERN = "product=0x([0-9a-fA-F]+)".toRegex()

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
     * 此处通过 Shizuku 的 Shell 执行命令实现
     */
    private fun getInputDeviceDescriptorToPortMap(): Map<String, String> {
        val map = HashMap<String, String>()
        
        // 尝试通过 Shizuku 执行 dumpsys input
        try {
            val output = executeDumpsysInput()
            if (output != null) {
                parseDumpsysInput(output, map)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to execute dumpsys input", e)
        }
        
        return map
    }

    /** 通过 Shizuku 执行 `dumpsys input` 命令 */
    private fun executeDumpsysInput(): String? {
        // 使用 Shizuku 的 Shell 命令执行
        // 注意：需要 Shizuku v13+ 且有执行 shell 命令的权限
        try {
            // 这里使用反射调用 Shizuku 的内部 API
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val executeMethod = shizukuClass.getDeclaredMethod("execute", String::class.java, Array<String>::class.java)
            executeMethod.isAccessible = true
            
            val result = executeMethod.invoke(null, "dumpsys", arrayOf("input")) as String?
            return result
        } catch (e: Exception) {
            // 如果反射失败，尝试通过 Runtime.exec (需要 root 或 shell 权限)
            Log.d(TAG, "Shizuku execute not available, trying Runtime.exec")
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys input"))
                process.waitFor()
                process.inputStream.reader().readText()
            } catch (e2: Exception) {
                Log.w(TAG, "Runtime.exec dumpsys input failed", e2)
                null
            }
        }
    }

    /** 解析 dumpsys input 输出 */
    private fun parseDumpsysInput(output: String, map: HashMap<String, String>) {
        var currentDescriptor: String? = null
        var currentUniqueId: String? = null
        var currentPort: String? = null

        output.lines().forEach { line ->
            val trimmed = line.trim()
            
            // 匹配 InputDevice 行
            val deviceMatch = INPUT_DEVICE_PATTERN.matchEntire(trimmed)
            if (deviceMatch != null) {
                // 保存上一个设备的映射
                if (currentDescriptor != null && currentPort != null) {
                    map[currentDescriptor] = currentPort
                }
                currentDescriptor = null
                currentUniqueId = null
                currentPort = null
            }
            
            // 提取 descriptor
            DESCRIPTOR_PATTERN.find(trimmed)?.let { currentDescriptor = it.groupValues[1] }
            
            // 提取 uniqueId
            UNIQUE_ID_PATTERN.find(trimmed)?.let { currentUniqueId = it.groupValues[1] }
            
            // 提取 port
            PORT_PATTERN.find(trimmed)?.let { currentPort = it.groupValues[1] }
        }
        
        // 保存最后一个设备
        if (currentDescriptor != null && currentPort != null) {
            map[currentDescriptor] = currentPort
        }
        
        Log.d(TAG, "Parsed dumpsys input, found ${map.size} descriptor->port mappings: $map")
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

    /**
     * 刷新输入设备绑定（当设备热插拔时调用）
     */
    fun refreshInputBindings(displayId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            bindAllExternalInputToDisplay(displayId)
        }
    }
}