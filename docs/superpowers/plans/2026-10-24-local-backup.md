# 本地備份 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把加密的資料庫檔每天自動備份到使用者選的資料夾，保留最新 30 份，並能在設定頁挑一份還原。

**Architecture:** 儲存層新增 `BackupFolder`（SAF 資料夾的檔案進出）與 `LocalBackups`（檔名、每日備份、清理，純邏輯）。`VaultViewModel` 在解鎖、存檔成功、按「立即備份」時呼叫它，失敗只記狀態；還原走既有的 `VaultRepository.save()` 與 `persistMutex`。設定頁加「備份」區段與還原面板。

**Tech Stack:** Kotlin、Jetpack Compose（Material3）、SAF（`androidx.documentfile`）、JUnit4 + kotlinx-coroutines-test（JVM，無 Robolectric）。

**Spec:** `docs/superpowers/specs/2026-10-24-local-backup-design.md`

## Global Constraints

- 每日備份檔名 `PassProtect-yyyy-MM-dd.kdbx`（手機當地日期）；還原前 `PassProtect-yyyy-MM-dd-HHmmss-before-restore.kdbx`。
- 保留最新 30 份；只動符合上面兩種檔名的檔案，資料夾裡其他檔案一律不碰。
- 備份內容是原封不動的 KDBX 位元組；寫回主檔時也用原始位元組，不重新序列化。
- 備份的任何失敗都不改變解鎖、存檔的結果，只設 `backupFailed = true`；下一次成功就清掉。
- 檔案與 Argon2 運算一律在 `unlockDispatcher` 上跑（測試換成 `Dispatchers.Unconfined`）。
- 不加新的相依套件。SAF 建檔的 MIME 沿用 `"application/vnd.passprotect.kdbx"`（`SafVaultFile` 註解：`application/octet-stream` 在部分裝置會被加上 `.bin`）。
- 文字照規格，盡量少：區段「備份」；列「備份資料夾」（右側資料夾名稱或「未設定」）、「立即備份」（右側「今天 14:32」／「10 月 22 日」／「備份失敗」）、「從備份還原」；面板標籤「還原前」；對話框欄位「主密碼」、按鈕「還原」、錯誤「主密碼不對」「還原失敗」。
- 驗證指令（每個 task 結尾）：`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --console=plain`；Task 4 另加 `:app:assembleRelease :app:compileDebugAndroidTestKotlin`。

## Review Focus

1. 備份資料夾的授權被撤銷或資料夾被刪：解鎖、存檔照常成功，設定頁顯示「備份失敗」，不閃退 —— Task 2 `folderErrorsNeverFailUnlockOrSave`。
2. 跨過午夜：午夜前解鎖、午夜後存檔，要多出新一天的檔案，舊的那天不被改 —— Task 2 `saveAfterMidnightStartsANewDay`。
3. 還原途中被上鎖（關螢幕、自動上鎖）：不能因為還原把 session 又解開；主檔維持原樣 —— Task 3 `lockDuringRestoreLeavesEverythingAsIs`。
4. 資料夾就是同步資料夾、主檔剛好叫 `PassProtect.kdbx`：清理不可以刪到它 —— Task 1 `foreignFilesAreNeverTouched` 涵蓋（主檔名不含日期，解析回 null）。
5. SAF provider 撞名時自動改名（`… (1).kdbx`）：`SafBackupFolder.write` 必須先找既有檔覆蓋，不能每次都建新檔 —— 只能手機驗證，列入 Task 4 的手動清單。

---

### Task 1: 備份資料夾與每日備份規則（儲存層）

**Files:**
- Create: `app/src/main/java/com/passprotect/storage/BackupFolder.kt`
- Create: `app/src/main/java/com/passprotect/storage/LocalBackups.kt`
- Create: `app/src/test/java/com/passprotect/storage/FakeBackupFolder.kt`
- Test: `app/src/test/java/com/passprotect/storage/LocalBackupsTest.kt`

