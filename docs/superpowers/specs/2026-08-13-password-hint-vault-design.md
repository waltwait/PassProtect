# 密碼提示保險庫 — 設計規格

日期：2026-08-13
狀態：待實作

## 1. 這是什麼

一個 Android app，用來存放**密碼提示**（hint），不存放真正的密碼。使用者以自己的方式記憶密碼，本 app 只儲存能喚起記憶的線索。

雖然內容不是密碼本身，但提示會大幅縮小攻擊者的猜測範圍，因此加密強度不因此放寬。

### 使用情境

- 單一使用者（本人及家人朋友），sideload 安裝，不上架 Google Play
- 多裝置：資料庫是單一可攜加密檔，同步交由外部工具（Syncthing / Nextcloud / Drive）處理
- 桌面端可用 KeePassXC 開啟同一個檔案

### 明確不做（YAGNI）

以下功能經討論後確認**不在範圍內**，且多數是永久不做：

| 功能 | 原因 |
|---|---|
| 自動填入（Credential Manager / Autofill Framework） | 存的是提示不是密碼，無法填入登入表單 |
| 複製到剪貼簿 | 使用者明確表示不需要 |
| 密碼產生器、密碼強度檢查、洩漏比對（HIBP） | 不適用於提示型資料 |
| TOTP / 2FA 碼 | 超出範圍 |
| 附件、自訂欄位 | 資料模型刻意極簡 |
| 自建同步伺服器 | 外部同步工具已滿足需求 |
| 自動合併衝突 | 外包給桌面版 KeePassXC，見 §5 |

## 2. 技術棧

- **語言**：Kotlin（原提議 Java，已改為 Kotlin 以配合 Jetpack / 協程生態）
- **UI**：Jetpack Compose
- **minSdk**：29（Android 10）— scoped storage 自此為常態，SAF 行為一致，省去版本分支
- **targetSdk**：最新穩定版
- **儲存格式**：KDBX 4（KeePass 格式），相容 KeePassXC / KeePassDX
- **架構**：單一 Gradle module，內部以 package 分層

## 3. 模組邊界

```
crypto/     金鑰的一切。Argon2id 導出、Keystore 金鑰包裝/解包、生體辨識綁定。
            不認識 KDBX，不認識 UI。輸入密碼，輸出金鑰位元組。

vault/      KDBX 讀寫 + 領域模型 Entry。
            不認識檔案在哪。輸入 InputStream + 金鑰，輸出 Entry 清單。

storage/    SAF 檔案存取、URI 權限持久化、外部變動偵測、衝突另存。
            不認識加密內容，只管位元組進出與「檔案有沒有被動過」。

session/    解鎖狀態機、記憶體中 vault key 的生命週期、自動上鎖計時。

ui/         Compose 畫面。
```

**切分理由**：四層各對應一種失敗模式（金鑰導錯 / 格式解析錯 / 檔案覆蓋錯 / 金鑰滯留記憶體），可獨立測試與定位問題。`crypto` 與 `storage` 互不認識——前者不知道檔案存在，後者只看得到已加密位元組。

## 4. 加密與金鑰管理

### 4.1 金鑰階層

```
主密碼 ──Argon2id──→ vault key ──→ 解密 KDBX
                          │
                          └──AES-256-GCM──→ wrapped key（存 app 私有目錄）
                                  ↑
                          Android Keystore 金鑰（生體辨識綁定）
```

主密碼本身**永不儲存**，任何形式皆然。

### 4.2 KDF

- **Argon2id**，參數由 KDBX header 決定（開啟既有檔）
- 建立新資料庫時的預設：memory 64 MiB、iterations 4、parallelism 2
- 建立時實測一次耗時，若超過 2 秒則向下調整並告知使用者
- 實作走 JNI 原生函式庫（純 JVM 實作在行動裝置上過慢）

### 4.3 KDBX 加密

- 內容加密採 **AES-256**（KDBX4 預設，相容性最佳）
- ChaCha20 為可接受的替代選項，但非預設

### 4.4 Android Keystore（生體辨識快速解鎖）

Keystore 金鑰規格：

- AES-256-GCM
- `setUserAuthenticationRequired(true)`
- `setInvalidatedByBiometricEnrollment(true)` — 裝置新增指紋時金鑰自動作廢，防止他人註冊自己的生體特徵後解鎖
- API 30+ 使用 `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`；API 29 使用 `setUserAuthenticationValidityDurationSeconds(-1)`
- 嘗試 `setIsStrongBoxBacked(true)`，捕捉 `StrongBoxUnavailableException` 後退回 TEE

解鎖流程以 `BiometricPrompt` 搭配 `CryptoObject`，使金鑰的使用與該次生體辨識綁定，而非「驗證通過後自由取用」。

