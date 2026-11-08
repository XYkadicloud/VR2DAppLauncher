package com.vr2dapplauncher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vr2dapplauncher.R
import com.vr2dapplauncher.vr.VrSettings

/**
 * VR 设置 Activity：配置屏幕参数、画面调整、舒适度设置
 * 参考 OpenNOW-VR 的设置界面设计
 */
class VrSettingsActivity : AppCompatActivity() {

    private const val TAG = "VrSettingsActivity"
    
    private lateinit var settings: VrSettings
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SettingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vr_settings)

        settings = VrSettings(this)
        
        recyclerView = findViewById(R.id.rv_settings)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SettingsAdapter(this, settings)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btn_reset).setOnClickListener { resetToDefaults() }
        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }
    }

    private fun resetToDefaults() {
        settings.resetToDefaults()
        adapter.notifyDataSetChanged()
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, VrSettingsActivity::class.java)
        }
    }

    /** 设置项数据类 */
    private sealed class SettingItem {
        data class Header(val title: String) : SettingItem()
        data class SeekBarItem(
            val title: String,
            val summary: String,
            val min: Int,
            val max: Int,
            val getValue: () -> Int,
            val onChanged: (Int) -> Unit
        ) : SettingItem()
        data class FloatSeekBarItem(
            val title: String,
            val summary: String,
            val min: Float,
            val max: Float,
            val step: Float,
            val getValue: () -> Float,
            val onChanged: (Float) -> Unit
        ) : SettingItem()
    }

    /** RecyclerView 适配器 */
    private class SettingsAdapter(
        private val context: Context,
        private val settings: VrSettings
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items: List<SettingItem> = buildSettingsList()

        private fun buildSettingsList(): List<SettingItem> {
            return listOf(
                // --- 屏幕几何参数 ---
                SettingItem.Header("屏幕几何参数 (影响 VR 中虚拟屏幕大小/距离)"),
                
                SettingItem.FloatSeekBarItem(
                    "屏幕距离", "眼睛到虚拟屏幕的距离 (米)",
                    0.02f, 0.2f, 0.005f,
                    { settings.screenDistance },
                    { settings.screenDistance = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "屏幕宽度", "虚拟屏幕物理宽度 (米)",
                    0.1f, 0.5f, 0.005f,
                    { settings.screenWidth },
                    { settings.screenWidth = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "屏幕高度", "虚拟屏幕物理高度 (米)",
                    0.05f, 0.3f, 0.005f,
                    { settings.screenHeight },
                    { settings.screenHeight = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "屏幕曲率", "正值=凸面(桶形)，负值=凹面(枕形)",
                    -1.0f, 1.0f, 0.05f,
                    { settings.curvature },
                    { settings.curvature = it }
                ),

                // --- 画面调整 ---
                SettingItem.Header("画面调整"),

                SettingItem.FloatSeekBarItem(
                    "亮度", "整体亮度倍数",
                    0.1f, 2.0f, 0.05f,
                    { settings.brightness },
                    { settings.brightness = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "对比度", "对比度倍数",
                    0.1f, 2.0f, 0.05f,
                    { settings.contrast },
                    { settings.contrast = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "饱和度", "色彩饱和度倍数",
                    0.1f, 2.0f, 0.05f,
                    { settings.saturation },
                    { settings.saturation = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "角渐晕", "边缘变暗强度 (模拟透镜效果)",
                    0.0f, 1.0f, 0.05f,
                    { settings.vignette },
                    { settings.vignette = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "色差校正", "正值=红外扩散，负值=蓝外扩散",
                    -1.0f, 1.0f, 0.05f,
                    { settings.chromaticAberration },
                    { settings.chromaticAberration = it }
                ),

                // --- 头部姿态校正 ---
                SettingItem.Header("头部姿态校正 (修正安装偏差)"),

                SettingItem.FloatSeekBarItem(
                    "瞳距 (IPD)", "双眼间距 (米)，影响立体感",
                    0.05f, 0.08f, 0.001f,
                    { settings.ipd },
                    { settings.ipd = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "偏航角偏移", "左右旋转偏移 (度)",
                    -180f, 180f, 1f,
                    { settings.yawOffset },
                    { settings.yawOffset = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "俯仰角偏移", "上下旋转偏移 (度)",
                    -90f, 90f, 1f,
                    { settings.pitchOffset },
                    { settings.pitchOffset = it }
                ),
                SettingItem.FloatSeekBarItem(
                    "翻滚角偏移", "倾斜旋转偏移 (度)",
                    -180f, 180f, 1f,
                    { settings.rollOffset },
                    { settings.rollOffset = it }
                ),

                // --- 虚拟显示器配置 ---
                SettingItem.Header("虚拟显示器配置 (需重建虚拟显示器生效)"),

                SettingItem.SeekBarItem(
                    "宽度", "虚拟显示器宽度 (像素)",
                    720, 3840,
                    { settings.virtualDisplayWidth },
                    { settings.virtualDisplayWidth = it }
                ),
                SettingItem.SeekBarItem(
                    "高度", "虚拟显示器高度 (像素)",
                    480, 2160,
                    { settings.virtualDisplayHeight },
                    { settings.virtualDisplayHeight = it }
                ),
                SettingItem.SeekBarItem(
                    "DPI", "虚拟显示器像素密度",
                    120, 640,
                    { settings.virtualDisplayDpi },
                    { settings.virtualDisplayDpi = it }
                ),
                SettingItem.SeekBarItem(
                    "刷新率", "虚拟显示器刷新率 (Hz)",
                    30, 120,
                    { settings.virtualDisplayRefreshRate },
                    { settings.virtualDisplayRefreshRate = it }
                )
            )
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is SettingItem.Header -> 0
                is SettingItem.SeekBarItem -> 1
                is SettingItem.FloatSeekBarItem -> 2
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> HeaderViewHolder(LayoutInflater.from(context).inflate(R.layout.item_settings_header, parent, false))
                1 -> SeekBarViewHolder(LayoutInflater.from(context).inflate(R.layout.item_settings_seekbar, parent, false))
                2 -> FloatSeekBarViewHolder(LayoutInflater.from(context).inflate(R.layout.item_settings_seekbar, parent, false))
                else -> throw IllegalArgumentException("Unknown view type: $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when (holder) {
                is HeaderViewHolder -> holder.bind(item as SettingItem.Header)
                is SeekBarViewHolder -> holder.bind(item as SettingItem.SeekBarItem)
                is FloatSeekBarViewHolder -> holder.bind(item as SettingItem.FloatSeekBarItem)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvTitle: TextView = view.findViewById(R.id.tv_header_title)
            fun bind(item: SettingItem.Header) {
                tvTitle.text = item.title
            }
        }

        inner class SeekBarViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvTitle: TextView = view.findViewById(R.id.tv_setting_title)
            private val tvSummary: TextView = view.findViewById(R.id.tv_setting_summary)
            private val tvValue: TextView = view.findViewById(R.id.tv_setting_value)
            private val seekBar: SeekBar = view.findViewById(R.id.seek_bar)

            fun bind(item: SettingItem.SeekBarItem) {
                tvTitle.text = item.title
                tvSummary.text = item.summary
                seekBar.max = item.max - item.min
                seekBar.progress = item.getValue() - item.min
                updateValueText(item.getValue())

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = progress + item.min
                        updateValueText(value)
                        if (fromUser) item.onChanged(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }

            private fun updateValueText(value: Int) {
                tvValue.text = value.toString()
            }
        }

        inner class FloatSeekBarViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvTitle: TextView = view.findViewById(R.id.tv_setting_title)
            private val tvSummary: TextView = view.findViewById(R.id.tv_setting_summary)
            private val tvValue: TextView = view.findViewById(R.id.tv_setting_value)
            private val seekBar: SeekBar = view.findViewById(R.id.seek_bar)

            fun bind(item: SettingItem.FloatSeekBarItem) {
                tvTitle.text = item.title
                tvSummary.text = item.summary
                val steps = ((item.max - item.min) / item.step).toInt()
                seekBar.max = steps
                val currentValue = item.getValue()
                seekBar.progress = ((currentValue - item.min) / item.step).toInt()
                updateValueText(currentValue)

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = item.min + progress * item.step
                        updateValueText(value)
                        if (fromUser) item.onChanged(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }

            private fun updateValueText(value: Float) {
                tvValue.text = String.format("%.3f", value)
            }
        }
    }
}