**Interfaces:**
- Produces:
  - `data class StoredFile(val name: String, val lastModified: Long)`
  - `interface BackupFolder { val displayName: String; fun list(): List<StoredFile>; fun read(name: String): ByteArray; fun write(name: String, bytes: ByteArray); fun delete(name: String) }` —— `write` 已存在就覆蓋（`"wt"`），不存在才建立。
  - `class SafBackupFolder(context: Context, directory: DocumentFile) : BackupFolder`（`displayName = directory.name ?: ""`）
  - `data class BackupEntry(val name: String, val date: LocalDate, val time: LocalTime?, val beforeRestore: Boolean, val lastModified: Long)` —— 每日備份 `time = null`。
  - `object LocalBackups { const val KEEP = 30; fun dailyName(date: LocalDate): String; fun beforeRestoreName(at: LocalDateTime): String; fun parse(file: StoredFile): BackupEntry?; fun list(folder: BackupFolder): List<BackupEntry>; fun backupDaily(folder: BackupFolder, bytes: ByteArray, today: LocalDate, overwrite: Boolean); fun backupBeforeRestore(folder: BackupFolder, bytes: ByteArray, at: LocalDateTime); fun prune(folder: BackupFolder, keep: Int = KEEP) }`
  - 測試用 `class FakeBackupFolder : BackupFolder`（`val files: MutableMap<String, ByteArray>`、`var failWrites`、`var failList`、`var onRead: () -> Unit = {}`；`lastModified` 用寫入順序遞增的計數）。

- [ ] **Step 1: Write the failing tests** —— `LocalBackupsTest`：

```kotlin
@Test fun dailyNameUsesTheLocalDate() =
    assertEquals("PassProtect-2026-10-24.kdbx", LocalBackups.dailyName(LocalDate.of(2026, 10, 24)))

@Test fun beforeRestoreNameCarriesTheTime() =
    assertEquals("PassProtect-2026-10-24-153012-before-restore.kdbx",
        LocalBackups.beforeRestoreName(LocalDateTime.of(2026, 10, 24, 15, 30, 12)))

@Test fun parsesOnlyItsOwnNames() {
    assertNotNull(LocalBackups.parse(StoredFile("PassProtect-2026-10-24.kdbx", 0)))
    assertEquals(true, LocalBackups.parse(StoredFile("PassProtect-2026-10-24-153012-before-restore.kdbx", 0))?.beforeRestore)
    listOf("PassProtect.kdbx", "PassProtect-2026-13-40.kdbx", "PassProtect-2026-10-24 (1).kdbx", "notes.txt")
        .forEach { assertNull(it, LocalBackups.parse(StoredFile(it, 0))) }
}

@Test fun dailyBackupIsWrittenOncePerDayUnlessOverwriting() {
    val folder = FakeBackupFolder(); val day = LocalDate.of(2026, 10, 24)
    LocalBackups.backupDaily(folder, byteArrayOf(1), day, overwrite = false)
    LocalBackups.backupDaily(folder, byteArrayOf(2), day, overwrite = false)
    assertArrayEquals(byteArrayOf(1), folder.files["PassProtect-2026-10-24.kdbx"])
    LocalBackups.backupDaily(folder, byteArrayOf(3), day, overwrite = true)
    assertArrayEquals(byteArrayOf(3), folder.files["PassProtect-2026-10-24.kdbx"])
}

@Test fun keepsTheNewestThirty() {
    val folder = FakeBackupFolder(); val start = LocalDate.of(2026, 9, 1)
    repeat(32) { LocalBackups.backupDaily(folder, byteArrayOf(it.toByte()), start.plusDays(it.toLong()), overwrite = true) }
    val names = LocalBackups.list(folder).map { it.name }
    assertEquals(30, names.size)
    assertFalse("PassProtect-2026-09-01.kdbx" in names); assertFalse("PassProtect-2026-09-02.kdbx" in names)
}

@Test fun foreignFilesAreNeverTouched() {
    val folder = FakeBackupFolder().apply { files["PassProtect.kdbx"] = byteArrayOf(9); files["keep.txt"] = byteArrayOf(8) }
    repeat(31) { LocalBackups.backupDaily(folder, byteArrayOf(1), LocalDate.of(2026, 9, 1).plusDays(it.toLong()), overwrite = true) }
    assertTrue("PassProtect.kdbx" in folder.files && "keep.txt" in folder.files)
}

@Test fun listsNewestFirstWithBeforeRestoreAfterThatDaysTime() {
    // 同一天：每日備份（time = null，視為當天最後）排在還原前備份之前
    val folder = FakeBackupFolder()
    LocalBackups.backupDaily(folder, byteArrayOf(1), LocalDate.of(2026, 10, 23), overwrite = true)
    LocalBackups.backupBeforeRestore(folder, byteArrayOf(2), LocalDateTime.of(2026, 10, 24, 9, 0, 0))
    LocalBackups.backupDaily(folder, byteArrayOf(3), LocalDate.of(2026, 10, 24), overwrite = true)
    assertEquals(
        listOf("PassProtect-2026-10-24.kdbx", "PassProtect-2026-10-24-090000-before-restore.kdbx", "PassProtect-2026-10-23.kdbx"),
        LocalBackups.list(folder).map { it.name })
}
```

