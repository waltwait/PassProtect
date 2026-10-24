package com.passprotect.ui

import android.content.Context
import android.net.Uri

/** 記住本地備份資料夾的 tree URI（同 [VaultLocationPrefs] 的理由：重開 app 不必再選一次）。 */
class BackupLocationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("backup_location", Context.MODE_PRIVATE)

    fun get(): Uri? = prefs.getString(KEY_URI, null)?.let(Uri::parse)

    fun put(treeUri: Uri) {
        prefs.edit().putString(KEY_URI, treeUri.toString()).apply()
    }

    private companion object {
        const val KEY_URI = "tree_uri"
    }
}
