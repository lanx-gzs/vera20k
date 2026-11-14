package org.vera20k

import java.util.Locale

/**
 * Which icon a name gets in the browser, and whether a tap on it opens a
 * viewer instead of the action sheet.
 *
 * The icons come from the player-supplied art pack (`res/drawable-nodpi`):
 * a folder, the coloured type tiles and a "unknown" tile. The line-art menu
 * icons from the same pack are tinted at use, so one black stroke set serves
 * both the toolbar and the chevrons on the dark surface.
 */
internal object FileKinds {
    private val TEXT = setOf(
        "txt", "log", "md", "ini", "cfg", "toml", "json", "xml", "csv", "yml",
        "yaml", "lst", "nfo", "bat", "cmd", "sh", "conf", "properties", "srt",
        "rs", "kt", "java", "js", "css", "html", "htm", "c", "h", "cpp", "py",
    )

    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "heic")

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.US)
    }

    fun icon(name: String, isDirectory: Boolean): Int = when {
        isDirectory -> R.drawable.ic_folder
        else -> when (extensionOf(name)) {
            in IMAGE -> R.drawable.ic_image
            in setOf("mp3", "wav", "ogg", "m4a", "flac", "aac") -> R.drawable.ic_audio
            in setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "wmv") -> R.drawable.ic_video
            "html", "htm" -> R.drawable.ic_html
            in TEXT -> R.drawable.ic_text
            in setOf("zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "z01") -> R.drawable.ic_archive
            else -> R.drawable.ic_unknown
        }
    }

    fun isTextViewable(name: String): Boolean = extensionOf(name) in TEXT

    fun isImageViewable(name: String): Boolean = extensionOf(name) in IMAGE
}
