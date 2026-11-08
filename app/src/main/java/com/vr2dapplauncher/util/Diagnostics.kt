package com.vr2dapplauncher.util

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.util.Collections

/**
 * 轻量运行诊断：把关键事件与未捕获异常记到内存，可一键导出成文件。
 *
 * 用途：本机无法真机运行验证，用户遇到闪退时导出这个文件即可定位根因，
 * 不需要连 adb logcat。所有写入都做异常保护，绝不会因为诊断逻辑本身导致崩溃。
 */
object Diagnostics {

    private val lines = Collections.synchronizedList(mutableListOf<String>())

    init {
        record("BOOT", "Diagnostics initialized")
    }

    fun record(tag: String, message: String) {
        try {
            synchronized(lines) {
                lines.add("${System.currentTimeMillis()} [$tag] $message")
                while (lines.size > 400) lines.removeAt(0)
            }
        } catch (e: Exception) {
            Log.w("Diagnostics", "record failed", e)
        }
    }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(
                    "CRASH",
                    "thread=${thread.name} ${throwable.javaClass.name}: ${throwable.message}\n" +
                        Log.getStackTraceString(throwable)
                )
            } catch (e: Exception) {
                Log.w("Diagnostics", "record crash failed", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
        record("BOOT", "UncaughtExceptionHandler installed")
    }

    /** 导出到应用私有外部目录，返回绝对路径；失败返回 null。 */
    fun export(context: Context): String? = try {
        val dir = context.getExternalFilesDir(null)
        if (dir == null) {
            record("EXPORT", "getExternalFilesDir returned null")
            null
        } else {
            val file = File(dir, "vr2d_diagnostics.txt")
            val snapshot = synchronized(lines) { lines.toList() }
            file.writeText(snapshot.joinToString("\n"))
            record("EXPORT", "wrote ${file.absolutePath} (${snapshot.size} lines)")
            file.absolutePath
        }
    } catch (e: Exception) {
        record("EXPORT", "failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
