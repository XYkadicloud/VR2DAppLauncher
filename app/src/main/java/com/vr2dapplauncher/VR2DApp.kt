package com.vr2dapplauncher

import android.app.Application
import android.util.Log

/**
 * 应用程序入口
 */
class VR2DApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("VR2DApp", "Application created")
        
        // 初始化 Shizuku（可选，按需初始化）
        // Shizuku.init(this)
    }
}