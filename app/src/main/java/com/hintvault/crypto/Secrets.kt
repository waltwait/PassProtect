package com.hintvault.crypto

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/** 以 0 覆寫。用於金鑰與其他機密位元組。 */
fun ByteArray.wipe() = fill(0)

/** 以 NUL 覆寫。用於主密碼。 */
fun CharArray.wipe() = fill(' ')

/** 執行 [block] 後必定歸零，即使 [block] 擲出例外。 */
fun <R> ByteArray.useThenWipe(block: (ByteArray) -> R): R =
    try { block(this) } finally { wipe() }

/** 執行 [block] 後必定歸零，即使 [block] 擲出例外。 */
fun <R> CharArray.useThenWipe(block: (CharArray) -> R): R =
    try { block(this) } finally { wipe() }

/**
 * UTF-8 編碼且不經過 String。String 不可變且無法歸零，
 * 機密資料一旦進入 String 就會留在堆積上直到 GC。
 */
fun CharArray.toUtf8Bytes(): ByteArray {
    val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(this))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    // encode() 的內部緩衝區也可能持有明文
    if (buffer.hasArray()) buffer.array().fill(0)
    return bytes
}
