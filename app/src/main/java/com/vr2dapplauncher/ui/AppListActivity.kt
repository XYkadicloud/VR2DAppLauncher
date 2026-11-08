package com.vr2dapplauncher.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vr2dapplauncher.R
import com.vr2dapplauncher.shizuku.AppInfo
import com.vr2dapplauncher.shizuku.LaunchPlan
import com.vr2dapplauncher.util.Diagnostics

/**
 * 读取已安装应用列表，让用户勾选要在 VR 里同时运行的应用。
 *
 * 每个应用对应一块独立虚拟屏（OWN_CONTENT_ONLY，不镜像主屏），
 * 最多 LaunchPlan.MAX_APPS 块。component 用 ComponentName.flattenToShortString()
 * 生成 "pkg/.Activity"，可直接喂给 `am start --display <id> -n <component>`。
 *
 * Android 11+ 包可见性由 AndroidManifest.xml 的 <queries> 声明保证。
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var editSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var adapter: AppAdapter

    private var allApps: List<AppInfo> = emptyList()
    private var selectedPackages: MutableSet<String> = LinkedHashSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        recyclerView = findViewById(R.id.rv_apps)
        editSearch = findViewById(R.id.et_search)
        tvCount = findViewById(R.id.tv_selected_count)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter { app -> toggle(app) }
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_clear).setOnClickListener {
            LaunchPlan.clear(this)
            refreshSelection()
            Toast.makeText(this, "已清空选择", Toast.LENGTH_SHORT).show()
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                applyFilter(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })

        try {
            loadInstalledApps()
        } catch (e: Exception) {
            Diagnostics.record(
                "AppListActivity",
                "queryIntentActivities failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            Toast.makeText(this, "读取应用列表失败：${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshSelection()
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        allApps = resolved.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val pkg = ai.packageName
            if (pkg == packageName) return@mapNotNull null
            if (ai.name.isNullOrEmpty()) return@mapNotNull null
            val component = ComponentName(pkg, ai.name).flattenToShortString()
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }
            AppInfo(pkg, component, label)
        }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
        Diagnostics.record("AppListActivity", "found ${allApps.size} launchable apps")
    }

    override fun onResume() {
        super.onResume()
        refreshSelection()
    }

    private fun refreshSelection() {
        selectedPackages = LaunchPlan.load(this).map { it.packageName }.toMutableSet()
        tvCount.text = "已选 ${selectedPackages.size}/${LaunchPlan.MAX_APPS}"
        applyFilter(editSearch.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        adapter.submit(filtered, selectedPackages)
    }

    private fun toggle(app: AppInfo) {
        if (selectedPackages.contains(app.packageName)) {
            LaunchPlan.remove(this, app.packageName)
            Toast.makeText(this, "已移除 ${app.label}", Toast.LENGTH_SHORT).show()
        } else if (LaunchPlan.add(this, app)) {
            Toast.makeText(this, "已加入 ${app.label}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "最多只能选 ${LaunchPlan.MAX_APPS} 个应用", Toast.LENGTH_SHORT).show()
        }
        refreshSelection()
    }

    private class AppAdapter(
        private val onToggle: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        private var apps: List<AppInfo> = emptyList()
        private var selected: Set<String> = emptySet()

        fun submit(list: List<AppInfo>, selectedSet: Set<String>) {
            apps = list
            selected = selectedSet
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.bind(app, selected.contains(app.packageName))
        }

        override fun getItemCount(): Int = apps.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvLabel: TextView = view.findViewById(R.id.tv_app_label)
            private val tvPkg: TextView = view.findViewById(R.id.tv_app_pkg)
            private val tvState: TextView = view.findViewById(R.id.tv_app_state)

            fun bind(app: AppInfo, isSelected: Boolean) {
                tvLabel.text = app.label
                tvPkg.text = app.packageName
                tvState.text = if (isSelected) "✓ 已加入" else "+ 加入"
                tvState.setTextColor(if (isSelected) 0xFF88CC88.toInt() else 0xFF2196F3.toInt())
                itemView.setOnClickListener { onToggle(app) }
            }
        }
    }
}
