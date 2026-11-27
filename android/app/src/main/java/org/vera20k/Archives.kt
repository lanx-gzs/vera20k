package org.vera20k

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException

/**
 * The archive formats Java cannot read on its own.
 *
 * `java.util.zip` covers ZIP only, and most of what a Chinese CNC site hands
 * out is 7z: a full Yuri's Revenge tree in ZIP would be unusably large, so
 * those uploads are packed solid. Apache Commons Compress reads 7z (LZMA,
 * LZMA2, BZip2, Deflate, solid and non-solid) with the XZ codec underneath,
 * which keeps this a pure-Java dependency: no second native library per ABI,
 * nothing to build on a machine that already struggles with the engine.
 *
 * Pure `File` in, `File` out, so the unpacker can be tested on a plain JVM
 * without an Android context.
 */
internal object Archives {
    /** 7z's signature: `37 7A BC AF 27 1C`. */
    private val SEVEN_ZIP_MAGIC = byteArrayOf(
        0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C,
    )

    /** RAR's signature: `Rar!`. Not supported; told apart to say so clearly. */
    private val RAR_MAGIC = byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte())

    /** The magic of the first bytes, or "" when the stream could not be read. */
    fun kindOf(head: ByteArray): String = when {
        head.startsWith(SEVEN_ZIP_MAGIC) -> "7z"
        head.startsWith(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())) -> "zip"
        head.startsWith(RAR_MAGIC) -> "rar"
        else -> ""
    }

    private fun ByteArray.startsWith(magic: ByteArray): Boolean {
        if (size < magic.size) return false
        for (index in magic.indices) {
            if (this[index] != magic[index]) return false
        }
        return true
    }

    /**
     * Unpack a 7z into `destination`, one entry at a time.
     *
     * Every entry is resolved and re-checked against the destination root, as
     * the ZIP path does: a crafted archive ("../../files/config.toml") would
     * otherwise write outside the folder the player chose. Entries are streamed,
     * never materialised in memory, so a solid multi-gigabyte archive still only
     * costs its unpacked size on disk.
     */
    fun extractSevenZip(
        archive: File,
        destination: File,
        onProgress: (files: Int, bytes: Long) -> Unit,
    ): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        destination.mkdirs()
        val rootPath = destination.canonicalPath
        SevenZFile.builder().setFile(archive).get().use { sevenZip ->
            while (true) {
                val entry = sevenZip.nextEntry ?: break
                val name = entry.name
                if (name.isNullOrBlank()) continue
                val target = File(destination, name)
                val targetPath = target.canonicalPath
                if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
                    throw IOException("压缩包里的路径不合法：$name")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { sink ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = sevenZip.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        bytes += read
                    }
                }
                files++
                onProgress(files, bytes)
            }
        }
        return Pair(files, bytes)
    }
}
