package com.hintvault.storage

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SafVaultFileTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var directory: DocumentFile

    @Before
    fun setUp() {
        // 每個測試獨立的目錄，避免上一個測試留下的兄弟檔案互相干擾
        dir = File(context.cacheDir, "saf-test-${System.nanoTime()}").apply { mkdirs() }
        directory = DocumentFile.fromFile(dir)
        File(dir, "saf-test.kdbx").writeBytes("v1".toByteArray())
    }

    private fun subject() = SafVaultFile(context, directory, "saf-test.kdbx")

    @Test
    fun readsBytesThroughContentResolver() {
        assertArrayEquals("v1".toByteArray(), subject().read())
    }

    @Test
    fun writesBytesThroughContentResolver() {
        subject().write("v2-longer".toByteArray())
        assertArrayEquals("v2-longer".toByteArray(), File(dir, "saf-test.kdbx").readBytes())
    }

    @Test
    fun writeTruncatesWhenNewContentIsShorter() {
        // SAF 的 "w" 模式不保證截斷，殘留的尾巴會讓 KDBX 檔損毀
        File(dir, "saf-test.kdbx").writeBytes("a-very-long-previous-version".toByteArray())
        subject().write("short".toByteArray())
        assertArrayEquals("short".toByteArray(), File(dir, "saf-test.kdbx").readBytes())
    }

    @Test
    fun reportsLastModified() {
        assertTrue(subject().lastModified() > 0)
    }

    @Test
    fun writeSiblingCreatesFileNextToOriginal() {
        val name = subject().writeSibling(".conflict-2026.kdbx", "local".toByteArray())
        val sibling = File(dir, name)
        assertTrue(sibling.exists())
        assertArrayEquals("local".toByteArray(), sibling.readBytes())
    }

    @Test
    fun writeSiblingNeverOverwritesAnExistingFile() {
        // Task 7 review 釘住的合約：撞名絕不可覆蓋既有檔案，須回傳實際使用的檔名
        val first = subject().writeSibling(".conflict.kdbx", "first".toByteArray())
        val second = subject().writeSibling(".conflict.kdbx", "second".toByteArray())

        assertNotEquals("撞名時檔名必須不同", first, second)
        assertArrayEquals("first".toByteArray(), File(dir, first).readBytes())
        assertArrayEquals("second".toByteArray(), File(dir, second).readBytes())
    }

    @Test
    fun writeSiblingDoesNotAppendAnUnwantedExtension() {
        // "application/octet-stream" 這個 MIME type 在部分裝置上會被自動加上
        // .bin 副檔名，讓 KeePassXC 認不出這是它的格式
        val name = subject().writeSibling(".conflict-2026.kdbx", "local".toByteArray())
        assertTrue("不該被加上非預期的副檔名，實際檔名是 $name", name.endsWith(".kdbx"))
    }

    @Test
    fun displayNameIsTheFileName() {
        assertEquals("saf-test.kdbx", subject().displayName)
    }
}
