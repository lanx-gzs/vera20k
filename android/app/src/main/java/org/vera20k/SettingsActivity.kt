package org.vera20k

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * This build's own switches: what the browser shows, which decoder a text file
 * gets, whether tapping the icon should go straight to the game, and a way to
 * reclaim the cache.
 *
 * They live in SharedPreferences rather than the engine's `config.toml` — the
 * engine owns that file, and rewriting it here would race the import flow.
 */
class SettingsActivity : Activity() {
    private lateinit var cacheRow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COL_BG
        window.navigationBarColor = COL_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        setContentView(buildLayout())
        refreshCacheSize()
    }

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(18), dp(6))
        }
        val back = TextView(this).apply {
            text = "‹"
            sp(22f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(square(COL_SURFACE_HI, 11))
            contentDescription = "返回"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "设置"
            sp(17f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(back, LinearLayout.LayoutParams(dp(38), dp(38)))
        header.addView(title)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(26))
        }

        // 浏览
        content.addView(sectionLabel("浏览"))
        val browseCard = card()
        browseCard.addView(switchRow(
            "显示隐藏文件",
            Prefs.getBoolean(this, Prefs.SHOW_HIDDEN, false),
        ) { value -> Prefs.setBoolean(this, Prefs.SHOW_HIDDEN, value) })
        browseCard.addView(divider())
        browseCard.addView(switchRow(
            "列表显示文件大小",
            Prefs.getBoolean(this, Prefs.SHOW_SIZE, true),
        ) { value -> Prefs.setBoolean(this, Prefs.SHOW_SIZE, value) })
        browseCard.addView(divider())
        val encodingRow = valueRow(
            "文本默认编码",
            Prefs.getString(this, Prefs.ENCODING, "UTF-8"),
        ) {
            val next = if (Prefs.getString(this, Prefs.ENCODING, "UTF-8") == "UTF-8") "GBK" else "UTF-8"
            Prefs.setString(this, Prefs.ENCODING, next)
            (it as TextView).text = next
        }
        browseCard.addView(encodingRow)
        content.addView(browseCard)

        // 启动
        content.addView(sectionLabel("启动"))
        val startCard = card()
        startCard.addView(switchRow(
            "打开应用直接进游戏",
            Prefs.getBoolean(this, Prefs.SKIP_INSPECTOR, false),
        ) { value -> Prefs.setBoolean(this, Prefs.SKIP_INSPECTOR, value) })
        startCard.addView(divider())
        startCard.addView(hintRow("开启后跳过资源检查器。想检查文件时，从通知或最近任务里退出应用再关掉这个开关。"))
        content.addView(startCard)

        // 存储
        content.addView(sectionLabel("存储"))
        val storageCard = card()
        cacheRow = valueRow("清理缓存", "计算中…") { clearCache() }
        storageCard.addView(cacheRow)
        content.addView(storageCard)

        // 关于
        content.addView(sectionLabel("关于"))
        val aboutCard = card()
        aboutCard.addView(infoRow("版本", versionLabel()))
        aboutCard.addView(divider())
        aboutCard.addView(infoRow("包名", packageName))
        aboutCard.addView(divider())
        aboutCard.addView(infoRow("许可", "GPLv3 · 非官方修改版"))
        aboutCard.addView(divider())
        aboutCard.addView(hintRow(
            "本应用基于 VERA20K 修改，按 GPLv3 第 5(a) 条标注修改、第 6 条随附源码。\n" +
                "VERA20K 的名称与图标权属其作者，本改名版不使用其商标，仅作事实性署名。\n" +
                "源码：github.com/lanx-gzs/vera20k（分支 verazip）",
        ))
        aboutCard.addView(divider())
        aboutCard.addView(hintRow(
            "地图格式与编辑器参考：World-Altering Editor（GPLv3，\u00A9 Rami Pasanen / CnCNet）。" +
                "本应用未内嵌该编辑器，地图编辑请在 PC 上使用它；此处只借用了它文档记载的地图结构知识。",
        ))
        content.addView(aboutCard)

        val scroll = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return root
    }

    /** Version name and code straight from the package manager. */
    private fun versionLabel(): String = try {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} · ${info.longVersionCode}"
    } catch (_: Throwable) {
        "-"
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {        this.text = text
        sp(11f)
        setTextColor(COL_TEXT_FAINT)
        letterSpacing = 0.12f
        setPadding(dp(6), dp(16), 0, dp(7))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBg()
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun divider(): android.view.View = android.view.View(this).apply {
        setBackgroundColor(0xFF14171C.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
        ).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
        }
    }

    private fun switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(10), dp(6))
        }
        val label = TextView(this).apply {
            text = title
            sp(13.5f)
            setTextColor(COL_TEXT)
        }
        val toggle = Switch(this).apply {
            isChecked = checked
            buttonTintList = null
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(COL_ACCENT, 0xFF6B7178.toInt()),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x66EFB44C, 0xFF23262C.toInt()),
            )
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(toggle)
        return row
    }

    private fun valueRow(title: String, value: String, onClick: (TextView) -> Unit): TextView =
        TextView(this).apply {
            text = "$title    ·    $value"
            sp(13.5f)
            setTextColor(COL_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(15), dp(14), dp(15))
            background = ripple(square(0x00000000, 12))
            setOnClickListener { onClick(this) }
        }

    private fun infoRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        val name = TextView(this).apply {
            text = label
            sp(12f)
            setTextColor(COL_TEXT_DIM)
        }
        val valueView = TextView(this).apply {
            text = value
            sp(12f)
            setTextColor(COL_TEXT)
            gravity = Gravity.END
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueView)
        return row
    }

    private fun hintRow(text: String): TextView = TextView(this).apply {
        this.text = text
        sp(11f)
        setTextColor(COL_TEXT_DIM)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(14), dp(10), dp(14), dp(12))
    }

    private fun refreshCacheSize() {
        val cache = cacheDir
        Thread {
            val bytes = size(cache)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                cacheRow.text = "清理缓存    ·    " + formatSize(bytes)
            }
        }.start()
    }

    private fun clearCache() {
        val cache = cacheDir
        Thread {
            cache?.listFiles()?.forEach { it.deleteRecursively() }
            val bytes = size(cache)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                cacheRow.text = "清理缓存    ·    " + formatSize(bytes)
                android.widget.Toast.makeText(this, "缓存已清理", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun size(dir: File?): Long {
        val files = dir?.listFiles() ?: return 0
        var total = 0L
        for (file in files) {
            total += if (file.isDirectory) size(file) else file.length()
        }
        return total
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}
