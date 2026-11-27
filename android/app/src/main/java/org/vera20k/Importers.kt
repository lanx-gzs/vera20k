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

    // --- mods ---------------------------------------------------------------

    /**
     * The folder the engine keeps its own settings in: `[paths] settings_dir`
     * when config.toml names one, else the app's files folder — which is what
     * the engine itself falls back to on Android.
     */
    fun settingsDir(context: Context): File {
        val raw = configPathsValue(context, "settings_dir")
        val dir = when {
            raw.isNullOrBlank() -> context.filesDir
            File(raw).isAbsolute -> File(raw)
            else -> File(context.filesDir, raw)
        }
        dir.mkdirs()
        return dir
    }

    /** `Mods` under the settings folder: one subfolder per mod. */
    fun modsDir(context: Context): File = File(settingsDir(context), "Mods").apply { mkdirs() }

    /** The folder `[paths] mod` names; null means the original game. */
    fun activeMod(context: Context): String? =
        configPathsValue(context, "mod")?.takeIf { it.isNotBlank() }

    /**
     * Keep the mod the next launch plays, as `[paths] mod` in the config the
     * engine reads; null (or blank) plays the original game. Refused while no
     * config exists: the engine would then report a missing `ra2_dir`, not a
     * missing game, and the player would lose the wizard's explanation.
     */
    fun setActiveMod(context: Context, folder: String?): Boolean {
        val config = File(context.filesDir, "config.toml")
        if (!config.isFile) return false
        return try {
            val updated = setPathsKey(config.readText(), "mod", folder.orEmpty())
            val temp = File(config.parentFile, "config.toml.tmp")
            temp.writeText(updated)
            if (!temp.renameTo(config)) temp.copyTo(config, overwrite = true)
            log("config.toml mod -> " + (folder ?: "(original)"))
            true
        } catch (error: Throwable) {
            log("config.toml mod write failed: " + error.message)
            false
        }
    }

    /** The value under `[paths] key` in config.toml, unquoted; null when absent. */
    fun configPathsValue(context: Context, key: String): String? {
        val config = File(context.filesDir, "config.toml")
        if (!config.isFile) return null
        var section = ""
        val lines = try {
            config.readLines()
        } catch (_: Throwable) {
            return null
        }
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                section = line.removePrefix("[").substringBefore(']').trim()
                continue
            }
            if (section != "paths" || line.startsWith("#") || !line.contains('=')) continue
            if (line.substringBefore('=').trim() != key) continue
            return tomlString(line.substringAfter('='))
        }
        return null
    }

    /** A TOML string value with its quotes taken off; bare words kept whole. */
    private fun tomlString(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val quote = text.first()
        if (quote == '"' || quote == '\'') {
            val end = text.indexOf(quote, 1)
            return if (end < 0) null else text.substring(1, end)
        }
        return text.substringBefore(' ').substringBefore('\t')
    }

    /**
     * The mod's own folder inside an unpacked archive.
     *
     * Archives are made in three shapes: the mod's files at the root, one
     * wrapper folder, or a wrapper folder beside a README. A mod left one level
     * deeper than `Mods` is never read, so the wrapper is stepped out of —
     * but only when the level holds no mod files of its own, so a real mod tree
     * (with its own `Rules/`, `Art/`…) is never flattened by mistake.
     */
    fun collapseSingleFolder(root: File): File {
        var dir = root
        repeat(3) {
            val children = (dir.listFiles() ?: return dir).filterNot { noise(it.name) }
            if (children.isEmpty()) return dir
            val folders = children.filter { it.isDirectory }
            val files = children.filterNot { it.isDirectory }
            // Files that are part of a mod — anything but a note beside it.
            if (files.any { !isNote(it.name) }) return dir
            if (folders.size != 1) return dir
            dir = folders[0]
        }
        return dir
    }

    /** Names a desktop archive leaves behind: never a mod's own folder. */
    private fun noise(name: String): Boolean =
        name.startsWith('.') ||
            name.equals("__MACOSX", ignoreCase = true) ||
            name.equals("Thumbs.db", ignoreCase = true) ||
            name.equals("desktop.ini", ignoreCase = true)

    /** A readme or screenshot that ships beside a mod's folder. */
    private fun isNote(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in setOf(
            "txt", "md", "pdf", "doc", "docx", "rtf", "html", "htm",
            "url", "jpg", "jpeg", "png", "gif", "bmp",
        )
    }

    /** Removes what a desktop archive leaves at a mod's root (`__MACOSX`). */
    fun stripArchiveNoise(mod: File) {
        mod.listFiles()?.forEach { child ->
            if (noise(child.name)) deleteTree(child)
        }
    }

    /** A folder name that is safe on every file system; "模组" when nothing is left. */
    fun plainFolderName(name: String): String {
        val cleaned = name
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), " ")
            .trim()
            .trim('.')
            .trim()
        return cleaned.ifBlank { "模组" }
    }

    /** `dir/name`, or `dir/name (2)` and on while one of those names is there. */
    fun uniqueDir(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        var index = 2
        while (true) {
            target = File(dir, "$name ($index)")
            if (!target.exists()) return target
            index++
        }
    }

    /** Move a tree whole; a rename the file system refuses falls back to a copy. */
    fun moveTree(source: File, target: File): Boolean {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return true
        return try {
            copyFiles(source, target)
            deleteTree(source)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun copyFiles(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { copyFiles(it, File(target, it.name)) }
            return
        }
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    /** Remove a tree best-effort; false when something survived. */
    fun deleteTree(root: File): Boolean {
        if (root.isDirectory) {
            root.listFiles()?.forEach { deleteTree(it) }
        }
        return root.delete() || !root.exists()
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
