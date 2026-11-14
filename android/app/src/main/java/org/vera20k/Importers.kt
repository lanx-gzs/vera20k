package org.vera20k

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Copies and unpacks content-URI sources into the installation's own folders.
 *
 * A `content://` URI has no path the engine can open, so anything imported has
 * to be turned into a real file first. Both entry points stream, never read the
 * whole source into memory: a resource archive is larger than the heap.
 */
internal object Importers {
    /** The name the picker shows for a document, falling back to the URI's last segment. */
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
    }

    /** Copy one document into `destination`. */
    fun copyDocument(context: Context, uri: Uri, destination: File, onBytes: (Long) -> Unit) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开来源")
        input.use { source ->
            destination.parentFile?.mkdirs()
            destination.outputStream().use { sink ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    total += read
                    onBytes(total)
                }
            }
        }
    }

    /**
     * Unpack a ZIP into `destination`, one entry at a time.
     *
     * Every entry is resolved and re-checked against the destination root: a
     * crafted archive ("../../files/config.toml") would otherwise write outside
     * the folder the player chose. Encrypted entries cannot be read by
     * `java.util.zip`, and are reported rather than half-written.
     */
    fun extractZip(
        context: Context,
        uri: Uri,
        destination: File,
        onProgress: (files: Int, bytes: Long) -> Unit,
    ): Pair<Int, Long> {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开压缩包")
        var files = 0
        var bytes = 0L
        destination.mkdirs()
        ZipInputStream(input.buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val name = entry.name
                if (name.isNullOrBlank()) continue
                val target = File(destination, name)
                val rootPath = destination.canonicalPath
                if (!target.canonicalPath.startsWith(rootPath + File.separator) &&
                    target.canonicalPath != rootPath
                ) {
                    throw IOException("压缩包里的路径不合法：$name")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = archive.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            bytes += read
                        }
                    }
                    files++
                    onProgress(files, bytes)
                }
                archive.closeEntry()
            }
        }
        return Pair(files, bytes)
    }

    /** Whether `child` lives inside `parent`, used to refuse moving a folder into itself. */
    fun isInside(parent: File, child: File): Boolean = try {
        val parentPath = parent.canonicalPath
        val childPath = child.canonicalPath
        childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    } catch (_: IOException) {
        false
    }

    /**
     * The installation folder the engine was pointed at.
     *
     * `config.toml` is the engine's file; this only reads the one key the import
     * wrote, so a player who installed somewhere unusual still lands in the right
     * folder. Falls back to the app's own `files/ra2`, which needs no permission.
     */
    fun gameDir(context: Context): File {
        val config = File(context.filesDir, "config.toml")
        if (config.isFile) {
            val line = config.readLines().firstOrNull { it.trimStart().startsWith("ra2_dir") }
            val path = line?.let { Regex("\"([^\"]*)\"").find(it)?.groupValues?.getOrNull(1) }
                ?: line?.let { Regex("'([^']*)'").find(it)?.groupValues?.getOrNull(1) }
            if (!path.isNullOrBlank()) return File(path)
        }
        return File(context.filesDir, "ra2").apply { mkdirs() }
    }

    /** Both masters the engine requires, in the order it names them. */
    private val MASTERS = listOf("ra2md.mix", "ra2.mix")

    /**
     * Whether `dir` itself holds both masters.
     *
     * The engine looks in this folder only — no recursion, names compared
     * case-insensitively — so anything one level deeper reads as "missing" no
     * matter how much was copied.
     */
    fun holdsMasters(dir: File): Boolean = MASTERS.all { name ->
        dir.listFiles()?.any { it.isFile && it.name.equals(name, ignoreCase = true) } == true
    }

    /**
     * The folder that actually holds the masters: `dir`, or a subfolder within
     * a few levels — a picked parent folder often contains the game one level
     * deeper. Returns null when the archives are not there at all.
     */
    fun locateMasters(dir: File): File? {
        if (holdsMasters(dir)) return dir
        var frontier = listOf(dir)
        repeat(3) {
            val next = mutableListOf<File>()
            for (parent in frontier) {
                parent.listFiles()?.forEach { child ->
                    if (!child.isDirectory) return@forEach
                    if (holdsMasters(child)) return child
                    next += child
                }
            }
            frontier = next
        }
        return null
    }

    /**
     * Point `config.toml` at [dir], the one key the engine reads before it
     * decides the game is missing. Writes beside the file and renames, so a
     * half-written config cannot leave the player with a startup error instead
     * of a game folder.
     */
    fun registerGameDir(context: Context, dir: File): Boolean = try {
        val config = File(context.filesDir, "config.toml")
        val source = if (config.isFile) {
            config.readText()
        } else {
            // Same starting point the engine uses when the file is absent: the
            // shipped template, with the settings folder beside it.
            context.assets.open("config.toml.example")
                .bufferedReader().use { it.readText() }
        }
        val updated = setPathsKey(
            setPathsKey(source, "ra2_dir", dir.absolutePath),
            "settings_dir",
            if (config.isFile) null else ".",
        )
        val temp = File(context.filesDir, "config.toml.tmp")
        temp.writeText(updated)
        val moved = temp.renameTo(config)
        if (!moved) temp.copyTo(config, overwrite = true)
        log("config.toml -> " + dir.absolutePath)
        true
    } catch (error: Throwable) {
        log("config.toml write failed: " + error.message)
        false
    }

    private fun log(message: String) = android.util.Log.i("VeraZip", message)

    /**
     * `set_paths_key` from `src/util/config.rs`: replace the value under
     * `[paths]`, insert it there when the key is only a comment, and fall back
     * to appending the section. Kept compatible so the engine re-reads this file
     * exactly as if its own editor had written it.
     */
    private fun setPathsKey(text: String, key: String, value: String?): String {
        if (value == null) return text
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.lines().toMutableList()
        var header = -1
        var assigned = -1
        var section = ""
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                section = line.removePrefix("[").substringBefore(']').trim()
                if (section == "paths" && header < 0) header = index
                continue
            }
            if (section != "paths" || line.startsWith("#") || !line.contains('=')) continue
            if (line.substringBefore('=').trim() == key) assigned = index
        }
        val assignment = "$key = ${quote(value)}"
        when {
            assigned >= 0 -> lines[assigned] = assignment
            header >= 0 -> lines.add(header + 1, assignment)
            else -> {
                lines.add("")
                lines.add("[paths]")
                lines.add(assignment)
            }
        }
        return lines.joinToString(newline)
    }

    /** A TOML basic string, with the other quote style when the path has one. */
    private fun quote(value: String): String = when {
        value.contains('"') -> "'" + value.replace("'", "") + "'"
        else -> "\"" + value.replace("\\", "\\\\") + "\""
    }

    /**
     * Copy a folder picked with ACTION_OPEN_DOCUMENT_TREE into `destination`.
     *
     * Everything is copied immediately: the tree grant lasts only as long as this
     * activity, and a remembered URI would be unreadable next launch. SAF exposes
     * document ids rather than paths, so the walk is driven by ids.
     */
    fun copyTree(
        context: Context,
        tree: Uri,
        destination: File,
        onProgress: (files: Int, bytes: Long) -> Unit,
    ): Pair<Int, Long> {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var files = 0
        var bytes = 0L

        fun children(documentId: String): List<Triple<String, String, String>> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val cursor = resolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            ) ?: return emptyList()
            val result = mutableListOf<Triple<String, String, String>>()
            cursor.use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getString(0) ?: continue
                    val name = rows.getString(1) ?: continue
                    result += Triple(id, name, rows.getString(2) ?: "")
                }
            }
            return result
        }

        fun walk(documentId: String, dir: File) {
            dir.mkdirs()
            val rootPath = destination.canonicalPath
            for ((id, name, mime) in children(documentId)) {
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(id, File(dir, name.replace('/', '_')))
                    continue
                }
                val target = File(dir, name.replace('/', '_'))
                if (!target.canonicalPath.startsWith(rootPath + File.separator)) continue
                val source = resolver.openInputStream(
                    DocumentsContract.buildDocumentUriUsingTree(tree, id),
                ) ?: continue
                source.use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            bytes += read
                        }
                    }
                }
                files++
                onProgress(files, bytes)
            }
        }

        walk(rootId, destination)
        return Pair(files, bytes)
    }
}

/**
 * The few switches this app keeps between launches.
 *
 * SharedPreferences rather than the engine's `config.toml`: these are this
 * build's own screen options, and the engine owns that file.
 */
internal object Prefs {
    const val SHOW_HIDDEN = "show_hidden"
    const val SHOW_SIZE = "show_size"
    const val ENCODING = "encoding"
    const val SKIP_INSPECTOR = "skip_inspector"

    private const val FILE = "verazip"

    fun getBoolean(context: Context, key: String, fallback: Boolean): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, fallback)

    fun setBoolean(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    fun getString(context: Context, key: String, fallback: String): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, fallback) ?: fallback

    fun setString(context: Context, key: String, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }
}
