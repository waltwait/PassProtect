# 密碼提示保險庫 — UI 設計規格

日期：2026-08-15
狀態：待實作
前置條件：核心引擎（`docs/superpowers/specs/2026-08-13-password-hint-vault-design.md`）已完成、已合併至 `main`

## 1. 這是什麼

在已完成的核心引擎（`crypto` / `vault` / `storage` / `session` 四層）之上，建立規格 §7 列出的六個 Compose 畫面，讓這個 app 第一次變成真的能裝到手機上用的東西。核心引擎完成後沒有任何 UI，連解鎖畫面都不存在。

本輪同時解決核心引擎最終審查明確標記、故意留給這一輪處理的三個缺口：

1. 生體辨識快速解鎖沒有串起來（`VaultSession` 持有的抽象「vault key」不對應任何實際存在的東西）
2. 沒有辦法建立新的資料庫檔案（`SafVaultFile` 只能找既有檔案）
3. 備份寫了但沒有還原路徑

## 2. 安全政策變更（重要）

這輪 brainstorming 對核心引擎既有的安全規則做了一次**明確、使用者知情同意**的放寬，記錄於此並已同步修正 `docs/superpowers/specs/2026-08-13-password-hint-vault-design.md` §4.1、§4.2。

### 背景

原規則：「主密碼本身永不儲存，任何形式皆然」。核心引擎完成後發現：KDBX 函式庫的 `Credentials` 介面只有 `getKey()` 一個方法，`KdbxHeader.getTransformedKeyDigest()` 不論輸入是什麼都無條件重新跑一次 Argon2d，沒有「已經算過了、跳過」的介面存在。

這代表生體辨識若要真的跳過 Argon2（達成當初設想的「秒開」），需要繞過函式庫內建的 `load()` 流程，自己接一段用已算好的 transformed digest 直接解密的路徑——這是全新的工程，不是單純的介面串接。

### 決定

在兩個選項之間，使用者選擇後者：

- **A. 真的秒開**：快取 Argon2 算完的 transformed digest（32 bytes），繞過函式庫內建流程直接解密。不違反「主密碼永不儲存」，但需要額外一輪設計與實作。
- **B. 只求免打字**（已選）：接受主密碼以 Keystore 包裝、需生體驗證才能解開的形式存在裝置上。每次生體辨識解鎖仍完整跑一次 Argon2d（約 2.2 秒），換來的只是不用手動輸入密碼。

### 新規則

主密碼可以透過 Android Keystore 包裝後存於 app 私有目錄，前提是：

- 包裝金鑰為生體辨識綁定，沿用核心引擎 §4.4 既有規格：`setInvalidatedByBiometricEnrollment(true)`、StrongBox 優先、`AUTH_BIOMETRIC_STRONG`
- 生體辨識解鎖時，主密碼仍完整跑一次 Argon2d——這條規則放寬換來的是「不用打字」，不是「解鎖變快」
- 使用者必須先以主密碼手動解鎖一次，才能啟用生體辨識
- 不得將主密碼以任何形式寫入 log

**殘餘風險（使用者已知情並接受）**：任何能通過生體驗證的人（包含被脅迫的本人，或生體辨識層本身被攻破的情況）就能拿到主密碼明文，不只是「用一次」。裝置遺失或指紋被冒用的防護仍在（新增指紋會讓 Keystore 金鑰失效），但不再有「就算整支手機被拆開分析，主密碼在任何地方都找不到」這條保證。

### 對核心引擎既有型別的影響

`crypto.BiometricUnlock` 的 `enroll(vaultKey: ByteArray)` / `unlock(): ByteArray?` 簽章不變，但語意從「包裝一把抽象的 vault key」變成具體的「包裝主密碼的 UTF-8 位元組」。呼叫端（本輪新建的 ViewModel）負責把 `CharArray` 主密碼轉成 `ByteArray` 交給 `enroll()`，以及把 `unlock()` 拿回的 `ByteArray` 轉回 `CharArray` 餵給 `Vault.open()`，兩個方向都要走 `crypto.Secrets` 既有的 `toUtf8Bytes()` / `wipe()` 工具，不得經過 `String`。

## 3. 架構

單一 Activity + Navigation Compose + 一個 ViewModel 持有 `VaultSession`。

