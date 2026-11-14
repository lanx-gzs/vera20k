package org.vera20k

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * The map browser: every map file dropped into the installation, with the
 * extension resolved into what the game will actually do with it.
 *
 * The campaigns themselves are sealed inside `ra2.mix`, which stores no names,
 * so this screen can only show what a player put on disk — that is also the only
 * thing they can act on: `.map` (mission), `.mpr` (RA2 skirmish), `.yrm` (YR
 * skirmish), `.mmx` (compressed skirmish).
 */
class MapActivity : Activity() {
    private companion object {
        val EXTENSIONS = setOf("map", "mpr", "yrm", "mmx")
        const val MAX_RESULTS = 400
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var summary: TextView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COL_BG
        window.navigationBarColor = COL_BG

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(26))
            setBackgroundColor(COL_BG)
        }
        column.addView(buildHeader())

        empty = TextView(this).apply {
            text = "游戏目录里还没有地图文件。\n把 .yrm / .mpr / .map 放进目录，或用入口界面的导入按钮传进来。"
            sp(12f)
            setTextColor(COL_TEXT_FAINT)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(20), dp(34), dp(20), dp(34))
            visibility = TextView.GONE
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(buildSummary())
        column.addView(empty)
        column.addView(listContainer)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(COL_BG)
            addView(column)
        })
        scan()
    }

    private fun buildHeader(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }
        val back = TextView(this).apply {
            text = "‹"
            sp(20f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(square(COL_SURFACE_HI, 11))
            contentDescription = "返回"
            setOnClickListener { finish() }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "地图"
            sp(19f)
            setTextColor(COL_TEXT)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        titles.addView(TextView(this).apply {
            text = "游戏目录里的 .map / .mpr / .yrm / .mmx"
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(2), 0, 0)
        })
        val refresh = TextView(this).apply {
            text = "刷新"
            sp(12f)
            setTextColor(COL_TEXT)
            gravity = Gravity.CENTER
            background = ripple(pill(COL_SURFACE_HI, 12, COL_OUTLINE))
            setOnClickListener { scan() }
        }
        row.addView(back, LinearLayout.LayoutParams(dp(38), dp(38)))
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(refresh, LinearLayout.LayoutParams(dp(64), dp(34)))
        return row
    }

    private fun buildSummary(): TextView {
        summary = TextView(this).apply {
            text = "正在扫描…"
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, 0, 0, dp(10))
        }
        return summary
    }

    private var scanning = false

    private fun scan() {
        if (scanning) return
        scanning = true
        summary.text = "正在扫描…"
        listContainer.removeAllViews()
        empty.visibility = TextView.GONE

        Thread {
            val found = mutableListOf<File>()
            fun walk(dir: File, depth: Int) {
                if (depth > 6 || found.size >= MAX_RESULTS) return
                dir.listFiles()?.forEach { child ->
                    if (found.size >= MAX_RESULTS) return@forEach
                    if (child.isDirectory) walk(child, depth + 1)
                    else if (child.name.substringAfterLast('.', "").lowercase(Locale.US) in EXTENSIONS) {
                        found += child
                    }
                }
            }
            walk(Importers.gameDir(this), 0)

            runOnUiThread {
                scanning = false
                if (isFinishing) return@runOnUiThread
                summary.text = if (found.isEmpty()) {
                    "没有找到地图文件"
                } else {
                    String.format(Locale.US, "共 %d 个地图文件", found.size)
                }
                empty.visibility = if (found.isEmpty()) TextView.VISIBLE else TextView.GONE

                val byType = found.sortedWith(compareBy({ kindOrder(it.name) }, { it.name.lowercase() }))
                for (map in byType) listContainer.addView(buildRow(map))
            }
        }.start()
    }

    private fun kindOrder(name: String): Int = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "map" -> 0
        "yrm" -> 1
        "mpr" -> 2
        else -> 3
    }

    /** What the game does with this file, in the player's terms. */
    private fun describe(name: String): Pair<String, Int> =
        when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "map" -> "任务 / 战役地图" to COL_ACCENT
            "yrm" -> "尤里的复仇 · 遭遇战" to COL_GOOD
            "mpr" -> "红色警戒2 · 遭遇战" to 0xFF7FB3E8.toInt()
            "mmx" -> "压缩版遭遇战地图" to COL_TEXT_DIM
            else -> "地图" to COL_TEXT_DIM
        }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    /** The preview, its schematic and the fields the INI actually carried. */
    private fun buildPreviewCard(info: MapInfo): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(COL_SURFACE_HI, 14, COL_OUTLINE)
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }

        card.addView(MapPreviewView(this, info), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val rows = mutableListOf<Pair<String, String>>()
        if (info.parsed) {
            rows += "尺寸" to "${info.width} × ${info.height} 格"
            info.theater?.takeIf { it.isNotBlank() }?.let { rows += "剧场" to it }
            info.name?.takeIf { it.isNotBlank() }?.let { rows += "内部名" to it }
            rows += "开始点" to if (info.starts.isEmpty()) "未标注" else "${info.starts.size} 个（按序号着色）"
            rows += "步兵 / 单位" to "${info.infantry} / ${info.units}"
            rows += "建筑 / 飞机" to "${info.structures} / ${info.aircraft}"
            rows += "地形物件" to "${info.terrain} 个"
            rows += "触发器 / 标签" to "${info.triggers} / ${info.tags}"
            rows += "参战方" to "${info.houses} 个房屋条目"
            info.lighting?.takeIf { it.isNotBlank() }?.let { rows += "光照 Ambient" to it }
            rows += "段落" to "${info.sections.size} 段"
        } else {
            info.note?.let { rows += "说明" to it }
        }
        for ((key, value) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = key
                sp(11f)
                setTextColor(COL_TEXT_DIM)
                layoutParams = LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = value
                sp(11f)
                setTextColor(COL_TEXT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
        }
        card.addView(TextView(this).apply {
            text = "缩略图是位置与规模示意，不含地形贴图：贴图在 ra2.mix / ra2md.mix 里，属游戏资源，本应用不随包分发。\n地形渲染需要解析 mix + tmp + 调色板，属于下一步；编辑请用 PC 端的 World-Altering Editor（GPLv3）。"
            sp(10f)
            setTextColor(COL_TEXT_FAINT)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        })
        return card
    }

    private fun buildRow(map: File): LinearLayout {
        val (label, color) = describe(map.name)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pill(COL_SURFACE, 14, COL_OUTLINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                // Reading a map's INI can take a moment on a big file; build
                // the card off the UI thread, then show it.
                Thread {
                    val info = MapFile.read(map)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        darkSheet(
                            title = map.name,
                            subtitle = buildString {
                                append("类型  ").append(label).append('\n')
                                append("大小  ").append(formatSize(map.length())).append('\n')
                                append("路径  ").append(map.absolutePath)
                            },
                            actions = listOf("关闭" to {}),
                            content = buildPreviewCard(info),
                        )
                    }
                }.start()
            }
        }

        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_folder_tool)
            setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        column.addView(TextView(this).apply {
            text = map.name
            sp(13f)
            setTextColor(COL_TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        meta.addView(TextView(this).apply {
            text = label
            sp(10f)
            setTextColor(color)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = pill(0x14FFFFFF, 7, 0x22FFFFFF)
        })
        meta.addView(TextView(this).apply {
            text = formatSize(map.length())
            sp(10f)
            setTextColor(COL_TEXT_FAINT)
            setPadding(dp(8), 0, 0, 0)
        })
        column.addView(meta)

        row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
