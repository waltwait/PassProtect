package com.passprotect.ui

import java.net.URLEncoder

/**
 * 使用者自己打的字（分類名稱、拿去搜尋的標題）可能含 `/`、`?`、`#`、`%`。原樣塞進路徑，
 * Navigation 會把它們當成多一段路徑、查詢字串、片段或跳脫序列：不是找不到目的地而閃退，
 * 就是開到別的分類。所以放進路徑前先整段百分比編碼（空白編成 `%20` 而不是 `+`，
 * Navigation 解碼時不會把 `+` 當空白）。
 *
 * Navigation 比對路徑參數時已經 `Uri.decode` 過一次，目的地拿到的就是原本的字，
 * 不能再解碼——否則名為 `50%`、`a%2Fb` 的分類會被解壞。
 */
private fun routeSegment(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

fun categoryRoute(name: String) = "category/" + routeSegment(name)

/** 路徑參數已經解碼過，原樣拿來用；見 [routeSegment]。 */
fun categoryNameFromArg(arg: String?) = arg.orEmpty()

fun webSearchRoute(query: String) = "websearch/" + routeSegment(query)