- [ ] **Step 2: Run to verify they fail** —— Expected: compile errors, unresolved `LocalBackups` / `StoredFile` / `FakeBackupFolder`.
- [ ] **Step 3: Implement** `BackupFolder.kt`、`LocalBackups.kt`、`FakeBackupFolder.kt`。`parse` 用 `Regex("""PassProtect-(\d{4}-\d{2}-\d{2})(?:-(\d{6})-before-restore)?\.kdbx""")` 整串比對，日期／時間解析失敗回 null。排序鍵：`(date, time ?: LocalTime.MAX)` 由新到舊。`backupDaily`、`backupBeforeRestore` 寫完都呼叫 `prune`。`SafBackupFolder.write`：`directory.findFile(name)` 有就 `openOutputStream(uri, "wt")`，沒有才 `createFile(MIME, name)`；`list` 用 `directory.listFiles()` 的 `name`／`lastModified()`。
- [ ] **Step 4: Run to verify they pass** —— Expected: `LocalBackupsTest` 7 passed，全套無失敗。
- [ ] **Step 5: Commit** —— `feat(backup): backup folder and daily backup rules`

---

### Task 2: 解鎖／存檔時自動備份、立即備份、記住資料夾

**Files:**
- Create: `app/src/main/java/com/passprotect/ui/BackupLocationPrefs.kt`
- Modify: `app/src/main/java/com/passprotect/ui/VaultUiState.kt`、`app/src/main/java/com/passprotect/ui/VaultViewModel.kt`
- Test: `app/src/test/java/com/passprotect/ui/VaultViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `BackupFolder`、`SafBackupFolder`、`LocalBackups`、`BackupEntry`、`FakeBackupFolder`。
- Produces:
  - `VaultUiState` 新欄位：`backupFolderName: String? = null`、`backups: List<BackupEntry> = emptyList()`、`backupFailed: Boolean = false`。（「最後一次備份時間」由畫面取 `backups.maxOfOrNull { it.lastModified }`。）
  - `VaultViewModel`：`fun chooseBackupFolder(context: Context, treeUri: Uri)`、`fun backupNow()`、`fun refreshBackups()`；測試入口 `internal fun setBackupFolderForTest(folder: BackupFolder?)`、`internal fun setClockForTest(now: () -> LocalDateTime)`。
  - `class BackupLocationPrefs(context: Context) { fun get(): Uri?; fun put(treeUri: Uri) }`（SharedPreferences `"backup_location"`、鍵 `"tree_uri"`）。

- [ ] **Step 1: Write the failing tests**（沿用 `readyViewModel(bytes)`，另外 `vm.setBackupFolderForTest(folder)`、`vm.setClockForTest { LocalDateTime.of(2026, 10, 24, 10, 0) }`）：
  - `unlockBacksUpTodayOnce`：解鎖後 `folder.files["PassProtect-2026-10-24.kdbx"]` 等於主檔位元組；把那份改成 `byteArrayOf(7)`，`lock()` 再解鎖，仍是 `byteArrayOf(7)`。
  - `saveRefreshesTodaysBackup`：解鎖、`saveEntry(...)` 後，今天那份等於存檔後主檔的位元組。
  - `conflictedSaveDoesNotBackUp`：解鎖後把主檔內容換掉（外部改動），`saveEntry` 得到 `SaveMessage.Conflict`，今天那份仍是解鎖時的位元組。
  - `folderErrorsNeverFailUnlockOrSave`：`folder.failWrites = true`；解鎖成功、`saveEntry` 得到 `SaveMessage.Saved`，且 `uiState.backupFailed == true`；之後 `failWrites = false`、`backupNow()` → `backupFailed == false`。
  - `saveAfterMidnightStartsANewDay`：10/24 23:59 解鎖，時鐘改成 10/25 00:01 再存檔 → 兩個檔都在，10/24 那份仍是解鎖時的位元組。
  - `backupNowWritesTheCurrentFile`：解鎖、把今天那份改成 `byteArrayOf(7)`、`backupNow()` → 等於主檔位元組，`uiState.backups` 有一筆。
  - `noFolderMeansNoBackupAndNoError`：不設資料夾，解鎖、存檔都成功，`backupFailed == false`。
- [ ] **Step 2: Run to verify they fail** —— Expected: unresolved `setBackupFolderForTest` / `setClockForTest` / `backupFailed`。
- [ ] **Step 3: Implement.**
  - `VaultViewModel` 新增 `private var backupFolder: BackupFolder? = null`、`private var now: () -> LocalDateTime = LocalDateTime::now`，以及 `private suspend fun runBackup(block: (BackupFolder) -> Unit)`：沒有資料夾直接 return；在 `unlockDispatcher` 上執行 `block` 再 `LocalBackups.list`，成功 → `backups`、`backupFailed = false`；任何非取消例外 → `backupFailed = true`（`CancellationException` 照既有慣例重丟）。
  - `attemptUnlock` 成功、`session.unlock` 之後：`runBackup { LocalBackups.backupDaily(it, loaded.bytes, now().toLocalDate(), overwrite = false) }`。
  - `persist` 的 `SaveResult.Saved` 分支：`runBackup { LocalBackups.backupDaily(it, bytes, now().toLocalDate(), overwrite = true) }`（`Conflict` 分支不呼叫）。
  - `backupNow()`：`viewModelScope.launch { runBackup { f -> LocalBackups.backupDaily(f, repository!!.load().bytes, now().toLocalDate(), overwrite = true) } }`；沒有 repository 就 return。
  - `refreshBackups()`：`runBackup {}`。
  - `chooseBackupFolder`：`VaultUriPermissions.persist(context, treeUri)`（既有的取得持久授權函式）、`BackupLocationPrefs.put`、建 `SafBackupFolder`、更新 `backupFolderName`，然後 `backupNow()`。
  - `checkExistingSetup` 開頭：`BackupLocationPrefs.get()` 有值且 `VaultUriPermissions.isStillGranted` 才建 `SafBackupFolder`、設 `backupFolderName`。
- [ ] **Step 4: Run to verify they pass** —— Expected: 新增 7 個測試通過，全套無失敗。
- [ ] **Step 5: Commit** —— `feat(backup): back up daily on unlock and save`

---

### Task 3: 還原

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/VaultUiState.kt`、`app/src/main/java/com/passprotect/ui/VaultViewModel.kt`
- Test: `app/src/test/java/com/passprotect/ui/VaultViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 `LocalBackups.backupBeforeRestore`、`backupDaily`；Task 2 的 `backupFolder`、`now`、`runBackup`。
- Produces:
  - `sealed interface RestoreState { data object Idle; data object Restoring; data object WrongPassword; data object Failed; data class Conflict(val conflictFileName: String); data object Done }`，`VaultUiState.restoreState: RestoreState = RestoreState.Idle`。
  - `VaultViewModel.restoreBackup(name: String, password: CharArray, biometric: BiometricUnlock?)`、`fun clearRestoreState()`。

- [ ] **Step 1: Write the failing tests**（主檔是有一筆「A」的保險庫；資料夾裡放一份有一筆「B」的備份 `PassProtect-2026-10-20.kdbx`）：
  - `restoreReplacesTheVaultAndKeepsABeforeRestoreCopy`：用正確密碼還原 → 主檔位元組等於備份位元組、資料夾多一份 `PassProtect-2026-10-24-100000-before-restore.kdbx` 等於還原前的主檔、`entries.map { it.title } == listOf("B")`、`restoreState == Done`、今天的每日備份等於備份位元組。
  - `wrongPasswordChangesNothing`：錯的密碼 → `restoreState == WrongPassword`，主檔不變、沒有 before-restore 檔、清單仍是「A」。
  - `restoreWithItsOwnPasswordKeepsUsingIt`：備份用 `"old horse"` 加密，用它還原後 `saveEntry` 一筆，主檔能用 `"old horse"` `Vault.open`。
  - `beforeRestoreCopyFailureAborts`：`folder.failWrites = true`（備份檔已事先放好）→ `restoreState == Failed`，主檔不變。
  - `conflictDuringRestoreLeavesTheSessionAlone`：解鎖後把主檔換成外部內容，再還原 → `restoreState is Conflict`，清單仍是「A」。
  - `lockDuringRestoreLeavesEverythingAsIs`：`folder.onRead = { vm.lock() }` → 還原後 `lockState == Locked`、`entries` 為空、主檔不變、`restoreState == Failed`。
- [ ] **Step 2: Run to verify they fail** —— Expected: unresolved `restoreBackup` / `RestoreState`。
- [ ] **Step 3: Implement `restoreBackup`**（`viewModelScope.launch`；`finally` 清掉 `password`）：
  1. `restoreState = Restoring`。在 `unlockDispatcher` 上 `folder.read(name)`、`Vault.open(bytes, password)`；`WrongPasswordException` → `WrongPassword`；其他例外 → `Failed`。
  2. 進 `persistMutex`。若 `lockState` 已是 `Locked` → `Failed` 並結束（不寫、不解開）。
  3. `LocalBackups.backupBeforeRestore(folder, repo.load().bytes, now())`，例外 → `Failed` 並結束。
  4. `repo.save(bytes, loadedStamp)`：
     - `Saved` → 再檢查一次 `lockState`（是 `Locked` 就只更新 `loadedStamp`、`Failed` 結束）；`val changed = !password.contentEquals(masterPasswordCache)`；`loadedStamp`、`session.unlock(vault)`、`cacheMasterPassword(password)`、`entries`／`hintSnippets`（背景解出）；`runBackup { backupDaily(it, bytes, today, overwrite = true) }`；`changed && biometricEnabled && biometric != null` → `enrollBiometric(biometric)`；`Done`。
     - `Conflict` → `RestoreState.Conflict(fileName)`。
  - `clearRestoreState()` 設回 `Idle`。
- [ ] **Step 4: Run to verify they pass** —— Expected: 新增 6 個測試通過，全套無失敗。
- [ ] **Step 5: Commit** —— `feat(backup): restore a backup from the backup folder`

---

### Task 4: 設定頁「備份」區段與還原面板

**Files:**
- Create: `app/src/main/java/com/passprotect/ui/screens/RestoreBackupSheet.kt`
- Modify: `app/src/main/java/com/passprotect/ui/screens/SettingsScreen.kt`、`app/src/main/java/com/passprotect/ui/format/EntryFormat.kt`
- Modify: `docs/manual-verification.md`、`docs/superpowers/specs/2026-10-24-local-backup-design.md`（狀態改「已實作」）
- Test: `app/src/test/java/com/passprotect/ui/format/EntryFormatTest.kt`

**Interfaces:**
- Consumes: Task 2 `backupFolderName`、`backups`、`backupFailed`、`chooseBackupFolder`、`backupNow`、`refreshBackups`；Task 3 `restoreState`、`restoreBackup`、`clearRestoreState`。
- Produces: `fun lastBackupLabel(at: Instant, now: Instant, zone: ZoneId): String`、`fun backupDateLabel(entry: BackupEntry): String`（`EntryFormat.kt`）。

- [ ] **Step 1: Write the failing tests**（`zone = ZoneId.of("Asia/Taipei")`，`now = 2026-10-24T06:00:00Z`）：

```kotlin
@Test fun lastBackupTodayShowsTheTime() =
    assertEquals("今天 14:32", lastBackupLabel(Instant.parse("2026-10-24T06:32:00Z"), now, zone))
