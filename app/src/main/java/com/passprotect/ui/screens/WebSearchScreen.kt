package com.passprotect.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import java.net.URLDecoder
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
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSearchScreen(query: String, navController: NavController) {
    var currentUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜尋網址") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(WEB_SEARCH_RESULT_KEY, currentUrl)
                navController.popBackStack()
            }) {
                Icon(Icons.Filled.Check, contentDescription = "使用目前網址")
            }
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                        }
                    }
                    val decodedQuery = try {
                        URLDecoder.decode(query, "UTF-8")
                    } catch (_: Exception) {
                        query
                    }
                    loadUrl("https://www.google.com/search?q=" + URLEncoder.encode(decodedQuery, "UTF-8"))
                }
            }
        )
    }
}
