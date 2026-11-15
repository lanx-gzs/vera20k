package org.vera20k

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.NativeActivity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The app's activity: [NativeActivity] plus the Android features the NDK cannot
 * reach on its own — the system pickers and a Storage Access Framework copy.
 *
 * The engine runs on [NativeActivity]'s surface and thread; this class adds no
 * game UI. It asks the player for the game files, hands the chosen URI back to
 * Rust through [nativeOnFolderPicked], and then copies what it can read into the
 * app's private storage, because a `content://` URI has no filesystem path for
 * the engine to open.
 *
 * Two sources are accepted:
 *  - a folder tree (`ACTION_OPEN_DOCUMENT_TREE`), copied file by file;
 *  - a ZIP archive (`ACTION_OPEN_DOCUMENT`), unpacked in one pass, so a phone
 *    never has to hold both the archive and a second full copy of its contents
 *    before the import can start.
 *
 * A successful import can also delete the source (`deleteSourceWhenDone`), which
 * is what frees the phone's space back up; that is destructive, so it is only
 * offered after the copy is published and always behind a confirmation.
 */
class VeraActivity : NativeActivity() {
    private companion object {
        /** Unique within this activity; "VR" in ASCII. */
        const val REQUEST_PICK_FOLDER = 0x5652
        const val REQUEST_PICK_ZIP = 0x5653

        /** A retail installation is about 1.5 GB; leave headroom for the copy. */
        const val REQUIRED_BYTES = 1700L shl 20

        /** How many bytes may pass between two progress callbacks. */
        const val PROGRESS_STEP_BYTES = 16L shl 20

        /** Refuse to keep unpacking once the phone is this close to full. */
        const val FREE_SPACE_FLOOR = 64L shl 20

        /** How deep inside an archive the retail installation may sit. */
        const val MAX_ARCHIVE_DEPTH = 3

        /** MIME types an archive picker accepts; a wildcard alone lists everything. */
        val ZIP_MIME_TYPES = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-zip",
            "application/octet-stream",
        )

        /** What the engine opens directly; a folder without these is not RA2. */
        val REQUIRED_ARCHIVES = listOf("ra2md.mix", "ra2.mix")

