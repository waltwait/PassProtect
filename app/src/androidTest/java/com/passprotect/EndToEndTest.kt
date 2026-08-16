package com.passprotect

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.passprotect.crypto.KdfParams
import com.passprotect.session.LockState
import com.passprotect.session.VaultSession
import com.passprotect.storage.FileBackupStore
import com.passprotect.storage.SafVaultFile
import com.passprotect.storage.SaveResult
import com.passprotect.storage.VaultRepository
import com.passprotect.vault.Entry
import com.passprotect.vault.HistoricalHint
import com.passprotect.vault.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pw() = "correct horse battery staple".toCharArray()

    @Test
    fun fullLifecycleOnDevice() {
        val dbFile = File(context.cacheDir, "e2e.kdbx").apply { delete() }
        dbFile.writeBytes(Vault.create(KdfParams.DEFAULT).serialize(pw()))

        // SafVaultFile 需要「已授權的資料夾 + 檔名」，而非單一檔案的 Uri——見
        // SafVaultFile.kt 的說明：ACTION_OPEN_DOCUMENT 取得的單一檔案 DocumentFile
        // 其 parentFile 恆為 null，無法用來建立衝突用的兄弟檔案。測試用
        // DocumentFile.fromFile() 包一個普通目錄，走與正式 SAF 授權相同的程式碼路徑。
        val directory = DocumentFile.fromFile(dbFile.parentFile!!)
        val safFile = SafVaultFile(context, directory, dbFile.name)

        val repo = VaultRepository(
            safFile,
            FileBackupStore(File(context.filesDir, "e2e-backups"))
        )

        // 1. 載入
        var loaded = repo.load()
        val vault = Vault.open(loaded.bytes, pw())
        assertTrue(vault.entries().isEmpty())

        // 2. 新增一筆帶 history 的提示
        val entry = Entry(
            id = UUID.randomUUID(),
            title = "github.com",
            hint = "提示：貓的名字 + 生日",
            modifiedAt = Instant.now(),
            history = listOf(
                HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), "舊提示：國中同學的綽號")
            )
        )
        vault.upsert(entry)

        // 3. 存檔
        val saved = repo.save(vault.serialize(pw()), loaded.stamp)
        assertTrue(saved is SaveResult.Saved)

        // 4. 重新載入並確認內容完整
        loaded = repo.load()
        val reopened = Vault.open(loaded.bytes, pw())
        val got = reopened.entries().single()
        assertEquals("github.com", got.title)
        assertEquals("提示：貓的名字 + 生日", got.hint)
        assertEquals(1, got.history.size)
        assertEquals("舊提示：國中同學的綽號", got.history.single().hint)

        // 5. 模擬同步工具在背後改檔 → 必須拒絕覆蓋
        dbFile.writeBytes(dbFile.readBytes() + byteArrayOf(0))
        val conflict = repo.save(reopened.serialize(pw()), loaded.stamp)
        assertTrue("外部改動時必須產生衝突而非覆蓋", conflict is SaveResult.Conflict)

        // 6. session 生命週期
        val session = VaultSession()
        session.unlock(reopened)
        assertEquals(LockState.Unlocked, session.state.value)
        assertTrue(session.withVault { it.entries() }.isNotEmpty())
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }

    /** 把測試產生的資料庫拉出來，供桌面 KeePassXC 驗收。 */
    @Test
    fun exportsDatabaseForDesktopVerification() {
        val out = File(context.getExternalFilesDir(null), "for-keepassxc.kdbx")
        val vault = Vault.create(KdfParams.DEFAULT)
        vault.upsert(
            Entry(
                id = UUID.randomUUID(),
                title = "github.com",
                hint = "提示：貓的名字 + 生日",
                modifiedAt = Instant.now(),
                history = listOf(
                    HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), "舊提示：國中同學的綽號")
                )
            )
        )
        out.writeBytes(vault.serialize(pw()))
        assertTrue(out.length() > 0)
    }

    /**
     * 方向二：KeePassXC 建立的資料庫（KDBX 3.1 / AES-KDF）必須能被本 app 開啟。
     *
     * 執行前需先在電腦上跑這幾行，再用 adb push 送到裝置：
     *
     *   rm -f /tmp/from-keepassxc.kdbx
     *   printf 'desktop-pass\ndesktop-pass\n' | keepassxc-cli db-create --set-password /tmp/from-keepassxc.kdbx
     *   printf 'desktop-pass\nentry-pass\nentry-pass\n' | \
     *     keepassxc-cli add /tmp/from-keepassxc.kdbx -u someone --password-prompt "made-on-desktop"
     *   adb push /tmp/from-keepassxc.kdbx /sdcard/Android/data/com.passprotect/files/from-keepassxc.kdbx
     *
     * 目的地刻意不是原始構想的 `/sdcard/Download`：讀取該路徑需要
     * `MANAGE_EXTERNAL_STORAGE`，而這正是本 app 刻意不申請的權限（見
     * SafVaultFile.kt 的說明）。實測（Galaxy S25 / Android 16 / One UI，
     * targetSdk 36）：`adb shell appops set com.passprotect
     * MANAGE_EXTERNAL_STORAGE allow` 靜默無效（回讀仍是 `default`），
     * `legacy_storage` 對 targetSdk 29+ 的 app 無作用——即使真的授權成功，
     * 也會讓這個測試依賴一個 app 本身故意不用的權限，形同測了一條產品
     * 不會走的路徑。改用 app 私有的外部檔案目錄：一樣是真機上、由真正
     * keepassxc-cli 產生並以 adb push 送達的位元組，只是取得路徑改用
     * app 自己就能存取、不需額外權限的位置。推到 `/sdcard/Download` 不會
     * 讓這個測試失敗，只會讓它被下面的 Assume 靜默跳過——看起來像全部
     * 測試都通過了，實際上完全沒驗證到方向二的互通性。
     */
    @Test
    fun opensDatabaseCreatedByKeePassXc() {
        val pushed = File(context.getExternalFilesDir(null), "from-keepassxc.kdbx")
        org.junit.Assume.assumeTrue(
            "需先執行上方 KDoc 列出的 keepassxc-cli + adb push 指令", pushed.exists()
        )

        val vault = Vault.open(pushed.readBytes(), "desktop-pass".toCharArray())
        val titles = vault.entries().map { it.title }

        assertTrue("應讀到桌面端建立的 entry", titles.contains("made-on-desktop"))
    }
}
