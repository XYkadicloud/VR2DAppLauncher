package com.vr2dapplauncher

import android.app.Application
import android.util.Log
import com.vr2dapplauncher.util.Diagnostics

/** 应用程序入口。 */
class VR2DApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("VR2DApp", "Application created")
        Diagnostics.install(this)
        Diagnostics.record("BOOT", "VR2DApp.onCreate")
    }
}
