package com.passprotect.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.passprotect.ui.components.IconTopBar
import java.net.URLEncoder

/**
 * `EditScreen` 把撿到的網址回傳到這個 key（經由 Navigation Compose 的
 * `SavedStateHandle`「上一畫面等結果」標準做法）。放在檔案層級（不是類別
 * 常數），因為 [WebSearchScreen] 是一個 `@Composable fun`，不能有伴生物件。
 */
internal const val WEB_SEARCH_RESULT_KEY = "picked_url"

/**
 * 內嵌瀏覽器，不是真的在 app 裡解析/爬搜尋結果——密碼提示保險庫沒有理由
 * 自己養一套搜尋引擎串接（要嘛付費 API key，要嘛爬蟲不穩定又可能違反
 * 條款）。單純載入一個搜尋頁讓使用者像平常滑手機一樣自己找、自己點進真正
 * 的網站，找到了按右下角的勾勾，把當下網址帶回 [EditScreen]。
 *
 * 這是本 app 唯一會連網的地方——密碼/提示內容完全不經過這裡，`AndroidManifest.xml`
 * 的 `INTERNET` 權限只為了這個功能存在。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSearchScreen(query: String, navController: NavController) {
    var currentUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = { IconTopBar(onBack = { navController.popBackStack() }, centerTitle = "搜尋網址") },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(WEB_SEARCH_RESULT_KEY, currentUrl)
                    navController.popBackStack()
                },
                // 跟主畫面的「＋」同一套：primary 實心。不設的話 FAB 預設吃 primaryContainer。
                shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Check, contentDescription = "使用目前網址")
            }
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 頁面裡的搜尋框是網頁本身（Google）畫的，不是 Compose 元件，但這層
                // WebView 一樣要讓鍵盤把可用高度收小，網頁才會照著縮小後的視窗重排，
                // 不會被鍵盤蓋住。
                .imePadding(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                        }
                    }
                    // query 是 Navigation 已經解碼過的原標題（見 webSearchRoute），不要再解一次。
                    loadUrl("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
                }
            }
        )
    }
}