        const val TAG = "VeraZip"
    }

    /** Which picker the player chose in [showImportMethodDialog]. */
    private enum class SourceKind { FOLDER, ZIP }

    private var sourceKind = SourceKind.FOLDER

    /** Whether the player asked for the source to be removed after a good import. */
    @Volatile
    private var deleteSourceWhenDone = false

    /** The URI the player picked, kept for the post-import delete prompt. */
    @Volatile
    private var pickedSource: Uri? = null

    /** The bar in a panel sub-window, and the same bar beside the content view. */
    /** The chips: one bar, attached to the decor view, plus a popup fallback. */
    private var chipBar: LinearLayout? = null
    private var chipPopup: android.widget.PopupWindow? = null
    private val chipViews = mutableListOf<Pair<String, TextView>>()

    /** The view that owns the input connection, invisible by design. */
    private var imeEdit: ImeEditText? = null

    /** A dialog window, used only when the in-window `EditText` is refused. */
    private var imeProxy: Dialog? = null
    private var forcedKeyboard = false
    private val ui = Handler(Looper.getMainLooper())

    /**
     * Asks the player how to import: a folder tree, or a ZIP archive.
     *
     * Called from Rust on the UI thread (`ui::asset_import::choose_folder`); the
     * picker's result arrives in [onActivityResult]. The dialog is Java-side
     * because the engine's own import card has nowhere to put a second button
     * and the "delete the source afterwards" choice.
     */
    fun startFolderPicker() {
        runOnUiThread { showImportMethodDialog() }
    }

    private fun showImportMethodDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val explanation = TextView(this).apply {
            text = "文件夹：直接复制选中的目录。\n" +
                "ZIP：一边解压一边写入，不需要手机上再留一份完整副本。"
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        val deleteToggle = CheckBox(this).apply {
            text = "导入成功后提示删除源文件（省空间）"
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(explanation)
            addView(deleteToggle)
        }

        AlertDialog.Builder(this)
            .setTitle("导入游戏文件")
            .setView(container)
            .setPositiveButton("选择 ZIP 压缩包") { _, _ ->
                openPicker(SourceKind.ZIP, deleteToggle.isChecked)
            }
            .setNeutralButton("选择文件夹") { _, _ ->
                openPicker(SourceKind.FOLDER, deleteToggle.isChecked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openPicker(kind: SourceKind, deleteSource: Boolean) {
        sourceKind = kind
        deleteSourceWhenDone = deleteSource
        pickedSource = null
        val intent = when (kind) {
            SourceKind.FOLDER -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            SourceKind.ZIP -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // A provider may label an archive anything; the MIME filter only
                // narrows the list, and the extension check below is what really
                // decides whether this is a ZIP.
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, ZIP_MIME_TYPES)
            }
        }
        intent.addFlags(
            // Read for the copy, write for the optional delete, and persistable
            // because the copy can outlive the task that started it.
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        startActivityForResult(
            intent,
            if (kind == SourceKind.FOLDER) REQUEST_PICK_FOLDER else REQUEST_PICK_ZIP,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FOLDER && requestCode != REQUEST_PICK_ZIP) return
        val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
        if (uri == null) {
            // Nothing to delete afterwards, and the native side reads "" as
            // "cancelled" without needing a second callback shape.
            deleteSourceWhenDone = false
            nativeOnFolderPicked("")
            return
        }
        pickedSource = uri
        nativeOnFolderPicked(uri.toString())
    }

    /** Implemented in `android/src/lib.rs`; the import flow's entry. */
    private external fun nativeOnFolderPicked(treeUri: String)

    /** Implemented in `android/src/lib.rs`; reports the copy's outcome. */
    private external fun nativeImportFinished(result: String)

    /** Implemented in `android/src/lib.rs`; bytes copied so far. */
    private external fun nativeImportProgress(copiedBytes: Long)

    /** Uses the engine's config editor, retaining the player's other settings. */
    private external fun nativeConfigForImport(directory: String): String

    fun cancelImport(): Boolean = AssetImportJob.cancelActive()

    private var copiedBytes = 0L
    private var reportedBytes = 0L

    /**
     * Copies the chosen source into the app's own folder and points config.toml
     * at the copy.
     *
     * The copy lives here rather than in Rust because a Storage Access Framework
     * source is only readable through [android.content.ContentResolver]: its
     * `content://` URIs have no filesystem path for the engine to open. The copy
     * is one-way — the retail installation is never modified — and the engine
     * afterwards reads real files, which is what its memory-mapped asset loading
     * needs.
     *
     * Runs off the UI thread; progress arrives through [nativeImportProgress]
     * and the outcome through [nativeImportFinished] as "ok:<path>" or
     * "failed:<reason>".
     */
    fun startImport(sourceUri: String) {
        val job = AssetImportJob.begin() ?: return
        val uri = Uri.parse(sourceUri)
        val worker = Thread {
            var unpacked: File? = null
            val result = try {
                val free = filesDir.usableSpace
                if (free < REQUIRED_BYTES) {
                    throw IOException(
                        "not enough free space: " + (free shr 20) + " MB free, " +
                            (REQUIRED_BYTES shr 20) + " MB needed",
                    )
                }
                copiedBytes = 0
                reportedBytes = 0
                val target = if (DocumentsContract.isTreeUri(uri)) {
                    // Refuse a folder that is not a retail installation before
                    // copying: an empty folder would otherwise be accepted, the
                    // config would point at it, and the next launch would report
                    // missing game files with no hint about which step went wrong.
                    val missing = missingRetailArchives(uri)
                    if (missing.isNotEmpty()) {
                        throw IOException("that folder has no " + missing.joinToString(" or "))
                    }
                    job.run(filesDir,
                        { staging -> copyTree(uri, staging, job) },
                        { staging -> nativeConfigForImport(staging.absolutePath) },
                    )
                } else {
                    job.run(filesDir,
                        { staging -> unpackZip(uri, staging, job).also { unpacked = it } },
                        // An archive usually wraps the installation in one
                        // folder, so the config points at the directory that
                        // actually holds the archives, not at the staging root.
                        { staging -> nativeConfigForImport((unpacked ?: staging).absolutePath) },
                    )
                }
                "ok:" + (unpacked ?: target).absolutePath
            } catch (_: AssetImportJob.Cancelled) {
                "cancelled"
            } catch (error: Throwable) {
                "failed:" + (error.message ?: error.toString())
            }
            try {
                nativeImportFinished(result)
            } finally {
                job.close()
            }
            // Only a published import may delete the source, and never without
            // the player confirming it in the dialog this posts.
            if (result.startsWith("ok:") && deleteSourceWhenDone) {
                offerSourceDeletion(uri)
            }
        }
        try {
            worker.start()
        } catch (error: Throwable) {
            job.close()
            throw error
        }
    }

    /** Recursively copies one SAF directory into [dir]. */
    private fun copyTree(treeUri: Uri, dir: File, job: AssetImportJob) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        copyChildren(treeUri, rootId, dir, job)
    }

    /**
     * Which of the retail archives the chosen folder is missing, if any.
     *
     * The engine opens these two directly, so a folder without them cannot be a
     * Yuri's Revenge installation; catching that here keeps the config from
     * pointing at something the next launch will reject.
     */
    private fun missingRetailArchives(treeUri: Uri): List<String> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val present = mutableSetOf<String>()
        contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.lowercase()?.let(present::add)
            }
        }
        return REQUIRED_ARCHIVES.filterNot(present::contains)
    }

    private fun copyChildren(treeUri: Uri, documentId: String, dir: File, job: AssetImportJob) {
        job.checkCancelled()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                job.checkCancelled()
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val out = AssetImportJob.child(dir, name)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!out.mkdir()) throw IOException("cannot create $name")
                    copyChildren(treeUri, id, out, job)
                } else {
                    val source = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    contentResolver.openInputStream(source)?.use { input ->
                        out.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                job.checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: throw IOException("cannot read $name")
                    copiedBytes += out.length()
                    // Report in 16 MB steps: the native side only draws text,
                    // and a callback per file would flood it.
                    if (copiedBytes - reportedBytes >= PROGRESS_STEP_BYTES) {
                        reportedBytes = copiedBytes
                        nativeImportProgress(copiedBytes)
                    }
                }
            }
        } ?: throw IOException("cannot list $documentId")
    }

    /**
     * Unpacks [zipUri] straight into [staging] and returns the directory that
     * holds the retail archives.
     *
     * One stream, one destination: the archive is never copied to disk first,
     * so a phone short on space only ever needs room for the unpacked files.
     * Every entry path is rebuilt through [AssetImportJob.child], which rejects
     * absolute paths and `..`, so a crafted archive cannot write outside the
     * staging directory.
     */
    private fun unpackZip(zipUri: Uri, staging: File, job: AssetImportJob): File {
        var written = 0L
        contentResolver.openInputStream(zipUri)?.let { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    job.checkCancelled()
                    val entry = zip.nextEntry ?: break
                    // Providers name entries for whatever tool made the archive;
                    // normalise separators so the same nesting check applies.
                    val segments = entry.name.replace('\\', '/')
                        .split('/')
                        .filter { it.isNotEmpty() && it != "." }
                    if (segments.isEmpty()) {
                        zip.closeEntry()
                        continue
                    }
                    var dir = staging
                    for ((index, segment) in segments.withIndex()) {
                        val last = index == segments.size - 1
                        // A drive letter or a ".." segment would escape staging.
                        if (segment == "..") throw IOException("unsafe path in the archive: ${entry.name}")
                        if (index == 0 && segment.contains(':')) throw IOException("unsafe path in the archive: ${entry.name}")
                        if (!last || entry.isDirectory) {
                            val next = AssetImportJob.child(dir, segment)
                            if (!next.isDirectory && !next.mkdir()) throw IOException("cannot create $segment")
                            dir = next
                            continue
                        }
                        val out = AssetImportJob.child(dir, segment)
                        out.outputStream().use { output ->
                            while (true) {
                                job.checkCancelled()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                written += count
                                copiedBytes += count
                                if (copiedBytes - reportedBytes >= PROGRESS_STEP_BYTES) {
                                    reportedBytes = copiedBytes
                                    nativeImportProgress(copiedBytes)
                                    // A truncated download or a hostile archive
                                    // shows up here as a full disk mid-unpack.
                                    if (filesDir.usableSpace < FREE_SPACE_FLOOR) {
                                        throw IOException("not enough free space while unpacking")
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IOException("cannot read the archive")

        val root = locateRetailRoot(staging)
            ?: throw IOException(
                "this archive has no " + REQUIRED_ARCHIVES.joinToString(" or ") +
                    "; unpack it and check what the ZIP contains",
            )
        Log.i(TAG, "asset import: unpacked " + (written shr 20) + " MB into " + root.absolutePath)
        return root
    }

    /**
     * The directory inside an unpacked archive that the engine can open.
     *
     * Archives made on a PC usually wrap everything in one folder, so the
     * archives are one to [MAX_ARCHIVE_DEPTH] levels below the staging root.
     */
    private fun locateRetailRoot(staging: File): File? {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(staging to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val names = dir.list()?.map { it.lowercase() }?.toSet().orEmpty()
            if (REQUIRED_ARCHIVES.all(names::contains)) return dir
            if (depth >= MAX_ARCHIVE_DEPTH) continue
            dir.listFiles()?.filter { it.isDirectory }?.forEach { queue.add(it to depth + 1) }
        }
        return null
    }

    /**
     * Offers to delete the source the player picked, after a good import.
     *
     * Deleting frees the space the import just spent, but it is irreversible, so
     * the prompt names what will go and does nothing unless confirmed.
     */
    private fun offerSourceDeletion(source: Uri) {
        val name = displayName(source) ?: "所选文件"
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            AlertDialog.Builder(this)
                .setTitle("删除源文件？")
                .setMessage("游戏文件已经复制到 App 目录，可以正常启动游戏。\n\n是否删除手机上的「$name」？删除后无法恢复。")
                .setPositiveButton("删除") { _, _ ->
                    Thread {
                        val deleted = deleteSource(source)
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                if (deleted) "已删除「$name」，空间已释放" else "删除失败：系统不允许删除这个位置",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }.start()
                }
                .setNegativeButton("保留", null)
                .show()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (error: Throwable) {
        Log.w(TAG, "asset import: cannot read the source name: ${error.message}")
        null
    }

    /**
     * Deletes what the player picked.
     *
     * A single document (the ZIP) goes through [DocumentsContract.deleteDocument];
     * a tree has to be emptied child by child first, because most providers
     * refuse to remove a directory that still has contents. Anything the
     * provider (or the scoped-storage grant) refuses is reported, never retried
     * blind.
     */
    private fun deleteSource(source: Uri): Boolean = try {
        if (DocumentsContract.isTreeUri(source)) {
            val rootId = DocumentsContract.getTreeDocumentId(source)
            if (!deleteTreeChildren(source, rootId)) {
                Log.w(TAG, "asset import: could not remove every file under the source")
            }
            val rootDocument = DocumentsContract.buildDocumentUriUsingTree(source, rootId)
            DocumentsContract.deleteDocument(contentResolver, rootDocument)
        } else {
            DocumentsContract.deleteDocument(contentResolver, source)
        }
    } catch (error: Throwable) {
        Log.w(TAG, "asset import: the source could not be deleted: ${error.message}")
        false
    }

    private fun deleteTreeChildren(treeUri: Uri, documentId: String): Boolean {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var complete = true
        contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val mime = cursor.getString(1)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!deleteTreeChildren(treeUri, id)) complete = false
                    val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    if (!tryDelete(child)) complete = false
                } else {
                    val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    try {
                        DocumentsContract.deleteDocument(contentResolver, child)
                    } catch (error: Throwable) {
                        Log.w(TAG, "asset import: cannot delete a file in the source: ${error.message}")
                        complete = false
                    }
                }
            }
        }
        return complete
    }

    private fun tryDelete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(contentResolver, uri)
    } catch (error: Throwable) {
        Log.w(TAG, "asset import: cannot delete a folder in the source: ${error.message}")
        false
    }

    // --- keyboard ------------------------------------------------------------
    //
    // `NativeActivity` gives winit no input connection, so `set_ime_allowed`
    // is a no-op and winit never emits an `Ime` event: the game window can
    // never raise the system keyboard by itself. What does work is a real
    // `EditText` inside this activity: it owns an `InputConnection`, the
    // system treats it like any text field, and every commit arrives on it
    // even when the game's surface holds the screen.
    //
    // The engine already knows when a text field takes input (it sets the
    // same `ime.allowed` flag desktop winit uses). This activity polls that
    // flag and raises or lowers the keyboard to match, so the player types
    // into a save-name box or a chat line the way they type anywhere else —
    // no button to find first. Commits, backspace and the done key are
    // forwarded to the engine, which owns the actual edit and draws it.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installChips()
        ui.post(imeLoop)
    }

    override fun onResume() {
        super.onResume()
        installChips()
        ui.removeCallbacks(imeLoop)
        ui.post(imeLoop)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) installChips()
    }

    override fun onPause() {
        ui.removeCallbacks(imeLoop)
        super.onPause()
    }

    /** Ask for the keyboard whenever the engine has a field open; drop it after. */
    private val imeLoop = object : Runnable {
        override fun run() {
            val open = runCatching { nativeTextEntryOpen() }.getOrDefault(false)
            if (open || forcedKeyboard) raiseKeyboard() else lowerKeyboard()
            ui.postDelayed(this, 250)
        }
    }

    // --- chips ---------------------------------------------------------------

    /**
     * The modifier chips live in the decor view, above the engine's surface, and
     * are re-attached on every focus change: `setContentView` on the native
     * thread can wipe a sibling added before the surface existed.
     */
    private fun installChips() {
        val decor = window?.decorView as? ViewGroup ?: return
        val bar = chipBar ?: buildChipBar().also { chipBar = it }
        if (bar.parent == null) {
            try {
                decor.addView(bar, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(10)
                    rightMargin = dp(10)
                })
                bar.bringToFront()
            } catch (error: Throwable) {
                Log.w(TAG, "chip bar refused: ${error.message}")
                showChipPopup(decor, bar)
            }
        } else {
            bar.bringToFront()
        }
        syncModifierChips()
    }

    /**
     * Some ROMs draw a `SurfaceView` over sibling views in the same window.
     * A popup is its own window on top of the activity, so it is the fallback
     * when the decor attach cannot be seen.
     */
    private fun showChipPopup(anchor: View, bar: LinearLayout) {
        if (chipPopup != null) return
        try {
            val popup = android.widget.PopupWindow(
                bar,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                false,
            )
            popup.isOutsideTouchable = true
            popup.showAtLocation(anchor, Gravity.TOP or Gravity.END, dp(10), dp(10))
            chipPopup = popup
        } catch (error: Throwable) {
            Log.w(TAG, "chip popup refused: ${error.message}")
        }
    }

    private fun buildChipBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xCC14161A.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            alpha = 0.92f
        }
        bar.addView(makeChip("⌨") {
            forcedKeyboard = !forcedKeyboard
            if (forcedKeyboard) raiseKeyboard(force = true) else lowerKeyboard()
            syncModifierChips()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(4) })
        for (name in listOf("ctrl", "shift", "alt")) {
            val chip = makeChip(label(name)) { toggleModifier(name) }
            chipViews += name to chip
            bar.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(4) })
        }
        return bar
    }

    private fun label(name: String): String = when (name) {
        "ctrl" -> "Ctrl"
        "shift" -> "Shift"
        else -> "Alt"
    }

    private fun makeChip(title: String, tap: () -> Unit): TextView {
        val chip = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x33FFFFFF)
                setStroke(dp(1), 0x44FFFFFF)
            }
            setOnClickListener { tap() }
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var baseX = 0f
        var baseY = 0f
        var dragging = false
        chip.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    baseX = view.translationX
                    baseY = view.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val bar = chipBar ?: return@setOnTouchListener true
                        bar.translationX = baseX + dx
                        bar.translationY = (baseY + dy).coerceAtLeast(0f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    true
                }
                else -> true
            }
        }
        return chip
    }

    private fun toggleModifier(name: String) {
        val lit = try {
            when (name) {
                "ctrl" -> (nativeModifierMask() and 1) != 0
                "shift" -> (nativeModifierMask() and 2) != 0
                else -> (nativeModifierMask() and 4) != 0
            }
        } catch (_: Throwable) {
            false
        }
        try {
            nativeSetModifier(name, !lit)
        } catch (error: Throwable) {
            Log.w(TAG, "modifier chip: ${error.message}")
        }
        syncModifierChips()
    }

    /** Repaint the chips from the engine's own flag word. */
    private fun syncModifierChips() {
        val mask = try {
            nativeModifierMask()
        } catch (_: Throwable) {
            0
        }
        for ((name, chip) in chipViews) {
            val bit = when (name) {
                "ctrl" -> 1
                "shift" -> 2
                else -> 4
            }
            val lit = (mask and bit) != 0
            chip.setTextColor(if (lit) COL_DARK_TEXT else Color.WHITE)
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (lit) COL_ACCENT else 0x33FFFFFF)
                setStroke(dp(1), if (lit) 0xFFF4C566.toInt() else 0x44FFFFFF)
            }
        }
    }

    // --- the input connection ------------------------------------------------

    /**
     * The view that owns the keyboard. It carries no visible text: every
     * commit is handed to the engine and swallowed here, so the engine's own
     * edit stays the single source of truth and the caret is drawn by the
     * game, not by this widget.
     */
    private inner class ImeEditText(activity: Activity) : EditText(activity) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val base = super.onCreateInputConnection(outAttrs) ?: return null
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return object : InputConnectionWrapper(base, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val run = text?.toString().orEmpty()
                    if (run.isNotEmpty()) forward { nativeTypeText(run) }
                    return true
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                    true

                override fun finishComposingText(): Boolean = true

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength) { forward { nativeTextBackspace() } }
                    return true
                }

                override fun deleteSurroundingTextInCodePoints(
                    beforeLength: Int,
                    afterLength: Int,
                ): Boolean {
                    repeat(beforeLength) { forward { nativeTextBackspace() } }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DEL -> forward { nativeTextBackspace() }
                            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                                forward { nativeTextEnter() }
                        }
                    }
                    return true
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    forward { nativeTextEnter() }
                    return true
                }
            }
        }

        /** JNI calls from the UI thread; a dead engine must not crash the activity. */
        private inline fun forward(call: () -> Unit) {
            try {
                call()
            } catch (error: Throwable) {
                Log.w(TAG, "keyboard forward: ${error.message}")
            }
        }
    }

    private fun installImeEdit(): ImeEditText? {
        imeEdit?.let { return it }
        val decor = window?.decorView as? ViewGroup ?: return null
        val edit = ImeEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSingleLine = true
            setTextColor(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
        }
        val host = FrameLayout(this)
        host.addView(edit, FrameLayout.LayoutParams(1, 1))
        try {
            decor.addView(host, FrameLayout.LayoutParams(1, 1).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            })
        } catch (error: Throwable) {
            Log.w(TAG, "ime host refused: ${error.message}")
            return null
        }
        imeEdit = edit
        return edit
    }

    private fun imm(): InputMethodManager? =
        getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun keyboardVisible(): Boolean {
        val decor = window?.decorView ?: return false
        if (imm()?.isActive == true) return true
        val frame = android.graphics.Rect()
        decor.getWindowVisibleDisplayFrame(frame)
        val hidden = decor.height - frame.bottom
        return hidden > dp(120)
    }

    /**
     * Raise the keyboard. Try the in-window `EditText` first — it keeps the
     * window focused, so the engine keeps running — and fall back to a dialog
     * window if the device refuses to attach an input method to it.
     */
    private fun raiseKeyboard(force: Boolean = false) {
        if (keyboardVisible()) return
        val edit = installImeEdit()
        if (edit != null) {
            edit.requestFocus()
            edit.post {
                val shown = imm()?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) == true
                if (!shown) imm()?.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
                ui.postDelayed({
                    if (!keyboardVisible()) {
                        Log.i(TAG, "keyboard: in-window refused, falling back to the dialog")
                        openProxyKeyboard()
                    }
                }, 450)
            }
            return
        }
        openProxyKeyboard()
    }

    /** A dialog window is the last resort: it always owns an input connection. */
    private fun openProxyKeyboard() {
        if (imeProxy?.isShowing == true) return
        val edit = ImeEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setTextColor(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(edit, ViewGroup.LayoutParams(1, 1))
            setCancelable(false)
        }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0f)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
            setGravity(Gravity.BOTTOM)
            setLayout(1, 1)
        }
        dialog.setOnShowListener {
            edit.requestFocus()
            edit.postDelayed({
                imm()?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        }
        try {
            dialog.show()
            imeProxy = dialog
            Log.i(TAG, "keyboard: dialog proxy shown")
        } catch (error: Throwable) {
            Log.w(TAG, "ime proxy refused: ${error.message}")
            imeProxy = null
        }
    }

    private fun lowerKeyboard() {
        imeProxy?.takeIf { it.isShowing }?.dismiss()
        imeProxy = null
        imeEdit?.let {
            it.text.clear()
            it.clearFocus()
            imm()?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    /** Density-independent pixels to pixels. */
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Implemented in `android/src/lib.rs`; the overlay's finished text. */
    private external fun nativeTypeText(text: String)

    /** Implemented in `android/src/lib.rs`; whether any text field takes text now. */
    private external fun nativeTextEntryOpen(): Boolean

    /** Implemented in `android/src/lib.rs`; one backspace into the focused edit. */
    private external fun nativeTextBackspace()

    /** Implemented in `android/src/lib.rs`; the keyboard's done key. */
    private external fun nativeTextEnter()

    /** Implemented in `android/src/lib.rs`; hold or release one modifier chip. */
    private external fun nativeSetModifier(name: String, down: Boolean): Boolean

    /** Implemented in `android/src/lib.rs`; which chips are held (1 Ctrl, 2 Shift, 4 Alt). */
    private external fun nativeModifierMask(): Int

    override fun onDestroy() {
        ui.removeCallbacks(imeLoop)
        try {
            chipPopup?.dismiss()
        } catch (_: Throwable) {
        }
        try {
            imeProxy?.takeIf { it.isShowing }?.dismiss()
        } catch (_: Throwable) {
        }
        chipBar?.let { bar -> (bar.parent as? ViewGroup)?.removeView(bar) }
        super.onDestroy()
    }
}
