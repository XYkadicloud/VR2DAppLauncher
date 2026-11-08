package com.vr2dapplauncher.shizuku

import android.content.Context

/** 一个可启动应用。component 形如 "com.example/.MainActivity"，供 am start -n 使用。 */
data class AppInfo(
    val packageName: String,
    val component: String,
    val label: String
) {
    fun encode(): String = listOf(packageName, component, label).joinToString("\t")

    companion object {
        fun decode(line: String): AppInfo? {
            val parts = line.split("\t", limit = 3)
            return if (parts.size == 3 && parts[0].isNotEmpty()) {
                AppInfo(parts[0], parts[1], parts[2])
            } else {
                null
            }
        }
    }
}

/**
 * 用户在 2D 应用列表里勾选、要在 VR 中同时运行的应用。
 *
 * 每个应用对应一块独立虚拟屏（OWN_CONTENT_ONLY，不镜像主屏），
 * 因此列表长度 = 虚拟屏数量 = VR 中窗口数量。最多 4 个（老设备 GPU/内存有限）。
 */
object LaunchPlan {

    private const val PREFS = "launch_plan"
    private const val KEY = "apps"
    const val MAX_APPS = 4

    fun load(context: Context): List<AppInfo> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.lines().mapNotNull { AppInfo.decode(it) }
    }

    fun save(context: Context, apps: List<AppInfo>) {
        val data = apps.take(MAX_APPS).joinToString("\n") { it.encode() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, data).apply()
    }

    /** @return 是否真的加入（重复或已满返回 false） */
    fun add(context: Context, app: AppInfo): Boolean {
        val current = load(context).toMutableList()
        if (current.size >= MAX_APPS) return false
        if (current.any { it.packageName == app.packageName }) return false
        current.add(app)
        save(context, current)
        return true
    }

    fun remove(context: Context, packageName: String) {
        save(context, load(context).filterNot { it.packageName == packageName })
    }

    fun clear(context: Context) = save(context, emptyList())
}
