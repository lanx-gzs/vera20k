package org.vera20k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The 7z path is the one thing compiling cannot check: it needs a real LZMA
 * decoder at runtime. The fixture is a small solid archive with a folder, a
 * text file and a 4 KiB binary blob, packed by py7zr.
 */
class ArchivesTest {
    private fun fixture(): File {
        val stream = javaClass.getResourceAsStream("/sample.7z")
        assertNotNull("missing test fixture", stream)
        val file = File.createTempFile("vera", ".7z").apply { deleteOnExit() }
        stream!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun head(file: File, count: Int): ByteArray {
        val bytes = ByteArray(count)
        file.inputStream().use { input ->
            assertEquals(count, input.read(bytes))
        }
        return bytes
    }

    @Test
    fun extractsASolidSevenZip() {
        val archive = fixture()
        val target = Files.createTempDirectory("vera-out").toFile()

        val (files, bytes) = Archives.extractSevenZip(archive, target) { _, _ -> }

        assertTrue("no entries unpacked", files >= 2)
        assertTrue("no bytes written", bytes > 4000)

        val ini = File(target, "Mod/mod.ini")
        val blob = File(target, "Mod/Sub/blob.bin")
        assertTrue("mod.ini missing", ini.isFile)
        assertTrue("blob.bin missing", blob.isFile)
        assertEquals("name=Vera Test\n", ini.readText())
        assertEquals(4000L, blob.length())
    }

    @Test
    fun readsMagicNotExtension() {
        val archive = fixture()
        // The name says nothing; the header has to decide.
        val renamed = File(archive.parentFile, "actually-a-7z.zip")
        Files.copy(archive.toPath(), renamed.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        renamed.deleteOnExit()
        assertEquals("7z", Archives.kindOf(head(renamed, 6)))
    }

    @Test
    fun namesRarInsteadOfGuessing() {
        val rar = File.createTempFile("vera", ".rar").apply { deleteOnExit() }
        rar.writeBytes(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(), 0x1A, 0x07))
        assertEquals("rar", Archives.kindOf(head(rar, 6)))
    }
}