理由：`FLAG_SECURE` 要在 Activity 層設、`BiometricPrompt` 需要 `FragmentActivity`、`AutoLock` 要接生命週期事件（切到背景、螢幕關閉）——這些是 Android 平台本身要求的，「一個長期存活、跨畫面共用」的狀態需要某個東西撐過螢幕旋轉，ViewModel 是這個需求的最低限度解法，不是額外疊上去的框架。

### 對 `session.VaultSession` 的調整

核心引擎的 `VaultSession` 目前持有一把抽象的 `ByteArray`（"vault key"）。這個抽象在主密碼快取方式定案後不再對應任何實際存在的東西——真正敏感、真正有用的是**已經解密好的 `Vault` 物件**（裡面就是所有解密後的提示）。

調整：

```kotlin
class VaultSession {
    val state: StateFlow<LockState>
    fun unlock(vault: Vault)          // 原本是 unlock(vaultKey: ByteArray)
    fun <R> withVault(block: (Vault) -> R): R   // 原本是 withKey
    fun lock()                        // 語意不變：清掉持有的 Vault 參照
}
```

`Vault` 本身不是 `ByteArray`，沒有 `wipe()` 可呼叫；上鎖時的處理方式是**捨棄參照**讓 GC 回收，而非位元覆寫——這是本次調整必須明確記錄的行為差異，之前 `VaultSession` 的「上鎖時歸零陣列」邏輯不再適用。

`VaultSession`/`AutoLock` 目前都還沒有正式呼叫端（核心引擎最終審查已確認），此調整不影響任何已驗證過的核心引擎邏輯。

### 資料流

```
UI 層（Compose 畫面）
    ↕ 只透過 StateFlow 觀察狀態、呼叫 ViewModel 的函式
VaultViewModel
    ├─ VaultSession（持有 Vault、解鎖狀態）
    ├─ AutoLock（決定何時上鎖，接 Activity 生命週期）
    ├─ VaultRepository（讀寫 SAF 檔案、衝突偵測）
    └─ BiometricUnlock / KeystoreKeys（生體辨識，依 §2 決定包裝主密碼）
```

## 4. 畫面與流程

```
啟動
  │
  ├─ 尚未選過同步資料夾 → 【首次設定】選資料夾 → 自動偵測資料夾內的 .kdbx
  │                                                  ├─ 找到一個 → 進【解鎖】
  │                                                  ├─ 沒找到 → 建立新庫（設主密碼）→ 直接進【清單】
  │                                                  └─ 找到多個 → 列出讓使用者選一個 → 進【解鎖】
  │
  └─ 已選過資料夾 → 【解鎖】
```

自動建庫時的檔名固定為 `vault.kdbx`；使用者事後可透過系統檔案管理員自行更名（app 內不提供更名功能，避免和「資料庫位置」設定產生第二個真相來源）。

### 4.1 解鎖

- 已啟用生體辨識 → 一進來就跳 `BiometricPrompt`，通過後解出主密碼、呼叫 `Vault.open()`（仍跑 Argon2，約 2.2 秒，畫面需顯示進度指示，不可看起來像卡住）
- 未啟用，或使用者按了「改用主密碼」→ 密碼輸入框
- 開檔失敗分兩種訊息，不可混為一談：
  - `WrongPasswordException` → 密碼錯誤，可重試
  - `UnsupportedVaultFormatException` → 格式不支援（例如 KeePassXC 用 Argon2id 建的檔），**不可重試**，需明講原因並提示使用者到 KeePassXC 把 KDF 改回 Argon2d

### 4.2 清單／搜尋

- 依標題排序，上方搜尋框做即時過濾
- 每列顯示標題 + 最後修改時間；不顯示提示內容本身，不提供複製功能（沿用核心規格：這是提示保險庫，不是密碼管理器）
- 點一筆 → 詳情；右上角「新增」→ 編輯（新建模式）；提供手動「鎖定」按鈕

### 4.3 詳情

- 顯示標題、提示全文、最後修改時間
- 「編輯」「刪除」「查看歷史」三個動作；刪除需二次確認

### 4.4 編輯

