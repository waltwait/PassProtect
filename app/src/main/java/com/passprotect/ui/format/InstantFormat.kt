package com.passprotect.ui.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val displayFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

/** 清單/詳情畫面共用的顯示格式，取代原始 ISO-8601 字串（例如 `2026-08-16T05:13:45Z`）。 */
fun Instant.toDisplayString(): String = displayFormatter.format(this.atZone(ZoneId.systemDefault()))