**強制回退主密碼的時機**：裝置重開機後、Keystore 金鑰失效後（生體特徵變更）、使用者於設定中關閉生體辨識後。

### 4.5 記憶體衛生

- 金鑰與主密碼一律以 `CharArray` / `ByteArray` 承載，不使用 `String`
- 用畢立即以零覆寫
- 上鎖時清除 session 中的 vault key

### 4.6 平台層防護

- 全視窗設定 `FLAG_SECURE`（擋截圖與最近工作畫面預覽）
- `android:allowBackup="false"`
- 提示欄位設 `importantForAutofill="no"`，避免內容被其他自動填入服務擷取
- 任何情況下不將提示內容、金鑰、主密碼寫入 log

## 5. 資料層

### 5.1 資料模型

```kotlin
Entry(
    id: UUID,
    title: String,        // 哪個服務
    hint: String,         // 提示內容
    modifiedAt: Instant,
    history: List<HistoricalHint>   // KDBX 原生 entry history
)
```

刻意不設獨立的「帳號」欄位——若有需要寫入標題即可。歷史記錄的用途：改了密碼並更新提示後，若某裝置仍為舊密碼，舊提示可救援。

### 5.2 檔案存取

- 以 **SAF**（Storage Access Framework）存取資料庫檔
- `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` 選定位置，`takePersistableUriPermission` 持久化權限
- 使用者通常會選同步工具監看的資料夾
- 不使用 `MANAGE_EXTERNAL_STORAGE`（權限過大）

### 5.3 外部變動偵測與衝突處理

**載入時**：記錄檔案的 `lastModified` 與密文位元組的 SHA-256。

**存檔前**：重新讀取並比對上述兩者。

- 一致 → 正常覆寫
- 不一致 → **不覆寫**。將本地版本另存為 `<原檔名>.conflict-<timestamp>.kdbx` 於同一目錄，並提示使用者於桌面版 KeePassXC 執行合併

**設計取捨**：自動合併（依 UUID + 修改時間）體驗較佳，但實作複雜且合併錯誤會**靜默丟失資料**——這是此類資料最糟的失敗模式。改由成熟的桌面工具在大螢幕上處理合併，本 app 只保證「絕不覆蓋他人的改動」，此性質易於實作與驗證。

**寫入安全**：SAF 無法可靠地做 temp-then-rename。因此每次覆寫前，先將前一版完整密文備份至 app 私有目錄，寫入失敗時可回復。

## 6. 上鎖策略

觸發上鎖（清除記憶體中的 vault key）：

- App 進入背景（預設立即，可設定 30 秒寬限）
- 螢幕關閉
- 閒置逾時（預設 5 分鐘）
- 使用者手動上鎖

## 7. 畫面

1. **解鎖** — 生體辨識優先，可切換至主密碼
2. **清單 / 搜尋** — 依標題搜尋
3. **詳情** — 顯示提示內容（無複製按鈕）
4. **編輯** — 新增 / 修改
5. **歷史** — 檢視某筆記錄的過往提示
6. **設定** — 資料庫位置、生體辨識開關、逾時設定、變更主密碼

## 8. 測試策略

**單元測試**（不需裝置）
- Argon2id 導出：以已知測試向量驗證
- KDBX 讀寫來回：以固定 fixture 檔驗證解析與序列化
- 衝突偵測邏輯：以假的 storage 實作驗證各種 mtime / hash 組合

**Instrumented 測試**（需裝置或模擬器）
- Keystore 金鑰產生、包裝、解包
- 生體特徵變更後金鑰確實失效
- SAF 選檔與權限持久化

**跨工具驗收測試（關鍵）**
- 本 app 建立的資料庫，需能於桌面 KeePassXC 正常開啟與讀取
- KeePassXC 建立的資料庫，需能於本 app 正常開啟
- 此測試若不通過，KDBX 相容性的整個立論即不成立

## 9. 主要風險

**KDBX 函式庫的 Android 相容性 —— 最高風險，須最先驗證。**

候選為 KeePassJava2（純 Java，jorabin/KeePassJava2）。風險在於該函式庫可能依賴 Android 不支援或需 desugaring 的 JVM 元件（如 XML binding、java.time、NIO 特性）。Argon2 亦需另尋 Android 可用的 JNI 綁定。

**緩解**：實作的第一步即為 spike——在最小 Android 專案中，實際以該函式庫開啟一個 KeePassXC 產生的 kdbx 檔。若失敗，備案依序為：

1. 改用 KeePassDX 的 database 模組（GPL-3.0，須留意授權傳染性）
2. 自行實作 KDBX4 的必要子集
3. 回頭重新評估「自訂格式」方案（原討論中的選項 B）

此 spike 的結果會實質影響後續工作量，應在撰寫完整實作計畫前完成。