@Test fun lastBackupEarlierShowsTheDate() =
    assertEquals("10 月 22 日", lastBackupLabel(Instant.parse("2026-10-22T01:00:00Z"), now, zone))
@Test fun backupDateLabelIncludesTheWeekday() =
    assertEquals("10 月 22 日 週四", backupDateLabel(BackupEntry("x", LocalDate.of(2026, 10, 22), null, false, 0)))
```

- [ ] **Step 2: Run to verify they fail** —— Expected: unresolved `lastBackupLabel` / `backupDateLabel`。
- [ ] **Step 3: Implement.**
  - 兩個格式函式。不是今年的日期帶年份，同 `modifiedLabel` 的規則。
  - `SettingsScreen`：在「資料庫」後面加 `SectionLabel("備份")` 與三列 `GroupedRow`（First／Middle／Last），文字照 Global Constraints。「備份資料夾」與「立即備份」（沒設資料夾時）都開 `ActivityResultContracts.OpenDocumentTree()` → `chooseBackupFolder`。畫面進來時 `LaunchedEffect(Unit) { viewModel.refreshBackups() }`。`backupFailed` 時右側「備份失敗」用 `MaterialTheme.colorScheme.error`。
  - `RestoreBackupSheet(backups, restoreState, onRestore: (name: String, password: CharArray) -> Unit, onDismiss)`：`ModalBottomSheet`，樣式比照 `CategoryPickerSheet`；每列 `backupDateLabel` 加上「還原前」標籤；點了開 `AlertDialog`：`PassProtectField("主密碼", PasswordVisualTransformation)` ＋「還原」；`Restoring` 時按鈕換成轉圈；`WrongPassword` →「主密碼不對」；`Failed` →「還原失敗」；`Conflict` → 沿用 `EditScreen` 的衝突句子；`Done` → 關掉面板並 `clearRestoreState()`。沒有備份時面板只顯示一行「沒有備份」。
  - `docs/manual-verification.md` 加「本地備份」一節：選資料夾、重開 app 還記得、每天第一次解鎖產生當天的檔、KeePassXC 打得開、還原後清單換掉、不同主密碼的備份還原後指紋可用、同一天多次存檔不會出現 `(1)` 檔名（Review Focus 5）。
- [ ] **Step 4: Run to verify** —— `./gradlew :app:testDebugUnitTest :app:assembleRelease :app:compileDebugAndroidTestKotlin --console=plain`；Expected: BUILD SUCCESSFUL，全套無失敗。
- [ ] **Step 5: Commit** —— `feat(backup): backup section and restore sheet in settings`
