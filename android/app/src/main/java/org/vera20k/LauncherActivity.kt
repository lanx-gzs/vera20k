package org.vera20k

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The unofficial build's entry screen: a file manager for the imported game
 * files, a status card for the two archives the engine opens, and the button
 * that hands over to the engine.
 *
 * The engine starts straight into its own shell, which leaves a player no way to
 * see — or fix — what the import put on the phone. This screen answers that:
 * browse, view a text file or a PNG, import loose files or unzip an archive,
 * rename, move, copy and delete, then launch [VeraActivity].
 *
 * It also exists because this is a modified build: the chip under the title is
 * the GPLv3 §5(a) "modified version" statement, kept in front of the player
 * instead of buried in a file.
 */
class LauncherActivity : Activity() {
    private companion object {
        /** The engine opens these directly; without them it cannot start a game. */
        val REQUIRED_ARCHIVES = listOf("ra2md.mix", "ra2.mix")

        const val TAG = "VeraZip"
        const val REQUEST_IMPORT_FILES = 0x5654
        const val REQUEST_IMPORT_ZIP = 0x5655
        const val REQUEST_IMPORT_TREE = 0x5656

        /** Mods: a ZIP to unpack into `Mods`, and a folder to copy in. */
        const val REQUEST_MOD_ZIP = 0x5657
        const val REQUEST_MOD_TREE = 0x5658

        /** A ZIP / 7z to unpack into private storage, then use as game or mod. */
        const val REQUEST_ARCHIVE = 0x5659

        /** The in-app site browser; it returns the file it downloaded. */
        const val REQUEST_BROWSER = 0x5660

        /** What the archive picker offers; the format is sniffed, not trusted. */
        val ARCHIVE_MIME_TYPES = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-zip",
            "application/x-7z-compressed",
            "application/octet-stream",
        )
    }

    private lateinit var gameRoot: File
    private var currentDir: File? = null

    /** Scanner state, so a slow walk cannot repaint a folder the player left. */
    private var scanToken = 0

    private val entries = mutableListOf<File>()

    /** The directory as read; [entries] is what the search and sort left of it. */
    private val allEntries = mutableListOf<File>()
    private var query = ""
    private var sortByDate = false
    private lateinit var searchField: EditText
    private lateinit var sortButton: TextView
    private lateinit var adapter: EntryAdapter
    private lateinit var listView: ListView
    private lateinit var emptyView: View

    private lateinit var statusBadge: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var sizeValue: TextView
    private lateinit var sizeCount: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var pathView: TextView
    private lateinit var upButton: TextView
    private lateinit var enterButton: TextView
    private lateinit var heroSub: TextView

    /** The resource half of the hero subtitle; the mod suffix follows it. */
    private var heroBase = ""

    private var activeTab = 0
    private lateinit var tabHome: View
    private lateinit var tabFiles: View
    private lateinit var tabMods: View
    private lateinit var tabMine: View
    private lateinit var modList: LinearLayout
    private lateinit var modStatusTitle: TextView
    private lateinit var modStatusSub: TextView

    /** Rows whose file count is still being walked, refreshed off the UI thread. */
    private val modMetaRows = mutableListOf<Triple<String, File, TextView>>()
    private var modScanToken = 0
    private val navIcons = mutableListOf<ImageView>()
    private val navLabels = mutableListOf<TextView>()

    private var progressDialog: Dialog? = null
    private var progressText: TextView? = null
    private lateinit var overlayHost: FrameLayout
    private var wizard: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COL_BG
        window.navigationBarColor = COL_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        gameRoot = Importers.gameDir(this)
        currentDir = gameRoot
        // The engine only looks in `ra2_dir` itself, never below it, and only
        // after config.toml names that folder: adopt the real one before any
        // check, so a folder nested one level down is not read as "missing".
        alignGameDir()

        // Opt-in shortcut for players who only ever launch the game — but only
        // once the files are there: launching into a missing installation is a
        // black screen, which is exactly what the wizard exists to prevent.
        if (Prefs.getBoolean(this, Prefs.SKIP_INSPECTOR, false) && archivesPresent(gameRoot)) {
            launchGame()
            return
        }

        setContentView(buildLayout())
        showDirectory(gameRoot)
        showTab(0)
        // Nothing to browse until the two archives are in place: lead with the
        // wizard instead of showing an empty folder and a red status card.
        if (!archivesPresent(gameRoot)) showWizard()
    }

    // --- layout -------------------------------------------------------------

    /**
     * The engine's own Mods page can also change the choice, so the launcher
     * re-reads it whenever it comes back in front.
     */
    override fun onResume() {
        super.onResume()
        if (::modList.isInitialized) refreshMods()
        updateHeroSub()
    }

    /** Resource state plus the mod the next launch plays, on the hero card. */
    private fun updateHeroSub() {
        if (!::heroSub.isInitialized || heroBase.isEmpty()) return
        val mod = Importers.activeMod(this)
        heroSub.text = heroBase + if (mod != null) " · 模组 $mod" else ""
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        tabHome = buildHomeTab()
        tabFiles = buildFilesTab()
        tabMods = buildModsTab()
        tabMine = buildMineTab()

        val host = FrameLayout(this).apply {
            addView(tabHome, full())
            addView(tabFiles, full())
            addView(tabMods, full())
            addView(tabMine, full())
        }
        root.addView(host, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        root.addView(buildBottomBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // The wizard is a full-screen layer rather than a second activity, so a
        // failed import can fall back to the folder it was already browsing.
        overlayHost = FrameLayout(this)
        overlayHost.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return overlayHost
    }

    private fun full() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    /** 0 首页, 1 文件, 2 模组, 3 我的. One content pane at a time. */
    private fun showTab(index: Int) {
        activeTab = index
        tabHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabFiles.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabMods.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabMine.visibility = if (index == 3) View.VISIBLE else View.GONE
        for (i in 0 until 4) {
            val colour = if (i == index) COL_ACCENT else COL_TEXT_DIM
            navIcons.getOrNull(i)?.setColorFilter(colour, PorterDuff.Mode.SRC_IN)
            navLabels.getOrNull(i)?.setTextColor(colour)
        }
        // Coming back to the browser after an import elsewhere should show that
        // import rather than a list read before it happened.
        if (index == 1) currentDir?.let { showDirectory(it) }
        if (index == 2) refreshMods()
    }

    private fun buildHomeTab(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildHeader())
        column.addView(buildStatusCard())
        column.addView(buildHeroCard())
        column.addView(buildEntryRow())
        column.addView(buildQuickRow())
        return ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(column, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    /** The one warm surface here: launching is the only thing this screen is for. */
    private fun buildHeroCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF27221A.toInt(), 0xFF14161A.toInt()),
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x40EFB44C)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = dp(12)
            }
        }
        card.addView(TextView(this).apply {
            text = "开始游戏"
            sp(21f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.02f
        })
        heroSub = TextView(this).apply {
            text = "RA2: YR 引擎 · 正在检查资源"
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(heroSub)
        enterButton = TextView(this).apply {
            text = "进入游戏"
            sp(16f)
            setTextColor(COL_DARK_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
            background = ripple(accentButton(26), 0x40FFFFFF)
            setOnClickListener { launchGame() }
        }
        card.addView(enterButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50),
        ).apply { topMargin = dp(14) })
        return card
    }

    /** Import and rescan, as two equal pills rather than a footer toolbar. */
    private fun buildQuickRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
        }
        val import = TextView(this).apply {
            text = "导入资源"
            sp(13f)
            gravity = Gravity.CENTER
            setTextColor(COL_ACCENT)
            background = ripple(pill(COL_SURFACE, 14, 0x33EFB44C), 0x26FFFFFF)
            setOnClickListener { showImportSheet() }
        }
        val rescan = TextView(this).apply {
            text = "重新检查"
            sp(13f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(pill(COL_SURFACE_HI, 14, COL_OUTLINE))
            setOnClickListener { currentDir?.let { showDirectory(it) } }
        }
        row.addView(import, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(10) })
        row.addView(rescan, LinearLayout.LayoutParams(0, dp(46), 1f))
        return row
    }

    private fun buildFilesTab(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = "文件"
            sp(19f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(14), dp(18), dp(0))
        })
        column.addView(buildSearchBar())
        column.addView(buildPathBar())
        column.addView(buildListArea(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        column.addView(buildFooter())
        return column
    }

    // --- mods ---------------------------------------------------------------

    /**
     * VERA's own Mods page is shaped for a desktop: it adds mods through a
     * native file chooser, shows the folder in a file manager, and switches the
     * choice with a restart — none of which exists on a phone. This tab does the
     * same work with what Android does have: it fills `Mods` through the
     * Storage Access Framework and writes the one `[paths] mod` line the engine
     * reads at start-up, so the engine's own loading is untouched.
     */
    private fun buildModsTab(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = "模组"
            sp(19f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(14), dp(18), dp(2))
        })

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = cardBg()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = dp(10)
            }
        }
        modStatusTitle = TextView(this).apply {
            text = "正在读取…"
            sp(14f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        modStatusSub = TextView(this).apply {
            text = "选择只在进入游戏时生效"
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(4), 0, 0)
        }
        status.addView(modStatusTitle)
        status.addView(modStatusSub)
        column.addView(status)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(2))
        }
        actions.addView(modAction("导入压缩包") { startModZip() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        actions.addView(modAction("导入文件夹") { startModTree() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        actions.addView(modAction("玩原版") { disableMod() },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        column.addView(actions)

        modList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }
        column.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(modList, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    private fun modAction(label: String, run: () -> Unit): TextView = TextView(this).apply {
        text = label
        sp(12f)
        gravity = Gravity.CENTER
        setTextColor(COL_ACCENT)
        background = ripple(pill(COL_SURFACE, 14, 0x33EFB44C), 0x26FFFFFF)
        setOnClickListener { run() }
    }

    /** Re-reads `Mods` and the engine's choice; nothing is cached across tabs. */
    private fun refreshMods() {
        if (!::modList.isInitialized) return
        val modsDir = Importers.modsDir(this)
        val active = Importers.activeMod(this)
        val folders = (modsDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase(Locale.US) }
        val activeDir = active?.let { name -> folders.firstOrNull { it.name.equals(name, true) } }
        modStatusTitle.text = when {
            active == null -> "当前：原版游戏"
            activeDir == null -> "当前：原版游戏 · 找不到「$active」"
            else -> "当前：" + modTitle(activeDir)
        }
        modStatusSub.text = when {
            active == null -> "在下面的模组上点「启用」，再进入游戏"
            activeDir == null -> "配置里写的文件夹已经不在 Mods 里，点「玩原版」清掉"
            else -> "已启用 · 下次进入游戏生效"
        }

        modList.removeAllViews()
        modMetaRows.clear()
        val token = ++modScanToken
        if (folders.isEmpty()) {
            modList.addView(
                hintCard(
                    "还没有模组",
                    "用上面的「导入 ZIP」或「导入文件夹」放一个进来。一个模组就是 Mods 里的一个文件夹，" +
                        "里面放它要覆盖的 rulesmd.ini / artmd.ini 等文件，可以再加一个 mod.ini 写名字。",
                ),
            )
            return
        }
        for (dir in folders) {
            modList.addView(modRow(dir, dir === activeDir))
        }
        // File counts are walked off the UI thread: a total-conversion mod is
        // hundreds of megabytes in thousands of files.
        Thread {
            for ((_, dir, meta) in modMetaRows.toList()) {
                val (files, bytes) = countTree(dir)
                runOnUiThread {
                    if (token != modScanToken || isFinishing) return@runOnUiThread
                    meta.text = modMetaText(dir, files, bytes)
                }
            }
        }.start()
    }

    private fun modRow(dir: File, active: Boolean): View {
        val title = TextView(this).apply {
            text = modTitle(dir)
            sp(13f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = TextView(this).apply {
            text = modMetaText(dir, -1, 0)
            sp(10f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(3), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        modMetaRows += Triple(dir.name, dir, meta)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(10), 0)
        }
        column.addView(title)
        column.addView(meta)

        val badge = TextView(this).apply {
            text = if (active) "使用中" else "启用"
            sp(10f)
            gravity = Gravity.CENTER
            setTextColor(if (active) COL_DARK_TEXT else COL_ACCENT)
            background = pill(
                if (active) COL_ACCENT else Color.TRANSPARENT,
                9,
                if (active) 0 else 0x33EFB44C,
            )
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ripple(cardBg())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { showModSheet(dir, active) }
            setOnLongClickListener {
                showModSheet(dir, active)
                true
            }
            addView(column, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ))
            addView(badge, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun hintCard(title: String, body: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBg()
            addView(TextView(this@LauncherActivity).apply {
                text = title
                sp(13f)
                setTextColor(COL_TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@LauncherActivity).apply {
                text = body
                sp(11f)
                setTextColor(COL_TEXT_DIM)
                setLineSpacing(dp(2).toFloat(), 1.05f)
                setPadding(0, dp(5), 0, 0)
            })
        }

    /** The name the Mods page shows: `mod.ini`'s `Name=`, else the folder. */
    private fun modTitle(dir: File): String =
        modIniValues(dir)["name"]?.takeIf { it.isNotBlank() } ?: dir.name

    /** Author, version and size on one line; `files < 0` means still counting. */
    private fun modMetaText(dir: File, files: Int, bytes: Long): String {
        val info = modIniValues(dir)
        val parts = mutableListOf<String>()
        info["author"]?.takeIf { it.isNotBlank() }?.let { parts += it }
        info["version"]?.takeIf { it.isNotBlank() }?.let { parts += "v$it" }
        if (files < 0) {
            parts += "统计中…"
        } else {
            parts += "$files 个文件"
            if (bytes > 0) parts += formatSize(bytes)
        }
        return parts.joinToString(" · ")
    }

    /** `mod.ini`'s `[Mod]` keys, lower-cased; an absent file is an empty map. */
    private fun modIniValues(dir: File): Map<String, String> {
        val file = dir.listFiles()?.firstOrNull { it.isFile && it.name.equals("mod.ini", true) }
            ?: return emptyMap()
        val values = HashMap<String, String>()
        try {
            var section = ""
            file.forEachLine { raw ->
                val line = raw.substringBefore(';').trim()
                if (line.startsWith("[")) {
                    section = line.removePrefix("[").substringBefore(']').trim().lowercase(Locale.US)
                    return@forEachLine
                }
                if (section != "mod") return@forEachLine
                val equals = line.indexOf('=')
                if (equals <= 0) return@forEachLine
                values[line.substring(0, equals).trim().lowercase(Locale.US)] =
                    line.substring(equals + 1).trim()
            }
        } catch (_: Throwable) {
            return values
        }
        return values
    }

    private fun countTree(root: File): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) walk(child) else {
                    files++
                    bytes += child.length()
                }
            }
        }
        walk(root)
        return Pair(files, bytes)
    }

    /** Tap or long-press on a row: enable, read it, or take it away. */
    private fun showModSheet(dir: File, active: Boolean) {
        val actions = mutableListOf<SheetAction>()
        if (active) {
            actions += SheetAction("停用 · 玩原版") { disableMod() }
        } else {
            actions += SheetAction("启用（下次进入游戏生效）") { enableMod(dir.name) }
        }
        actions += SheetAction("详情") { showModDetails(dir) }
        actions += SheetAction("删除", danger = true) { confirmDeleteMod(dir, active) }
        showSheet(
            modTitle(dir),
            dir.name + " · " + if (active) "使用中" else "未启用",
            actions,
        )
    }

    private fun showModDetails(dir: File) {
        val info = modIniValues(dir)
        val (files, bytes) = countTree(dir)
        val lines = mutableListOf<String>()
        info["author"]?.takeIf { it.isNotBlank() }?.let { lines += "作者：$it" }
        info["version"]?.takeIf { it.isNotBlank() }?.let { lines += "版本：$it" }
        info["needs"]?.takeIf { it.isNotBlank() }?.let { lines += "要求引擎：$it" }
        info["about"]?.takeIf { it.isNotBlank() }?.let { lines += "\n$it" }
        info["terms"]?.takeIf { it.isNotBlank() }?.let { lines += "\n使用条款：$it" }
        lines += "\n$files 个文件 · " + formatSize(bytes)
        lines += "文件夹：Mods/" + dir.name
        showSheet(
            modTitle(dir),
            lines.joinToString("\n"),
            listOf(SheetAction("关闭") {}),
        )
    }

    private fun enableMod(folder: String) {
        if (Importers.activeMod(this).equals(folder, ignoreCase = true)) {
            toast("「$folder」已经启用")
            return
        }
        if (!Importers.setActiveMod(this, folder)) {
            toast("写入 config.toml 失败：先把游戏资源导入进来")
            return
        }
        refreshMods()
        updateHeroSub()
        toast("已启用「$folder」，进入游戏后生效")
    }

    private fun disableMod() {
        if (Importers.activeMod(this) == null) {
            toast("现在就是原版游戏")
            return
        }
        if (!Importers.setActiveMod(this, null)) {
            toast("写入 config.toml 失败")
            return
        }
        refreshMods()
        updateHeroSub()
        toast("已切回原版游戏")
    }

    private fun confirmDeleteMod(dir: File, active: Boolean) {
        showSheet(
            "删除「${modTitle(dir)}」？",
            if (active) "它正在使用中，删除后会自动切回原版游戏。" else "这会连同文件夹一起从 Mods 里移除。",
            listOf(
                SheetAction("删除", danger = true) {
                    if (active) Importers.setActiveMod(this, null)
                    val removed = deleteTree(dir)
                    refreshMods()
                    updateHeroSub()
                    toast(if (removed) "已删除「${dir.name}」" else "有文件删不掉，请重试")
                },
                SheetAction("取消") {},
            ),
        )
    }

    private fun startModZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, ARCHIVE_MIME_TYPES)
        }
        startActivityForResult(intent, REQUEST_MOD_ZIP)
    }

    private fun startModTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_MOD_TREE)
    }

    /** A ZIP or 7z becomes one folder in `Mods`, named after the archive. */
    private fun importModZip(uri: Uri) {
        val label = Importers.displayName(this, uri).substringBeforeLast('.')
        val counter = progress("正在导入模组…")
        Thread {
            val staging = File(
                Importers.modsDir(this),
                ".import-" + System.currentTimeMillis(),
            )
            val message = try {
                Importers.extractArchive(this, uri, staging) { files, bytes ->
                    if (files < 4 || files % 50 == 0) {
                        counter("已解压 $files 个文件\n" + formatSize(bytes))
                    }
                }
                finishModImport(staging, label)
            } catch (error: Throwable) {
                Importers.deleteTree(staging)
                "导入失败：" + (error.message ?: "压缩包可能已损坏或被加密")
            }
            runOnUiThread {
                dismissProgress()
                toast(message)
                refreshMods()
            }
        }.start()
    }

    /** A picked folder is copied in whole, named after the folder. */
    private fun importModTree(uri: Uri) {
        val label = Importers.displayName(this, uri)
        val counter = progress("正在复制模组…")
        Thread {
            val staging = File(
                Importers.modsDir(this),
                ".import-" + System.currentTimeMillis(),
            )
            val message = try {
                Importers.copyTree(this, uri, staging) { files, bytes ->
                    if (files < 4 || files % 50 == 0) {
                        counter("已复制 $files 个文件\n" + formatSize(bytes))
                    }
                }
                finishModImport(staging, label)
            } catch (error: Throwable) {
                Importers.deleteTree(staging)
                "导入失败：" + (error.message ?: "无法读取所选文件夹")
            }
            runOnUiThread {
                dismissProgress()
                toast(message)
                refreshMods()
            }
        }.start()
    }

    /**
     * Moves what was unpacked into its own folder in `Mods`. A ZIP made on a
     * desktop usually carries one wrapper folder, and a mod dropped into
     * `Mods` under that wrapper would never be read: `collapseSingleFolder`
     * takes the real root out of it first.
     */
    private fun finishModImport(staging: File, label: String): String {
        val root = Importers.collapseSingleFolder(staging)
        val target = Importers.uniqueDir(
            Importers.modsDir(this),
            Importers.plainFolderName(label),
        )
        if (!Importers.moveTree(root, target)) {
            throw java.io.IOException("无法把文件移进 Mods")
        }
        // A zip made on a Mac leaves __MACOSX beside the real files; it is not
        // part of the mod and the engine would list it as one more folder.
        Importers.stripArchiveNoise(target)
        if (root != staging) Importers.deleteTree(staging)
        return "已导入「${target.name}」\n点它启用，再进入游戏生效"
    }

    private fun buildMineTab(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = "我的"
            sp(19f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(14), dp(18), dp(2))
        })

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = dp(10)
            }
        }
        tools.addView(mineRow(R.drawable.ic_settings, "设置", "导入方式、引擎参数、跳过检查器") {
            startActivity(Intent(this@LauncherActivity, SettingsActivity::class.java))
        })
        tools.addView(divider())
        tools.addView(mineRow(R.drawable.ic_tools, "战役浏览", "4 个战役 · 关卡名") {
            startActivity(Intent(this@LauncherActivity, CampaignActivity::class.java))
        })
        tools.addView(divider())
        tools.addView(mineRow(R.drawable.ic_folder_tool, "地图浏览", ".map / .mpr / .yrm 缩略图") {
            startActivity(Intent(this@LauncherActivity, MapActivity::class.java))
        })
        tools.addView(divider())
        // The community site players already use for maps and mods, opened in
        // the app's own browser so a download comes straight back here.
        tools.addView(
            mineRow(
                R.drawable.ic_import,
                "资源站",
                "mycnc.net · 应用内浏览，下载后直接导入",
            ) { openResourceSite() },
        )
        column.addView(tools)

        val about = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBg()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        }
        about.addView(TextView(this).apply {
            text = "VeraZip 0.4.1"
            sp(14f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        about.addView(TextView(this).apply {
            text = "org.verazip · arm64-v8a · 非官方修改版"
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(4), 0, dp(10))
        })
        about.addView(TextView(this).apply {
            text = "本应用基于 VERA20K 修改，按 GPLv3 第 5(a) 条标注修改、第 6 条随附源码。" +
                "VERA20K 的名称与图标权属其作者，本改名版不使用其商标，仅作事实性署名。\n" +
                "源码：github.com/lanx-gzs/vera20k（分支 verazip）"
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setLineSpacing(dp(3).toFloat(), 1.05f)
        })
        column.addView(about)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(column, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun mineRow(icon: Int, title: String, sub: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener { onClick() }
            background = ripple(square(Color.TRANSPARENT, 0))
            val image = ImageView(this@LauncherActivity).apply {
                setImageResource(icon)
                setColorFilter(COL_ACCENT, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val column = LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
            }
            column.addView(TextView(this@LauncherActivity).apply {
                text = title
                sp(13f)
                setTextColor(COL_TEXT)
            })
            column.addView(TextView(this@LauncherActivity).apply {
                text = sub
                sp(10f)
                setTextColor(COL_TEXT_DIM)
                setPadding(0, dp(2), 0, 0)
            })
            val chevron = ImageView(this@LauncherActivity).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(COL_TEXT_FAINT, PorterDuff.Mode.SRC_IN)
            }
            addView(image, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(14), dp(14)))
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0x14FFFFFF)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
        )
    }

    /**
     * Opens the community resource site in the app's own browser.
     *
     * In-app on purpose: the download then lands in `filesDir`, and comes
     * straight back here so the player can say whether it is the game or a
     * mod — no trip through the phone's Download folder, no file manager.
     * The site itself is run by other people; this app hosts none of it.
     */
    private fun openResourceSite() {
        startActivityForResult(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_URL, BrowserActivity.START_URL),
            REQUEST_BROWSER,
        )
    }

    /** A floating pill over the content, the shape modern phone apps settled on. */
    private fun buildBottomBar(): View {
        val wrap = FrameLayout(this).apply {
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = pill(COL_SURFACE, 26, COL_OUTLINE)
        }
        bar.addView(navItem(R.drawable.ic_home, "首页", 0),
            LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(navItem(R.drawable.ic_folder, "文件", 1),
            LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(navItem(R.drawable.ic_tools, "模组", 2),
            LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(navItem(R.drawable.ic_person, "我的", 3),
            LinearLayout.LayoutParams(0, dp(52), 1f))
        wrap.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(64),
        ))
        return wrap
    }

    private fun navItem(icon: Int, label: String, index: Int): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = ripple(square(Color.TRANSPARENT, 20))
            setOnClickListener { showTab(index) }
        }
        val image = ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(COL_TEXT_DIM, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val text = TextView(this).apply {
            text = label
            sp(10f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(3), 0, 0)
            letterSpacing = 0.06f
        }
        item.addView(image, LinearLayout.LayoutParams(dp(21), dp(21)))
        item.addView(text)
        navIcons.add(image)
        navLabels.add(text)
        return item
    }

    /** Two doors into the reference screens, kept next to the status they explain. */
    private fun buildEntryRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(0))
        }
        row.addView(
            navCard(R.drawable.ic_tools, "战役浏览", "4 个战役 · 关卡名", CampaignActivity::class.java),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) },
        )
        row.addView(
            navCard(R.drawable.ic_folder_tool, "地图浏览", ".map / .mpr / .yrm", MapActivity::class.java),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return row
    }

    private fun navCard(icon: Int, title: String, sub: String, target: Class<*>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = ripple(pill(COL_SURFACE, 14, COL_OUTLINE))
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, target))
            }
            val image = ImageView(this@LauncherActivity).apply {
                setImageResource(icon)
                setColorFilter(COL_ACCENT, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val column = LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            column.addView(TextView(this@LauncherActivity).apply {
                text = title
                sp(13f)
                setTextColor(COL_TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            column.addView(TextView(this@LauncherActivity).apply {
                text = sub
                sp(10f)
                setTextColor(COL_TEXT_DIM)
                setPadding(0, dp(2), 0, 0)
            })
            addView(image, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(4))
        }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            text = "VeraZip"
            sp(26f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.03f
        }
        val tagline = TextView(this).apply {
            text = "资源检查器"
            sp(11f)
            setTextColor(COL_ACCENT)
            letterSpacing = 0.22f
            setPadding(0, dp(2), 0, 0)
        }
        // GPLv3 §5(a): a changed version has to say so, prominently.
        val notice = TextView(this).apply {
            text = "非官方修改版 · 基于 VERA20K · GPLv3"
            sp(11f)
            setTextColor(0xFF9BA2AC.toInt())
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = pill(0xFF171B21.toInt(), 8, COL_OUTLINE)
        }
        val noticeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(10), 0, 0)
        }
        noticeRow.addView(notice)
        titles.addView(title)
        titles.addView(tagline)
        titles.addView(noticeRow)

        val importButton = iconButton(R.drawable.ic_import, "导入文件或 ZIP") { showImportSheet() }
        val settings = iconButton(R.drawable.ic_settings, "设置") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(importButton, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(6) })
        header.addView(settings, LinearLayout.LayoutParams(dp(42), dp(42)))
        return header
    }

    /** A line-art icon from the pack, tinted to the text colour for this surface. */
    private fun iconButton(res: Int, description: String, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(res)
            setColorFilter(COL_TEXT, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ripple(square(COL_SURFACE_HI, 12))
            contentDescription = description
            val pad = dp(9)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBg()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = dp(10)
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusBadge = TextView(this).apply {
            text = "…"
            sp(16f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT_DIM)
            background = square(0xFF1E2229.toInt(), 12)
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
        }
        statusTitle = TextView(this).apply {
            text = "正在统计…"
            sp(15f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusSub = TextView(this).apply {
            text = ""
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(3), 0, 0)
        }
        textColumn.addView(statusTitle)
        textColumn.addView(statusSub)

        val numberColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        sizeValue = TextView(this).apply {
            text = "—"
            sp(17f)
            setTextColor(COL_TEXT)
            gravity = Gravity.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        sizeCount = TextView(this).apply {
            text = ""
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            gravity = Gravity.END
            setPadding(0, dp(3), 0, 0)
        }
        numberColumn.addView(sizeValue)
        numberColumn.addView(sizeCount)

        topRow.addView(statusBadge, LinearLayout.LayoutParams(dp(42), dp(42)))
        topRow.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(numberColumn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(topRow)
        card.addView(chipRow)
        return card
    }

    /**
     * Search and sort for the current directory. A 1.5 GB installation is
     * thousands of files, and scrolling to find one is what made the list feel
     * clumsy next to the file operations themselves.
     */
    private fun buildSearchBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(2))
        }
        searchField = EditText(this).apply {
            hint = "搜索当前目录"
            sp(12f)
            setTextColor(COL_TEXT)
            setHintTextColor(COL_TEXT_FAINT)
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pill(COL_SURFACE, 12, COL_OUTLINE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    applyView()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        sortButton = TextView(this).apply {
            text = "按名称"
            sp(11f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(pill(COL_SURFACE_HI, 11, COL_OUTLINE))
            setOnClickListener {
                sortByDate = !sortByDate
                text = if (sortByDate) "按时间" else "按名称"
                applyView()
            }
        }
        row.addView(searchField, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(8) })
        row.addView(sortButton, LinearLayout.LayoutParams(dp(64), dp(38)))
        return row
    }

    private fun buildPathBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
        }
        upButton = TextView(this).apply {
            text = "↑"
            sp(16f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(square(COL_SURFACE_HI, 11))
            contentDescription = "返回上一级目录"
            setOnClickListener { goUp() }
        }
        pathView = TextView(this).apply {
            sp(12f)
            setTextColor(COL_TEXT_DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(10), 0, 0, 0)
        }
        bar.addView(upButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        bar.addView(pathView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun buildListArea(): View {
        adapter = EntryAdapter()
        listView = ListView(this).apply {
            divider = ColorDrawable(0xFF14171C.toInt())
            dividerHeight = dp(1)
            setSelector(ColorDrawable(0x14FFFFFF))
            setBackgroundColor(Color.TRANSPARENT)
            adapter = this@LauncherActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
                openEntry(entry)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val entry = entries.getOrNull(position) ?: return@setOnItemLongClickListener false
                showFileActions(entry)
                true
            }
        }

        emptyView = TextView(this).apply {
            text = "这个目录是空的\n点右上角的导入按钮，把文件或 ZIP 放进来"
            sp(12f)
            setTextColor(COL_TEXT_FAINT)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(34), dp(30), dp(34), dp(30))
            visibility = View.GONE
        }

        return FrameLayout(this).apply {
            addView(listView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(emptyView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
    }

    private fun buildFooter(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
        }
        val refresh = TextView(this).apply {
            text = "刷新"
            sp(12f)
            setTextColor(COL_TEXT)
            gravity = Gravity.CENTER
            background = ripple(pill(COL_SURFACE_HI, 13, COL_OUTLINE))
            setOnClickListener { currentDir?.let { showDirectory(it) } }
        }
        val import = TextView(this).apply {
            text = "导入文件或 ZIP"
            sp(13f)
            setTextColor(COL_DARK_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = ripple(accentButton(23), 0x40FFFFFF)
            setOnClickListener { showImportSheet() }
        }
        row.addView(refresh, LinearLayout.LayoutParams(dp(78), dp(44)).apply { rightMargin = dp(10) })
        row.addView(import, LinearLayout.LayoutParams(0, dp(44), 1f))
        return row
    }

    // --- file list ----------------------------------------------------------

    /** One row: type icon from the art pack, name over detail, and a chevron. */
    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = entries[position]
            val row = LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(14), dp(10))
            }

            val icon = ImageView(this@LauncherActivity).apply {
                setImageResource(FileKinds.icon(entry.name, entry.isDirectory))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val column = LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), 0, dp(8), 0)
            }
            val name = TextView(this@LauncherActivity).apply {
                text = entry.name
                sp(14f)
                setTextColor(COL_TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val detail = TextView(this@LauncherActivity).apply {
                text = describe(entry)
                sp(11f)
                setTextColor(COL_TEXT_DIM)
                setPadding(0, dp(2), 0, 0)
            }
            column.addView(name)
            column.addView(detail)

            val chevron = ImageView(this@LauncherActivity).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(COL_TEXT_FAINT, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            row.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(chevron, LinearLayout.LayoutParams(dp(14), dp(14)))
            return row
        }
    }

    private fun describe(entry: File): String = when {
        entry.isDirectory -> "文件夹"
        !Prefs.getBoolean(this, Prefs.SHOW_SIZE, true) ->
            FileKinds.extensionOf(entry.name).uppercase(Locale.US)
        else -> formatSize(entry.length()) + " · " +
            android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", entry.lastModified())
    }

    // --- directory ---------------------------------------------------------

    /** Whether both archives the engine opens are present in `dir`. */
    private fun archivesPresent(dir: File): Boolean = Importers.holdsMasters(dir)

    /**
     * Find the folder that really holds both masters and make `config.toml` say
     * so. A picked parent folder ("…/Red Alert II" while the game sits in
     * "…/Red Alert II/RA2") copies correctly but leaves the archives one level
     * below `ra2_dir`, which the engine does not search.
     */
    private fun alignGameDir(): Boolean {
        val found = Importers.locateMasters(gameRoot) ?: return false
        if (found.canonicalPath != gameRoot.canonicalPath) {
            gameRoot = found
            if (currentDir?.canonicalPath == found.parentFile?.canonicalPath ||
                currentDir?.canonicalPath == gameRoot.parentFile?.canonicalPath
            ) {
                currentDir = found
            }
        }
        Importers.registerGameDir(this, gameRoot)
        return true
    }

    private fun showDirectory(dir: File) {
        currentDir = dir
        entries.clear()
        // The read is a snapshot of this folder, not an addition to it: a
        // re-entry (tapping the Files tab again, coming back from a viewer)
        // used to append the whole listing to the last one, so every visit
        // doubled the rows and same-named entries piled up.
        allEntries.clear()
        val showHidden = Prefs.getBoolean(this, Prefs.SHOW_HIDDEN, false)
        allEntries.addAll(
            dir.listFiles()
                ?.filter { showHidden || !it.name.startsWith('.') }
                .orEmpty(),
        )
        applyView()

        pathView.text = displayPath(dir)
        upButton.isEnabled = dir.canonicalFile != gameRoot.canonicalFile
        upButton.alpha = if (upButton.isEnabled) 1f else 0.35f

        val present = REQUIRED_ARCHIVES.associateWith { name -> hasArchive(dir, name) }
        showArchives(present)
        refreshStatus(dir, present)
    }

    /** Re-derive the visible list from [allEntries], the search text and the sort. */
    private fun applyView() {
        entries.clear()
        val needle = query.trim().lowercase(Locale.US)
        val filtered = allEntries.filter { entry ->
            needle.isEmpty() || entry.name.lowercase(Locale.US).contains(needle)
        }
        entries.addAll(
            if (sortByDate) {
                filtered.sortedWith(
                    compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() },
                )
            } else {
                filtered.sortedWith(
                    compareByDescending<File> { it.isDirectory }
                        .thenBy { it.name.lowercase(Locale.US) },
                )
            },
        )
        adapter.notifyDataSetChanged()
        listView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        (emptyView as? TextView)?.text = if (needle.isNotEmpty()) {
            "没有匹配「$query」的文件"
        } else {
            "这个目录是空的\n点右上角的导入按钮，把文件或 ZIP 放进来"
        }
    }

    private fun hasArchive(dir: File, name: String): Boolean =
        File(dir, name).isFile ||
            (dir.listFiles()?.any { it.name.equals(name, ignoreCase = true) } == true)

    private fun displayPath(dir: File): String {
        val relative = try {
            dir.canonicalFile.relativeTo(gameRoot.canonicalFile).path
        } catch (_: IllegalArgumentException) {
            ""
        }
        return if (relative.isEmpty()) gameRoot.absolutePath else gameRoot.absolutePath + "/" + relative
    }

    /**
     * Space and file count are walked off the UI thread: a full installation is
     * 1.5 GB in thousands of files, far too slow to walk between two frames.
     */
    private fun refreshStatus(dir: File, present: Map<String, Boolean>) {
        val token = ++scanToken
        statusBadge.text = "…"
        statusBadge.setTextColor(COL_TEXT_DIM)
        statusTitle.text = "正在统计…"
        statusSub.text = ""
        sizeValue.text = "—"
        sizeCount.text = ""

        Thread {
            var bytes = 0L
            var files = 0
            fun walk(target: File) {
                val children = target.listFiles() ?: return
                for (child in children) {
                    if (child.isDirectory) walk(child) else {
                        files++
                        bytes += child.length()
                    }
                }
            }
            walk(dir)
            runOnUiThread {
                if (token != scanToken || isFinishing) return@runOnUiThread
                val complete = present.values.all { it }
                statusBadge.text = if (complete) "✓" else "!"
                statusBadge.setTextColor(if (complete) COL_GOOD else COL_BAD)
                statusTitle.text = if (complete) "资源完整" else "缺少必需文件"
                statusSub.text = if (complete) {
                    "两个 *.mix 都在位，可以开始游戏"
                } else {
                    "缺 " + REQUIRED_ARCHIVES.filter { present[it] != true }.joinToString(" / ") +
                        " · 用下面的导入补齐"
                }
                sizeValue.text = formatSize(bytes)
                sizeCount.text = String.format(Locale.US, "%,d 个文件", files)
                enterButton.text = if (complete) "进入游戏" else "进入游戏 · 缺资源"
                heroBase = if (complete) {
                    "ra2md.mix / ra2.mix 就位 · 可直接进入"
                } else {
                    "还缺 " + REQUIRED_ARCHIVES.filter { present[it] != true }.joinToString(" / ")
                }
                updateHeroSub()
            }
        }.start()
    }

    private fun showArchives(present: Map<String, Boolean>) {
        chipRow.removeAllViews()
        for (name in REQUIRED_ARCHIVES) {
            val ok = present[name] == true
            val chip = TextView(this).apply {
                text = "●  $name"
                sp(11f)
                setTextColor(if (ok) COL_GOOD else COL_BAD)
                setPadding(dp(9), dp(4), dp(10), dp(4))
                background = pill(if (ok) 0x146BD08A else 0x14E8837A, 8, 0x33FFFFFF)
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) })
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> bytes.toString() + " B"
    }

    private fun goUp() {
        val dir = currentDir ?: return
        val parent = dir.parentFile ?: return
        val root = gameRoot.canonicalFile
        if (parent.canonicalFile == root ||
            parent.canonicalFile.path.startsWith(root.path + File.separator)
        ) {
            showDirectory(parent)
        }
    }

    override fun onBackPressed() {
        val dir = currentDir
        if (dir != null && dir.canonicalFile != gameRoot.canonicalFile) goUp() else super.onBackPressed()
    }

    // --- opening ------------------------------------------------------------

    private fun openEntry(entry: File) {
        when {
            entry.isDirectory -> showDirectory(entry)
            FileKinds.isTextViewable(entry.name) -> startActivity(
                Intent(this, TextViewerActivity::class.java).putExtra("path", entry.absolutePath),
            )
            FileKinds.isImageViewable(entry.name) -> startActivity(
                Intent(this, ImageViewerActivity::class.java).putExtra("path", entry.absolutePath),
            )
            entry.name.endsWith(".mix", ignoreCase = true) -> startActivity(
                Intent(this, MixActivity::class.java).putExtra("path", entry.absolutePath),
            )
            else -> showFileActions(entry)
        }
    }

    // --- actions ------------------------------------------------------------

    private fun showFileActions(entry: File) {
        val actions = mutableListOf<SheetAction>()
        if (entry.isDirectory) {
            actions += SheetAction("进入文件夹") { showDirectory(entry) }
        } else if (FileKinds.isTextViewable(entry.name) || FileKinds.isImageViewable(entry.name)) {
            actions += SheetAction("打开") { openEntry(entry) }
        } else {
            actions += SheetAction("用文本方式打开") {
                startActivity(
                    Intent(this, TextViewerActivity::class.java).putExtra("path", entry.absolutePath),
                )
            }
        }
        if (!entry.isDirectory && entry.name.endsWith(".mix", ignoreCase = true)) {
            actions += SheetAction("查看 MIX 内容") {
                startActivity(
                    Intent(this, MixActivity::class.java).putExtra("path", entry.absolutePath),
                )
            }
        }
        actions += SheetAction("重命名") { promptRename(entry) }
        actions += SheetAction("移动到…") { pickDirectory("移动到") { dir -> moveEntry(entry, dir) } }
        actions += SheetAction("复制到…") { pickDirectory("复制到") { dir -> copyEntry(entry, dir) } }
        actions += SheetAction("属性") { showDetails(entry) }
        actions += SheetAction(if (entry.isDirectory) "删除文件夹" else "删除文件", danger = true) {
            confirmDelete(entry)
        }

        showSheet(
            title = entry.name,
            subtitle = if (entry.isDirectory) "文件夹" else formatSize(entry.length()),
            actions = actions,
        )
    }

    private fun showDetails(entry: File) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(entry.lastModified()))
        val subtitle = buildString {
            append("路径  ").append(entry.absolutePath).append('\n')
            append("修改  ").append(stamp).append('\n')
            if (entry.isDirectory) {
                append("内容  ").append(countFiles(entry)).append(" 个文件")
            } else {
                append("大小  ").append(formatSize(entry.length()))
            }
        }
        showSheet("属性", subtitle, listOf(SheetAction("关闭") {}))
    }

    private fun countFiles(dir: File): Int {
        var count = 0
        dir.listFiles()?.forEach { child ->
            count += if (child.isDirectory) countFiles(child) else 1
        }
        return count
    }

    private fun moveEntry(entry: File, destDir: File) {
        if (entry.parentFile?.canonicalFile == destDir.canonicalFile) {
            toast("已经在目标目录里")
            return
        }
        if (entry.isDirectory && Importers.isInside(entry, destDir)) {
            toast("不能把文件夹移动到它自己里面")
            return
        }
        val target = File(destDir, entry.name)
        if (target.exists()) {
            toast("目标目录已有同名项目")
            return
        }
        destDir.mkdirs()
        val renamed = entry.renameTo(target)
        if (!renamed) {
            // A different volume cannot be renamed across; copy then drop the source.
            val copied = try {
                copyTree(entry, target)
            } catch (_: Throwable) {
                false
            }
            if (copied) deleteTree(entry)
            toast(if (copied) "已移动" else "移动失败")
        } else {
            toast("已移动")
        }
        currentDir?.let { showDirectory(it) }
    }

    private fun copyEntry(entry: File, destDir: File) {
        if (entry.isDirectory && Importers.isInside(entry, destDir)) {
            toast("不能把文件夹复制到它自己里面")
            return
        }
        val target = File(destDir, entry.name)
        if (target.exists()) {
            toast("目标目录已有同名项目")
            return
        }
        val copied = try {
            copyTree(entry, target)
        } catch (_: Throwable) {
            false
        }
        toast(if (copied) "已复制" else "复制失败")
        currentDir?.let { showDirectory(it) }
    }

    private fun copyTree(source: File, target: File): Boolean {
        if (source.isDirectory) {
            target.mkdirs()
            var ok = true
            source.listFiles()?.forEach { child ->
                if (!copyTree(child, File(target, child.name))) ok = false
            }
            return ok
        }
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return true
    }

    private fun promptRename(entry: File) {
        val dialog = Dialog(this)
        val panel = panel()
        val input = EditText(this).apply {
            setText(entry.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            sp(14f)
            setTextColor(COL_TEXT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = pill(COL_SURFACE_HI, 10, COL_OUTLINE)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        val cancel = sheetButton("取消")
        val ok = sheetButton("保存", accent = true)

        panel.addView(sheetTitle("重命名"))
        panel.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        row.addView(cancel, LinearLayout.LayoutParams(dp(96), dp(44)).apply { rightMargin = dp(10) })
        row.addView(ok, LinearLayout.LayoutParams(dp(96), dp(44)))
        panel.addView(row)

        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener {
            renameTo(entry, input.text.toString().trim())
            dialog.dismiss()
        }

        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        input.requestFocus()
    }

    private fun renameTo(entry: File, name: String) {
        if (name.isEmpty() || name.contains('/') || name.contains('\\')) {
            toast("名称不合法")
            return
        }
        val target = File(entry.parentFile, name)
        if (target.exists()) {
            toast("同名项目已存在")
            return
        }
        toast(if (entry.renameTo(target)) "已重命名" else "重命名失败")
        currentDir?.let { showDirectory(it) }
    }

    private fun confirmDelete(entry: File) {
        showSheet(
            title = "删除「" + entry.name + "」？",
            subtitle = if (entry.isDirectory) "整个文件夹及其内容都会被删除，无法恢复。"
            else "文件会被删除，无法恢复。",
            actions = listOf(
                SheetAction("取消") {},
                SheetAction("确认删除", danger = true) {
                    val deleted = if (entry.isDirectory) deleteTree(entry) else entry.delete()
                    toast(if (deleted) "已删除 " + entry.name else "删除失败：" + entry.name)
                    currentDir?.let { showDirectory(it) }
                },
            ),
        )
    }

    private fun deleteTree(root: File): Boolean {
        val children = root.listFiles()
        var complete = true
        children?.forEach { child ->
            val ok = if (child.isDirectory) deleteTree(child) else child.delete()
            if (!ok) complete = false
        }
        return complete and root.delete()
    }

    /** Walk the installation for a destination folder; never leaves the game root. */
    private fun pickDirectory(title: String, onPick: (File) -> Unit) {
        val dialog = Dialog(this)
        val panel = panel()
        var browse = gameRoot

        val heading = sheetTitle(title)
        val pathLine = TextView(this).apply {
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(3), 0, dp(8))
        }
        val list = ListView(this).apply {
            divider = ColorDrawable(0xFF14171C.toInt())
            dividerHeight = dp(1)
            setSelector(ColorDrawable(0x14FFFFFF))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val up = sheetButton("上一级")
        val choose = sheetButton("选择此处", accent = true)

        fun refresh() {
            pathLine.text = displayPath(browse)
            val dirs = browse.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            list.adapter = DirectoryAdapter(dirs)
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val dir = (list.adapter as DirectoryAdapter).item(position) ?: return@setOnItemClickListener
            browse = dir
            refresh()
        }
        up.setOnClickListener {
            val parent = browse.parentFile
            if (parent != null && Importers.isInside(gameRoot, parent)) {
                browse = parent
                refresh()
            }
        }
        choose.setOnClickListener {
            dialog.dismiss()
            onPick(browse)
        }

        panel.addView(heading)
        panel.addView(pathLine)
        panel.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(250),
        ))
        buttons.addView(up, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(10) })
        buttons.addView(choose, LinearLayout.LayoutParams(0, dp(44), 1f))
        panel.addView(buttons)
        refresh()

        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private inner class DirectoryAdapter(private val dirs: List<File>) : BaseAdapter() {
        override fun getCount(): Int = dirs.size
        override fun getItem(position: Int): Any = dirs[position]
        override fun getItemId(position: Int): Long = position.toLong()
        fun item(position: Int): File? = dirs.getOrNull(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val dir = dirs[position]
            val row = LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
            }
            val icon = ImageView(this@LauncherActivity).apply {
                setImageResource(R.drawable.ic_folder_tool)
                setColorFilter(COL_TEXT_DIM, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val name = TextView(this@LauncherActivity).apply {
                text = dir.name
                sp(13f)
                setTextColor(COL_TEXT)
                setPadding(dp(12), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }
    }

    // --- first-run wizard ---------------------------------------------------

    /**
     * Shown when the two archives are missing: an empty list and a red badge tell
     * a player who just installed nothing. Two ways in — a folder already on the
     * phone, or an archive to unpack — then a re-check that also clears the layer.
     */
    private fun showWizard() {
        if (wizard != null) return

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(20))
        }
        column.addView(TextView(this).apply {
            text = "第 1 步"
            sp(11f)
            setTextColor(COL_ACCENT)
            letterSpacing = 0.2f
        })
        column.addView(TextView(this).apply {
            text = "先把游戏文件放进来"
            sp(24f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(6), 0, dp(10))
        })
        column.addView(TextView(this).apply {
            text = "引擎要打开 ra2.mix 与 ra2md.mix，还需要整个游戏目录里的其余资源。" +
                "现在这个目录里还没有它们：\n" + gameRoot.absolutePath
            sp(12f)
            setTextColor(COL_TEXT_DIM)
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        column.addView(
            option(
                R.drawable.ic_folder,
                "选择游戏文件夹",
                "选整个游戏目录，逐个文件复制进来。最稳，野包或已装好的目录都能用",
            ) { startImportTree() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        column.addView(
            option(
                R.drawable.ic_archive,
                "选择资源压缩包（ZIP）",
                "解压到上面这个目录。不支持加密 ZIP、分卷包与 RAR / 7z",
            ) { startImportZip() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        column.addView(TextView(this).apply {
            text = "放好之后点「重新检查」；两个 *.mix 都在位就会自动收起这一层。"
            sp(11f)
            setTextColor(COL_TEXT_FAINT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(16), 0, 0)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        actions.addView(TextView(this).apply {
            text = "重新检查"
            sp(13f)
            setTextColor(COL_TEXT)
            gravity = Gravity.CENTER
            background = ripple(pill(COL_SURFACE_HI, 24, COL_OUTLINE))
            setOnClickListener {
                if (alignGameDir()) {
                    showDirectory(gameRoot)
                    hideWizard()
                } else {
                    showDirectory(gameRoot)
                    toast("没找到 ra2md.mix / ra2.mix：文件可能还在更深一层，或选错了文件夹")
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
        actions.addView(TextView(this).apply {
            text = "先浏览目录"
            sp(13f)
            setTextColor(COL_DARK_TEXT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ripple(accentButton(24), 0x40FFFFFF)
            setOnClickListener { hideWizard() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        column.addView(actions)

        val layer = FrameLayout(this).apply {
            setBackgroundColor(COL_BG)
            isClickable = true
            addView(
                ScrollView(this@LauncherActivity).apply { addView(column) },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        overlayHost.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        wizard = layer
    }

    private fun option(icon: Int, title: String, detail: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(14), dp(15))
            background = ripple(pill(COL_SURFACE, 16, COL_OUTLINE))
            isClickable = true
            setOnClickListener { onClick() }
            val image = ImageView(this@LauncherActivity).apply {
                setImageResource(icon)
                setColorFilter(COL_ACCENT, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val column = LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), 0, dp(8), 0)
            }
            column.addView(TextView(this@LauncherActivity).apply {
                text = title
                sp(15f)
                setTextColor(COL_TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            column.addView(TextView(this@LauncherActivity).apply {
                text = detail
                sp(11f)
                setTextColor(COL_TEXT_DIM)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(4), 0, 0)
            })
            val chevron = ImageView(this@LauncherActivity).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(COL_TEXT_FAINT, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(image, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(14), dp(14)))
        }

    private fun hideWizard() {
        val layer = wizard ?: return
        overlayHost.removeView(layer)
        wizard = null
    }

    private fun startImportTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_TREE)
    }

    private fun importFolder(uri: Uri) {
        val counter = progress("正在复制文件夹…")
        Thread {
            val result = try {
                val pair = Importers.copyTree(this, uri, gameRoot) { files, bytes ->
                    // A full installation is thousands of files; repainting every
                    // one of them would cost more than the copy itself.
                    if (files < 4 || files % 25 == 0) {
                        counter("已复制 $files 个文件\n" + formatSize(bytes))
                    }
                }
                "已复制 ${pair.first} 个文件（" + formatSize(pair.second) + "）"
            } catch (error: Throwable) {
                "复制失败：" + (error.message ?: "无法读取所选文件夹")
            }
            runOnUiThread {
                dismissProgress()
                toast(result)
                afterImport()
            }
        }.start()
    }

    /** Re-check after any import, re-point the config, and drop the wizard. */
    private fun afterImport() {
        val aligned = alignGameDir()
        if (currentDir?.canonicalPath != gameRoot.canonicalPath) {
            currentDir?.let { showDirectory(it) }
        } else {
            showDirectory(gameRoot)
        }
        if (aligned) {
            hideWizard()
            toast("资源已就位：" + gameRoot.absolutePath)
        } else {
            toast("复制完成，但没找到 ra2md.mix / ra2.mix")
        }
    }

    // --- import -------------------------------------------------------------

    private fun showImportSheet() {
        showSheet(
            title = "导入",
            subtitle = "文件会放进当前目录：" + displayPath(currentDir ?: gameRoot),
            actions = listOf(
                SheetAction("导入文件…") { startImportFiles() },
                // The archive route: unpacked into private storage first, then
                // kept as the game itself or as a mod — never dumped blindly
                // into whatever folder the browser happens to show.
                SheetAction("导入压缩包（ZIP / 7z）…") { startArchivePick() },
                SheetAction("解压到当前目录（ZIP / 7z）…") { startImportZip() },
                SheetAction("新建文件夹") { promptNewFolder() },
            ),
        )
    }

    private fun startImportFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_IMPORT_FILES)
    }

    private fun startImportZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, ARCHIVE_MIME_TYPES)
        }
        startActivityForResult(intent, REQUEST_IMPORT_ZIP)
    }

    /** The archive entry: unpack into private storage, then ask what it is. */
    private fun startArchivePick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, ARCHIVE_MIME_TYPES)
        }
        startActivityForResult(intent, REQUEST_ARCHIVE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        // Mods fill their own folder, not the one the browser shows.
        when (requestCode) {
            REQUEST_MOD_ZIP -> {
                data.data?.let { importModZip(it) }
                return
            }
            REQUEST_MOD_TREE -> {
                data.data?.let { importModTree(it) }
                return
            }
            REQUEST_ARCHIVE -> {
                data.data?.let { importArchive(it) }
                return
            }
            REQUEST_BROWSER -> {
                data.getStringExtra(BrowserActivity.EXTRA_FILE)?.let { acceptDownload(it) }
                return
            }
        }
        val dir = currentDir ?: return
        when (requestCode) {
            REQUEST_IMPORT_FILES -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) uris += clip.getItemAt(index).uri
                }
                data.data?.let { uris += it }
                if (uris.isNotEmpty()) importFiles(uris, dir)
            }
            REQUEST_IMPORT_ZIP -> data.data?.let { importZip(it, dir) }
            REQUEST_IMPORT_TREE -> data.data?.let { importFolder(it) }
        }
    }

    private fun importFiles(uris: List<Uri>, dir: File) {
        val counter = progress("正在导入 ${uris.size} 个文件…")
        Thread {
            var done = 0
            var failed = 0
            for (uri in uris) {
                try {
                    val name = Importers.displayName(this, uri)
                    val target = uniqueTarget(dir, name)
                    Importers.copyDocument(this, uri, target) { bytes ->
                        counter(target.name + "\n" + formatSize(bytes))
                    }
                    done++
                } catch (_: Throwable) {
                    failed++
                }
            }
            runOnUiThread {
                dismissProgress()
                toast(
                    if (failed == 0) "已导入 $done 个文件"
                    else "导入 $done 个，失败 $failed 个",
                )
                afterImport()
            }
        }.start()
    }

    private fun importZip(uri: Uri, dir: File) {
        val counter = progress("正在解压…")
        Thread {
            val result = try {
                val pair = Importers.extractArchive(this, uri, dir) { files, bytes ->
                    counter("已解压 $files 个文件\n" + formatSize(bytes))
                }
                "已解压 ${pair.first} 个文件（" + formatSize(pair.second) + "）"
            } catch (error: Throwable) {
                "解压失败：" + (error.message ?: "压缩包可能已损坏或被加密")
            }
            runOnUiThread {
                dismissProgress()
                toast(result)
                afterImport()
            }
        }.start()
    }

    // --- archives into private storage --------------------------------------

    /**
     * Unpacks an archive into a private staging folder, then asks what it is
     * for. Nothing is written into the game folder or `Mods` before that
     * answer: a badly packed upload would otherwise leave a half-installed
     * game behind.
     */
    private fun importArchive(uri: Uri) {
        val label = Importers.displayName(this, uri).substringBeforeLast('.')
        unpackThenAsk(label) { staging, counter ->
            Importers.extractArchive(this, uri, staging) { files, bytes ->
                if (files < 4 || files % 50 == 0) {
                    counter("已解压 $files 个文件\n" + formatSize(bytes))
                }
            }
        }
    }

    /**
     * A file the in-app browser downloaded.
     *
     * Archives go through the same staging-then-ask path as a picked one; a
     * loose file (a single .mix, .map, .ini …) cannot be "a game" on its own,
     * so it is offered as one file placed either in the game folder or inside
     * a mod folder of its own.
     */
    private fun acceptDownload(path: String) {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) {
            toast("下载的文件不存在或为空")
            return
        }
        val label = file.name.substringBeforeLast('.').ifBlank { file.name }
        when (Importers.archiveKindOf(file)) {
            "zip", "7z" -> unpackThenAsk(label, onDone = { dropDownload(file) }) { staging, counter ->
                Importers.extractArchive(this, file, staging) { files, bytes ->
                    if (files < 4 || files % 50 == 0) {
                        counter("已解压 $files 个文件\n" + formatSize(bytes))
                    }
                }
            }
            "rar" -> {
                dropDownload(file)
                toast("暂不支持 RAR：请先在站点上换成 ZIP 或 7z")
            }
            else -> askLooseFile(file, label)
        }
    }

    /** The sheet for a non-archive download: one file, two places, or delete. */
    private fun askLooseFile(file: File, label: String) {
        showSheet(
            "已下载「${file.name}」",
            "这不是压缩包（" + formatSize(file.length()) + "）。\n" +
                "只有单个文件时，可以放进游戏目录，或单独做成一个模组。",
            listOf(
                SheetAction("放进游戏目录") { placeLoose(file, mod = false) },
                SheetAction("作为模组放进 Mods") { placeLoose(file, mod = true) },
                SheetAction("删除", danger = true) {
                    dropDownload(file)
                    toast("已删除下载的文件")
                },
            ),
        )
    }

    private fun placeLoose(file: File, mod: Boolean) {
        val counter = progress(if (mod) "正在放进 Mods…" else "正在放进游戏目录…")
        Thread {
            val message = try {
                val target = if (mod) {
                    Importers.uniqueDir(Importers.modsDir(this), Importers.plainFolderName(file.nameWithoutExtension))
                } else {
                    gameRoot
                }
                if (!Importers.moveTree(file, File(target, file.name))) {
                    throw java.io.IOException("无法写入 " + target.name)
                }
                dropDownload(file)
                if (mod) {
                    "已放进 Mods/${target.name}\n到「模组」页点它启用"
                } else {
                    "已放进 " + gameRoot.name
                }
            } catch (error: Throwable) {
                "放入失败：" + (error.message ?: "无法写入")
            }
            runOnUiThread {
                dismissProgress()
                toast(message)
                if (mod) refreshMods() else afterImport()
            }
        }.start()
    }

    /** Removes the per-download folder; the file in it has been dealt with. */
    private fun dropDownload(file: File) {
        val parent = file.parentFile
        if (parent != null && parent.name.startsWith(".download-")) {
            Importers.deleteTree(parent)
        } else {
            file.delete()
        }
    }

    /**
     * Unpack into a private staging folder, then ask what it is for.
     *
     * `onDone` runs after a successful unpack (the source download can then be
     * dropped); on failure the staging folder is removed and the message is
     * shown instead, so a bad archive never leaves half an installation.
     */
    private fun unpackThenAsk(
        label: String,
        onDone: () -> Unit = {},
        unpack: (staging: File, counter: (String) -> Unit) -> Pair<Int, Long>,
    ) {
        val counter = progress("正在解压…")
        Thread {
            val staging = File(
                Importers.settingsDir(this),
                ".archive-" + System.currentTimeMillis(),
            )
            var failure: String? = null
            try {
                unpack(staging) { counter(it) }
            } catch (error: Throwable) {
                Importers.deleteTree(staging)
                failure = "解压失败：" + (error.message ?: "压缩包可能已损坏或被加密")
            }
            runOnUiThread {
                dismissProgress()
                if (failure != null) {
                    toast(failure)
                } else {
                    onDone()
                    askArchiveTarget(staging, label)
                }
            }
        }.start()
    }


    /** What to do with an unpacked archive: game, mod, or nothing. */
    private fun askArchiveTarget(root: File, label: String) {
        val masters = Importers.locateMasters(root)
        val found = if (masters != null) {
            "检测到 ra2md.mix / ra2.mix"
        } else {
            "没有检测到 ra2md.mix / ra2.mix"
        }
        showSheet(
            "导入「$label」",
            "$found。\n" +
                "· 作为游戏本体 → 放进 " + displayPath(gameRoot) + "\n" +
                "· 作为模组 → 放进 Mods，之后在「模组」页启用",
            listOf(
                SheetAction("作为游戏本体") { useArchiveAsBase(root, label) },
                SheetAction("作为模组") { useArchiveAsMod(root, label) },
                SheetAction("取消并删除", danger = true) {
                    Importers.deleteTree(root)
                },
            ),
        )
    }

    /** Puts the unpacked tree where the engine reads the game from. */
    private fun useArchiveAsBase(root: File, label: String) {
        val counter = progress("正在放进游戏目录…")
        Thread {
            val message = try {
                // A download often wraps the installation one or two folders
                // deep ("RA2YR/Patch1.001/…"): install from the folder that
                // really holds the archives, not from the wrapper.
                val source = Importers.locateMasters(root) ?: root
                val pair = Importers.mergeTree(source, gameRoot) { files, bytes ->
                    if (files < 4 || files % 25 == 0) {
                        counter("已写入 $files 个文件\n" + formatSize(bytes))
                    }
                }
                Importers.deleteTree(root)
                Importers.registerGameDir(this, gameRoot)
                "「$label」已放进 " + gameRoot.name + "（${pair.first} 个文件）"
            } catch (error: Throwable) {
                "放入失败：" + (error.message ?: "无法写入游戏目录")
            }
            runOnUiThread {
                dismissProgress()
                toast(message)
                afterImport()
            }
        }.start()
    }

    /** Puts the unpacked tree into `Mods` as one mod folder, ready to enable. */
    private fun useArchiveAsMod(root: File, label: String) {
        val counter = progress("正在放进 Mods…")
        Thread {
            val message = try {
                val source = Importers.collapseSingleFolder(root)
                val target = Importers.uniqueDir(
                    Importers.modsDir(this),
                    Importers.plainFolderName(label),
                )
                if (!Importers.moveTree(source, target)) {
                    throw java.io.IOException("无法把文件移进 Mods")
                }
                Importers.stripArchiveNoise(target)
                if (source != root) Importers.deleteTree(root)
                "已作为模组导入「${target.name}」\n到「模组」页点它启用"
            } catch (error: Throwable) {
                Importers.deleteTree(root)
                "导入模组失败：" + (error.message ?: "无法写入 Mods")
            }
            runOnUiThread {
                dismissProgress()
                toast(message)
                refreshMods()
            }
        }.start()
    }

    private fun promptNewFolder() {
        val dialog = Dialog(this)
        val panel = panel()
        val input = EditText(this).apply {
            hint = "文件夹名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            sp(14f)
            setTextColor(COL_TEXT)
            setHintTextColor(COL_TEXT_FAINT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = pill(COL_SURFACE_HI, 10, COL_OUTLINE)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        val cancel = sheetButton("取消")
        val ok = sheetButton("创建", accent = true)
        panel.addView(sheetTitle("新建文件夹"))
        panel.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        row.addView(cancel, LinearLayout.LayoutParams(dp(96), dp(44)).apply { rightMargin = dp(10) })
        row.addView(ok, LinearLayout.LayoutParams(dp(96), dp(44)))
        panel.addView(row)

        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener {
            val name = input.text.toString().trim()
            dialog.dismiss()
            val dir = currentDir ?: return@setOnClickListener
            if (name.isEmpty() || name.contains('/')) {
                toast("名称不合法")
                return@setOnClickListener
            }
            val target = File(dir, name)
            if (target.exists()) {
                toast("同名文件夹已存在")
                return@setOnClickListener
            }
            toast(if (target.mkdirs()) "已创建 $name" else "创建失败")
            showDirectory(dir)
        }

        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        input.requestFocus()
    }

    /** `name`, `name (1)`, `name (2)`… so an import never overwrites silently. */
    private fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (target.exists()) {
            target = File(dir, "$base ($index)$ext")
            index++
        }
        return target
    }

    // --- progress -----------------------------------------------------------

    private fun progress(message: String): (String) -> Unit {
        val panel = panel()
        val title = sheetTitle(message.substringBefore('\n'))
        progressText = TextView(this).apply {
            text = message
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(6), 0, dp(12))
            maxLines = 3
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(COL_ACCENT)
        }
        panel.addView(title)
        panel.addView(progressText)
        panel.addView(bar, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(6)
        })

        progressDialog = Dialog(this).apply {
            setContentView(panel)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        progressDialog?.show()
        progressDialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.8f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        return { text ->
            runOnUiThread {
                val dialog = progressDialog ?: return@runOnUiThread
                if (!dialog.isShowing) return@runOnUiThread
                progressText?.text = text
            }
        }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
        progressText = null
    }

    // --- sheets -------------------------------------------------------------

    private class SheetAction(
        val label: String,
        val danger: Boolean = false,
        val run: () -> Unit,
    )

    /**
     * A rounded panel instead of the platform's light dialog, which would look
     * pasted onto this surface and has no room for a subtitle.
     */
    private fun showSheet(title: String, subtitle: String, actions: List<SheetAction>) {
        val dialog = Dialog(this)
        val panel = panel()
        panel.addView(sheetTitle(title))
        panel.addView(TextView(this).apply {
            text = subtitle
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(10))
        })

        for (action in actions) {
            val row = TextView(this).apply {
                text = action.label
                sp(14f)
                setTextColor(if (action.danger) COL_BAD else COL_TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = ripple(pill(COL_SURFACE_HI, 12, COL_OUTLINE))
                setOnClickListener {
                    dialog.dismiss()
                    action.run()
                }
            }
            panel.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(7) })
        }

        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.setGravity(Gravity.BOTTOM)
            it.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun sheetTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        sp(15f)
        setTextColor(COL_TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun sheetButton(label: String, accent: Boolean = false): TextView =
        TextView(this).apply {
            text = label
            sp(13f)
            gravity = Gravity.CENTER
            setTextColor(if (accent) COL_DARK_TEXT else COL_TEXT)
            background = ripple(
                if (accent) accentButton(22) else pill(COL_SURFACE_HI, 22, COL_OUTLINE),
                0x40FFFFFF,
            )
        }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = pill(COL_SURFACE, 20, COL_OUTLINE)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // --- hand over ----------------------------------------------------------

    /**
     * Hand over to the engine. [VeraActivity] is the NativeActivity that loads
     * `libvera20k_android.so`; this activity adds no state of its own, so the
     * engine starts exactly as it would from a launcher icon.
     */
    private fun launchGame() {
        startActivity(Intent(this, VeraActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
