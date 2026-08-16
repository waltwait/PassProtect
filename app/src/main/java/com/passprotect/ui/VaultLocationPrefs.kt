package com.passprotect.ui

import android.content.Context
import android.net.Uri

/**
 * 記住使用者選過的資料夾與檔名，讓下次啟動不必重新選一次。
 *
 * 規格 §4 的流程圖預設「已選過資料夾 → 直接進【解鎖】」，代表選擇結果必須
 * 跨 app 重啟持久化——`VaultUriPermissions.isStillGranted()` 只驗證 SAF
 * 授權本身是否還有效，不記得「是哪一個 URI」，因此另外需要這個小類別。
 */
class VaultLocationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("vault_location", Context.MODE_PRIVATE)

    data class Location(val treeUri: Uri, val fileName: String)

    fun get(): Location? {
        val uriString = prefs.getString(KEY_URI, null) ?: return null
        val fileName = prefs.getString(KEY_FILE, null) ?: return null
        return Location(Uri.parse(uriString), fileName)
    }

    fun put(treeUri: Uri, fileName: String) {
        prefs.edit().putString(KEY_URI, treeUri.toString()).putString(KEY_FILE, fileName).apply()
    }

    private companion object {
        const val KEY_URI = "tree_uri"
        const val KEY_FILE = "file_name"
    }
}
