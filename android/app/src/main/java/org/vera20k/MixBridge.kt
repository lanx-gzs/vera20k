package org.vera20k

import org.json.JSONObject

/**
 * Static bridge to the Rust MIX and map-preview readers.
 *
 * The engine already owns a MIX reader (RSA-wrapped Blowfish index, CRC-32
 * filename hashes, nested archives) and an LZO decoder for `[PreviewPack]`.
 * Calling those over JNI avoids porting all of it to Kotlin, where a
 * hand-written Blowfish would be one more place to get an S-box wrong.
 *
 * The library is only loaded when one of these is first used, so the file
 * manager keeps working even if the engine library is missing from the APK.
 */
internal object MixBridge {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("vera20k_android")
    }.isSuccess

    private external fun nativeEntries(path: String, parentId: Int): String
    private external fun nativeExtract(path: String, parentId: Int, entryId: Int, outPath: String): Boolean
    private external fun nativeMapPreview(path: String): ByteArray

    fun entries(path: String, parentId: Int = 0): JSONObject? {
        if (!loaded) return null
        return runCatching { JSONObject(nativeEntries(path, parentId)) }.getOrNull()
    }

    fun extract(path: String, parentId: Int, entryId: Int, outPath: String): Boolean {
        if (!loaded) return false
        return runCatching { nativeExtract(path, parentId, entryId, outPath) }.getOrDefault(false)
    }

    /** `[width][height][RGBA]`, or null when the map carries no preview. */
    fun mapPreview(path: String): ByteArray? {
        if (!loaded) return null
        return runCatching { nativeMapPreview(path) }
            .getOrNull()
            ?.takeIf { it.size > 8 }
    }
}
