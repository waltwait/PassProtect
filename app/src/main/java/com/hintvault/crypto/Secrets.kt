package com.hintvault.crypto

import java.nio.ByteBuffer
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
 *
 * **不可改用 `StandardCharsets.UTF_8.encode(CharBuffer.wrap(this))`。**
 * 那條路徑以 `averageBytesPerChar`（UTF-8 為 1.1）預估輸出大小，遇到中文
 * （每字 3 bytes）會不足而反覆擴充緩衝區，過程中**丟棄的中間緩衝區裝著明文**，
 * 而我們拿不到那些參照，也就清不掉。實測 "提示" 會丟棄一個含「提」完整
 * UTF-8 位元組的 5-byte 緩衝區。
 *
 * 因此改為預先配置足夠大的緩衝區單次編碼：UTF-8 每個 BMP 字元最多 3 bytes，
 * 代理對為 2 chars → 4 bytes，故 `size * 3` 永遠足夠，不會觸發擴充。
 * 緩衝區由我們自己配置，因此清得掉。
 */
fun CharArray.toUtf8Bytes(): ByteArray {
    val out = ByteBuffer.allocate(size * 3)
    val encoder = StandardCharsets.UTF_8.newEncoder()
    encoder.encode(CharBuffer.wrap(this), out, true)
    encoder.flush(out)

    val bytes = ByteArray(out.position())
    out.flip()
    out.get(bytes)
    out.array().fill(0)
    return bytes
}
