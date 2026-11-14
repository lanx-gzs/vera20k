package org.vera20k

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The inside of a `.mix` archive.
 *
 * Retail MIX files keep no filenames: the index holds a 32-bit CRC of the
 * original name, an offset and a size, so a listing can show what is in there
 * and how big it is, but not what it was called. That is the format, not a
 * limitation of this screen — the note at the bottom says so.
 *
 * Archives nest (a `local.mix` inside `ra2md.mix` holds the mod's own files),
 * so an entry that begins with a MIX header opens in a new screen with the same
 * reader, one level down.
 */
class MixActivity : Activity() {
    private companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_PARENT = "parent"
        const val EXTRA_LABEL = "label"
    }

    private class Entry(val id: Int, val size: Long, val offset: Long, val nested: Boolean)

    private lateinit var file: File
    private var parentId = 0
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var emptyView: TextView
    private lateinit var listView: ListView
    private val entries = mutableListOf<Entry>()
    private var totalBytes = 0L

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = entries[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = entries[position]
            val row = LinearLayout(this@MixActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(16), dp(10))
            }
            val column = LinearLayout(this@MixActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            column.addView(TextView(this@MixActivity).apply {
                text = String.format(Locale.US, "0x%08X", entry.id)
                sp(13f)
                setTextColor(COL_TEXT)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            column.addView(TextView(this@MixActivity).apply {
                text = buildString {
                    append(formatSize(entry.size))
                    append(" · 偏移 ")
                    append(entry.offset)
                    if (entry.nested) append(" · 内层 MIX")
                }
                sp(10f)
                setTextColor(if (entry.nested) COL_ACCENT else COL_TEXT_DIM)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener { openEntry(entry) }
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COL_BG
        window.navigationBarColor = COL_BG
        val path = intent?.getStringExtra(EXTRA_PATH).orEmpty()
        parentId = intent?.getIntExtra(EXTRA_PARENT, 0) ?: 0
        file = File(path)
        if (!file.isFile) {
            finish()
            return
        }
        setContentView(buildLayout())
        loadEntries(intent?.getStringExtra(EXTRA_LABEL))
    }

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(16), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            sp(20f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(square(COL_SURFACE_HI, 11))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        title = TextView(this).apply {
            text = file.name
            sp(16f)
            setTextColor(COL_TEXT)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        subtitle = TextView(this).apply {
            text = "正在读取索引…"
            sp(10f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(3), 0, 0)
        }
        titles.addView(title)
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        emptyView = TextView(this).apply {
            text = "索引是空的"
            sp(13f)
            setTextColor(COL_TEXT_DIM)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            adapter = this@MixActivity.adapter
            setBackgroundColor(COL_BG)
        }
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        root.addView(emptyView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        val note = TextView(this).apply {
            text = "MIX 只存文件名的 CRC32，没有原名；条目按哈希排列。全部解包会把整个归档摊到磁盘上，注意空间。"
            sp(10f)
            setTextColor(COL_TEXT_FAINT)
            setPadding(dp(18), dp(6), dp(18), dp(6))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(note)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        bar.addView(TextView(this).apply {
            text = "全部解包"
            sp(13f)
            gravity = Gravity.CENTER
            setTextColor(COL_DARK_TEXT)
            background = ripple(pill(COL_ACCENT, 13))
            setOnClickListener { confirmExtractAll() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        bar.addView(TextView(this).apply {
            text = "关闭"
            sp(13f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(pill(COL_SURFACE_HI, 13, COL_OUTLINE))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(bar)
        return root
    }

    private fun loadEntries(label: String?) {
        if (parentId != 0) {
            title.text = label ?: String.format(Locale.US, "内层 MIX 0x%08X", parentId)
        }
        Thread {
            val json = MixBridge.entries(file.absolutePath, parentId)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (json == null) {
                    subtitle.text = "读不出来"
                    emptyView.visibility = View.VISIBLE
                    listView.visibility = View.GONE
                    return@runOnUiThread
                }
                if (!json.optBoolean("ok")) {
                    subtitle.text = json.optString("error", "读不出来")
                    emptyView.visibility = View.VISIBLE
                    listView.visibility = View.GONE
                    return@runOnUiThread
                }
                entries.clear()
                totalBytes = 0
                val array = json.optJSONArray("entries")
                for (index in 0 until (array?.length() ?: 0)) {
                    val item = array?.optJSONObject(index) ?: continue
                    val entry = Entry(
                        item.optInt("id"),
                        item.optLong("size"),
                        item.optLong("offset"),
                        item.optBoolean("nested"),
                    )
                    entries += entry
                    totalBytes += entry.size
                }
                adapter.notifyDataSetChanged()
                listView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
                emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                subtitle.text = String.format(
                    Locale.US, "%d 个条目 · %s · %s",
                    entries.size,
                    formatSize(totalBytes),
                    if (parentId == 0) "顶层" else "内层",
                )
            }
        }.start()
    }

    private fun openEntry(entry: Entry) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "解包到当前目录" to { promptExtract(entry) }
        if (entry.nested) {
            actions += "浏览内层 MIX" to {
                startActivity(
                    Intent(this, MixActivity::class.java)
                        .putExtra(EXTRA_PATH, file.absolutePath)
                        .putExtra(EXTRA_PARENT, entry.id)
                        .putExtra(EXTRA_LABEL, String.format(Locale.US, "内层 MIX 0x%08X", entry.id)),
                )
            }
        }
        actions += "复制条目标识" to {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("MIX", String.format(Locale.US, "0x%08X", entry.id)))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        darkSheet(
            title = String.format(Locale.US, "0x%08X", entry.id),
            subtitle = "${formatSize(entry.size)} · 偏移 ${entry.offset}" +
                if (entry.nested) "\n这个条目本身就是一个 MIX 归档" else "",
            actions = actions,
        )
    }

    private fun promptExtract(entry: Entry) {
        val nameField = EditText(this).apply {
            setText(defaultName(entry))
            setSingleLine(true)
            sp(13f)
            setTextColor(COL_TEXT)
            setHintTextColor(COL_TEXT_FAINT)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pill(COL_SURFACE_HI, 10, COL_OUTLINE)
        }
        darkSheet(
            title = "解包到当前目录",
            subtitle = "目标：${file.parent}\nMIX 里没有原文件名，扩展名按内容无法判断，默认给 .bin。",
            content = nameField,
            actions = listOf(
                "取消" to {},
                "解包" to {
                    val name = nameField.text?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                    } else {
                        extract(listOf(entry), File(file.parentFile, name))
                    }
                },
            ),
        )
    }

    private fun defaultName(entry: Entry): String =
        String.format(Locale.US, "0x%08X.bin", entry.id)

    private fun confirmExtractAll() {
        if (entries.isEmpty()) {
            Toast.makeText(this, "没有可解包的条目", Toast.LENGTH_SHORT).show()
            return
        }
        val folder = File(file.parentFile, file.nameWithoutExtension + "_unpacked")
        darkSheet(
            title = "全部解包",
            subtitle = String.format(
                Locale.US,
                "%d 个条目，共 %s，解包到\n%s",
                entries.size, formatSize(totalBytes), folder.absolutePath,
            ),
            actions = listOf(
                "取消" to {},
                "开始" to {
                    val jobs = entries.map { entry ->
                        entry to File(folder, defaultName(entry))
                    }
                    Thread {
                        var done = 0
                        for ((entry, target) in jobs) {
                            if (MixBridge.extract(file.absolutePath, parentId, entry.id, target.absolutePath)) {
                                done++
                            }
                        }
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "解包 $done / ${jobs.size} 到 ${folder.name}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }.start()
                },
            ),
        )
    }

    private fun extract(jobs: List<Entry>, target: File) {
        Thread {
            val ok = MixBridge.extract(file.absolutePath, parentId, jobs.first().id, target.absolutePath)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "已写到 ${target.name}" else "解包失败",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}
