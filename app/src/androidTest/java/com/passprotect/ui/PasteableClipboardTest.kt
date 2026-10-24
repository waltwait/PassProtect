package com.passprotect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 別的 app 複製來、沒有純文字的剪貼內容要貼得進來。最常見的是「複製連結」只放一個 URI
 * （描述是 text/uri-list，Compose 會顯示「貼上」，但 `Item.getText()` 是 null）。
 */
@RunWith(AndroidJUnit4::class)
class PasteableClipboardTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeClipboard(var entry: ClipEntry?) : Clipboard {
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
        override val nativeClipboard: ClipboardManager get() = error("not used")
    }

    @Test
    fun linkCopiedAsUriPastesAsText() = runBlocking {
        val link = ClipData.newRawUri("連結", Uri.parse("https://example.com/login"))
        val pasted = PasteableClipboard(FakeClipboard(ClipEntry(link)), context).getClipEntry()

        assertEquals("https://example.com/login", pasted?.clipData?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun plainTextIsHandedOverUnchanged() = runBlocking {
        val original = ClipEntry(ClipData.newPlainText("文字", "貓的名字"))
        val pasted = PasteableClipboard(FakeClipboard(original), context).getClipEntry()

        assertSame(original, pasted)
    }
}
