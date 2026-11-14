package org.vera20k

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Text for the files a player actually opens: configs, logs, readmes, mod lists.
 *
 * The file is read in pages, never whole. An earlier version slurped up to
 * 512 KB into a single `TextView`, which is what made a long log take seconds to
 * appear and could still exhaust the heap; now only the page on screen is
 * decoded, so the cost is flat whatever the file weighs. Paging is also what
 * makes editing honest: a file too large to hold is reported, not cut silently.
 *
 * Highlighting runs over the visible page only — spans across a megabyte are the
 * other half of the same performance problem.
 */
class TextViewerActivity : Activity() {
    private companion object {
        const val EXTRA_PATH = "path"

        /** One or two screenfuls; small enough that spans stay cheap. */
        const val PAGE_BYTES = 48L shl 10

        /** Above this the file is read-only: a full copy would not fit the heap. */
        const val EDITABLE_LIMIT = 2L shl 20

        val NUMBER = Regex("""\b\d{2,}\b""")
    }

    private lateinit var file: File
    private lateinit var body: TextView
    private lateinit var editor: EditText
    private lateinit var subtitle: TextView
    private lateinit var pageLabel: TextView
    private lateinit var editButton: TextView
    private var readScroll: ScrollView? = null

    private var encoding: Charset = Charsets.UTF_8
    private var pageIndex = 0
    private var pageCount = 1
    private var editing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COL_BG
        window.navigationBarColor = COL_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        val path = intent?.getStringExtra(EXTRA_PATH).orEmpty()
        file = File(path)
        if (!file.isFile) {
            finish()
            return
        }
        encoding = detectCharset()
        pageCount = ((file.length() + PAGE_BYTES - 1) / PAGE_BYTES).toInt().coerceAtLeast(1)
        setContentView(buildLayout())
        showPage(0)
    }

    // --- layout -------------------------------------------------------------

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(6))
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
        header.addView(back, LinearLayout.LayoutParams(dp(38), dp(38)))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        titles.addView(TextView(this).apply {
            text = file.name
            sp(16f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        subtitle = TextView(this).apply {
            sp(10f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(2), 0, 0)
        }
        titles.addView(subtitle)

        editButton = TextView(this).apply {
            text = "编辑"
            sp(12f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(pill(COL_SURFACE_HI, 12, COL_OUTLINE))
            setOnClickListener { toggleEditing() }
        }
        val find = TextView(this).apply {
            text = "查找"
            sp(12f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(pill(COL_SURFACE_HI, 12, COL_OUTLINE))
            setOnClickListener { showFind() }
        }
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(editButton, LinearLayout.LayoutParams(dp(52), dp(32)).apply { rightMargin = dp(6) })
        header.addView(find, LinearLayout.LayoutParams(dp(52), dp(32)))
        root.addView(header)

        val pager = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(0), dp(14), dp(6))
        }
        pageLabel = TextView(this).apply {
            sp(10f)
            setTextColor(COL_TEXT_FAINT)
        }
        val prev = TextView(this).apply {
            text = "‹ 上页"
            sp(11f)
            setTextColor(COL_TEXT)
            gravity = Gravity.CENTER
            background = ripple(pill(COL_SURFACE_HI, 10, COL_OUTLINE))
            setOnClickListener { showPage(pageIndex - 1) }
        }
        val next = TextView(this).apply {
            text = "下页 ›"
            sp(11f)
            setTextColor(COL_TEXT)
            gravity = Gravity.CENTER
            background = ripple(pill(COL_SURFACE_HI, 10, COL_OUTLINE))
            setOnClickListener { showPage(pageIndex + 1) }
        }
        pager.addView(pageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pager.addView(prev, LinearLayout.LayoutParams(dp(62), dp(28)).apply { rightMargin = dp(6) })
        pager.addView(next, LinearLayout.LayoutParams(dp(62), dp(28)))
        root.addView(pager)

        body = TextView(this).apply {
            sp(13f)
            setTextColor(COL_TEXT)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(8), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COL_BG)
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        readScroll = scroll

        editor = EditText(this).apply {
            sp(13f)
            setTextColor(COL_TEXT)
            setHintTextColor(COL_TEXT_FAINT)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(COL_BG)
            setPadding(dp(14), dp(8), dp(14), dp(28))
            gravity = Gravity.TOP
            visibility = View.GONE
        }

        root.addView(FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(editor, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return root
    }

    // --- reading ------------------------------------------------------------

    private fun showPage(index: Int) {
        val clamped = index.coerceIn(0, pageCount - 1)
        val offset = clamped.toLong() * PAGE_BYTES
        val bytes = readRange(offset, PAGE_BYTES)
        pageIndex = clamped
        body.text = highlight(String(bytes, encoding))
        subtitle.text = String.format(
            Locale.US, "%s · %s · 偏移 %d", sizeLabel(file.length()),
            if (file.length() > EDITABLE_LIMIT) "只读" else "可编辑", offset,
        )
        pageLabel.text = String.format(Locale.US, "第 %d / %d 页", clamped + 1, pageCount)
        editButton.visibility = if (file.length() <= EDITABLE_LIMIT) View.VISIBLE else View.GONE
        readScroll?.scrollTo(0, 0)
    }

    private fun readRange(offset: Long, length: Long): ByteArray {
        RandomAccessFile(file, "r").use { handle ->
            handle.seek(offset)
            val available = (handle.length() - offset).coerceIn(0, length)
            val bytes = ByteArray(available.toInt())
            handle.readFully(bytes)
            return bytes
        }
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    /**
     * Strict UTF-8 over the first 4 KB: retail INIs are ASCII or a legacy
     * double-byte codepage, and the decoder that throws is the cheap signal.
     */
    private fun detectCharset(): Charset {
        val probe = readRange(0, 4096)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(probe))
            Charsets.UTF_8
        } catch (_: Exception) {
            Charset.forName("GB18030")
        }
    }

    // --- highlighting -------------------------------------------------------

    private fun highlight(text: String): CharSequence {
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.US)
        val spannable = SpannableString(text)
        var cursor = 0
        for (line in text.lines()) {
            val start = cursor
            cursor += line.length + 1
            if (start >= text.length) break
            val trimmed = line.trimStart()
            val lineStart = (start + (line.length - trimmed.length)).coerceAtMost(text.length)
            val lineEnd = (start + line.length).coerceAtMost(text.length)

            when {
                // [Section] headings are what the eye looks for in an INI.
                trimmed.startsWith("[") && trimmed.contains(']') -> {
                    val end = (lineStart + trimmed.indexOf(']') + 1).coerceAtMost(text.length)
                    spannable.setSpan(
                        ForegroundColorSpan(COL_ACCENT), lineStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD), lineStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                trimmed.startsWith(";") || trimmed.startsWith("#") || trimmed.startsWith("//") -> {
                    spannable.setSpan(
                        ForegroundColorSpan(COL_TEXT_FAINT), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                else -> {
                    if (ext in setOf("ini", "toml", "cfg", "conf")) {
                        val separator = trimmed.indexOf('=')
                        if (separator > 0) {
                            val keyEnd = (lineStart + separator).coerceAtMost(text.length)
                            spannable.setSpan(
                                ForegroundColorSpan(0xFF8BC8EA.toInt()),
                                lineStart, keyEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                    // Numbers read faster when they stand apart from the text.
                    for (match in NUMBER.findAll(line)) {
                        val from = (start + match.range.first).coerceAtMost(text.length)
                        val to = (start + match.range.last + 1).coerceAtMost(text.length)
                        if (from < to) {
                            spannable.setSpan(
                                ForegroundColorSpan(COL_GOOD), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }
            }
        }
        return spannable
    }

    // --- editing ------------------------------------------------------------

    private fun toggleEditing() {
        if (file.length() > EDITABLE_LIMIT) {
            Toast.makeText(this, "超过 2 MB，请在电脑上修改", Toast.LENGTH_SHORT).show()
            return
        }
        if (!editing) {
            val content = try {
                String(readRange(0, file.length()), encoding)
            } catch (error: Exception) {
                Toast.makeText(this, "读不出来：${error.message}", Toast.LENGTH_SHORT).show()
                return
            }
            editor.setText(content)
            editor.visibility = View.VISIBLE
            readScroll?.visibility = View.GONE
            editing = true
            editButton.text = "保存"
            editor.requestFocus()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        } else {
            save()
        }
    }

    private fun save() {
        val text = editor.text?.toString() ?: return
        AlertDialog.Builder(this)
            .setTitle("写回 ${file.name}？")
            .setMessage("以 ${encoding.name()} 编码覆盖原文件，共 ${text.length} 字符。")
            .setPositiveButton("保存") { _, _ ->
                Thread {
                    val outcome = try {
                        file.writeText(text, encoding)
                        "已保存"
                    } catch (error: Exception) {
                        "保存失败：${error.message}"
                    }
                    runOnUiThread {
                        Toast.makeText(this, outcome, Toast.LENGTH_SHORT).show()
                        if (outcome == "已保存") {
                            editing = false
                            editor.visibility = View.GONE
                            readScroll?.visibility = View.VISIBLE
                            editButton.text = "编辑"
                            pageCount = ((file.length() + PAGE_BYTES - 1) / PAGE_BYTES)
                                .toInt().coerceAtLeast(1)
                            showPage(pageIndex)
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // --- search -------------------------------------------------------------

    /**
     * Finds the page holding the query by streaming the file: nothing is decoded
     * until the matching page is shown, so a search over a large log is one pass
     * of byte comparisons.
     */
    private fun showFind() {
        val input = EditText(this).apply {
            hint = "要查找的文字"
            sp(14f)
        }
        AlertDialog.Builder(this)
            .setTitle("在当前文件里查找")
            .setView(input)
            .setPositiveButton("查找") { _, _ ->
                val query = input.text?.toString().orEmpty()
                if (query.isEmpty()) return@setPositiveButton
                hideKeyboard(input)
                Thread {
                    val found = scan(query)
                    runOnUiThread {
                        if (found < 0) {
                            Toast.makeText(this, "没有找到「$query」", Toast.LENGTH_SHORT).show()
                        } else {
                            showPage((found / PAGE_BYTES).toInt())
                            Toast.makeText(this, "在第 ${found / PAGE_BYTES + 1} 页附近", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Byte offset of the first match, or -1. */
    private fun scan(query: String): Long {
        val needle = query.toByteArray(encoding)
        if (needle.isEmpty()) return -1
        BufferedInputStream(file.inputStream(), 1 shl 16).use { stream ->
            var offset = 0L
            val window = ByteArray(maxOf(needle.size * 4, 8192))
            while (true) {
                val read = stream.read(window)
                if (read <= 0) break
                for (i in 0..(read - needle.size)) {
                    var hit = true
                    for (j in needle.indices) {
                        if (window[i + j] != needle[j]) {
                            hit = false
                            break
                        }
                    }
                    if (hit) return offset + i
                }
                offset += read
            }
        }
        return -1
    }

    private fun hideKeyboard(view: EditText) {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