- 標題、提示兩個欄位；存檔呼叫 `Vault.upsert()` → `serialize()` → `VaultRepository.save()`
- **必須處理 `SaveResult.Conflict`**：`VaultRepository` 偵測到外部改動時會另存 `.conflict-*.kdbx`、不覆寫本尊。UI 必須明確告知使用者「修改已存到另一個檔案，同步資料夾裡的本尊沒有被動到，請到 KeePassXC 手動合併」，不可靜默吞掉這個結果或讓使用者誤以為存檔成功覆蓋了本尊

### 4.5 歷史

- 列出 `Entry.history`，每筆顯示時間 + 當時的提示內容，唯讀

### 4.6 設定

- 資料庫位置（可重選資料夾，走 4.7 的建立/尋找邏輯）
- 生體辨識開關（開啟時觸發 `BiometricUnlock.enroll()`，前提是已用主密碼解鎖過本次 session）
- 自動上鎖逾時（對應既有 `AutoLockPolicy`）
- 變更主密碼：重新 `serialize()` 存檔；若已啟用生體辨識，Keystore 包裝的主密碼副本要跟著更新為新密碼，舊副本必須清掉——這裡是本規格中「忘了同步更新會導致生體辨識解鎖用舊密碼失敗」風險最高的一步，實作與測試需特別注意

### 4.7 新增功能：建立新資料庫檔案

核心引擎最終審查標記的缺口之一。`storage.VaultFile` / `storage.SafVaultFile` 加一個方法：

```kotlin
interface VaultFile {
    // ...既有方法不變
    fun create(bytes: ByteArray)   // 在目前的目錄與檔名建立新檔案；若已存在則拋例外
}
```

`SafVaultFile` 的實作可直接重用 Task 8 已經寫好、已驗證過「絕不覆蓋既有檔案」的 `writeSibling()` 邏輯改寫而成，不是從零設計新的檔案建立機制。

### 4.8 新增功能：備份還原

核心引擎最終審查標記的缺口之二。`storage.BackupStore.get()` 目前有寫入但零呼叫端。

觸發方式：**開 app 時自動偵測**。解鎖流程之前先檢查目標 KDBX 檔案是否存在、是否能被基本解析（不需要密碼，KDBX 檔頭本身未加密）；若檔案缺失或檔頭無法解析，且備份存在，跳出提示「偵測到資料庫檔案異常，是否從最近一次的備份還原？」，使用者確認後才寫回。使用者拒絕或沒有可用備份時，維持原本的錯誤處理流程（走 4.1 的失敗分支邏輯，但訊息需區分「密碼錯」「格式不支援」「檔案本身找不到/壞掉」三種）。

## 5. 測試策略

- **ViewModel 邏輯**：JVM 單元測試，以假的 `VaultRepository` / `BiometricUnlock` 介面窮舉狀態轉換（鎖定→解鎖→上鎖、`SaveResult.Conflict` 分支、備份還原提示的觸發條件），沿用核心引擎「假實作窮舉測試」的既有模式，不需碰 Compose。
- **畫面**：Compose UI 測試（`ComposeTestRule`）涵蓋核心路徑——輸入密碼解鎖、新增/編輯一筆提示、搜尋過濾。
- **無法自動化的部分**：生體辨識實際互動、`FLAG_SECURE` 的螢幕截圖防護、真實 SAF provider 行為，沿用並擴充既有 `docs/manual-verification.md`，不強行寫自動測試。
- **端到端**：比照核心引擎 Task 12 的做法，在真機上跑一次「首次設定 → 建立資料庫 → 新增一筆 → 手動上鎖 → 生體辨識解鎖 → 確認看得到那筆」的完整流程。

## 6. 明確不做（YAGNI，沿用核心規格既有原則）

沿用核心引擎規格 §1 已排除的項目（自動填入、複製到剪貼簿、密碼產生器等），本輪額外排除：

| 功能 | 原因 |
|---|---|
| app 內建更名資料庫檔案 | 避免與「資料庫位置」設定產生第二個真相來源，交給系統檔案管理員 |
| 自動合併衝突檔案 | 核心引擎既有決定：合併錯誤是最糟的失敗模式，交給桌面版 KeePassXC 處理 |
| 多資料庫／多保險庫切換 | 超出範圍，一個 app 對應一個資料庫檔案 |
| 真的秒開的生體辨識（§2 選項 A） | 使用者已選擇選項 B；選項 A 留待未來獨立的一輪設計 |
