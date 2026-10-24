package com.passprotect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 讓從別的 app 複製來的東西貼得進來。
 *
 * 剪貼簿裡的一筆不一定有純文字：有些 app（三星手機上很常見）只放 HTML，或只放一個指向
 * 內容的 URI。系統原生的輸入框貼上時會自己轉成文字（`ClipData.Item.coerceToText`）；
 * Compose 的輸入框只讀 `Item.getText()`，那是 null 就什麼都不做——選單上有「貼上」
 * （它只檢查剪貼簿的類型是不是文字類），按下去卻沒反應。
 *
 * 這裡在 Compose 讀剪貼簿時，第一筆沒有純文字就先替它轉好，換成一筆純文字交給輸入框。
 * 寫入剪貼簿原樣轉交。
 */
internal class PasteableClipboard(
    private val platform: Clipboard,
    private val context: Context
) : Clipboard {

    override suspend fun getClipEntry(): ClipEntry? {
        val entry = platform.getClipEntry() ?: return null
        val clip = entry.clipData
        if (clip.itemCount == 0 || clip.getItemAt(0).text != null) return entry
        // 指向內容的 URI 要透過 ContentResolver 讀出來，是會卡住的 I/O，不在主執行緒做。
        val text = withContext(Dispatchers.IO) { clip.getItemAt(0).coerceToText(context) }
        return ClipEntry(ClipData.newPlainText(clip.description.label, text))
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = platform.setClipEntry(clipEntry)

    override val nativeClipboard: ClipboardManager
        get() = platform.nativeClipboard
}
