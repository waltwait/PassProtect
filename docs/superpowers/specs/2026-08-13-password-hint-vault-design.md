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
- **KDBX 函式庫**：`org.linguafranca.pwdb:KeePassJava2-kdbx:2.2.4`（Apache-2.0），**外加自行 vendor 並修補的 `dom` 模組**（見 §2.3）
- **架構**：單一 Gradle module，內部以 package 分層

### 為何選 DOM 模組而非 Jackson

函式庫作者在 readme 中將 Jackson 模組列為「未來主力」，但 **Jackson 模組不可用於 Android**：它依賴 `jackson-dataformat-xml` + `woodstox-core`，兩者建立在 StAX（`javax.xml.stream`）之上，而 Android 平台不提供 StAX。

DOM 模組是 readme 中唯一明確標示「Known to work on Android」者，且僅使用 `javax.xml.parsers` / `javax.xml.transform` / `javax.xml.xpath`——這些 Android 皆有內建。代價是解析效能較差，但本 app 的資料量（數百筆提示）下無實質差異。

### 2.3 dom 模組必須 vendor 並修補（實機驗證後的結論）

上游 `DomSerializableDatabase.save()` 有一行：

```java
Document copyDoc = (Document) doc.cloneNode(true);
```

Android 的 Harmony DOM **拒絕複製 Document 節點**，在真機上擲出：

```
org.w3c.dom.DOMException: Cannot copy node of type 9
```

亦即上游 readme 標示的「Known to work on Android」僅對**讀取**成立，**寫入在 Android 上完全不能用**。已於 Galaxy S25 / Android 16 實測確認。

**修法**：建立新 Document 並 `importNode` 根元素。已在真機驗證此法可深拷貝、與原文件獨立、且能正常序列化。

由於 `DomDatabaseWrapper` 以 `private final` 寫死 `DomSerializableDatabase`，無注入接縫，無法以繼承替換；而同名類別若同時存在於 app 與 jar 會造成 dex 重複類別錯誤。故採用：

- 只依賴 `KeePassJava2-kdbx`（容器與加密，完全不修改）
- 將 `dom` 模組的 6 個檔（共 1249 行，Apache-2.0，全為 public 類別）納入專案原始碼，僅修改上述一行

修補處須保留上游版權標頭並加註說明。此修正應同時回報上游。

### 2.4 必要的 Gradle 排除項

`util` 模組會傳遞引入兩個不該進入 Android runtime 的依賴，須排除：

```kotlin
implementation("org.linguafranca.pwdb:KeePassJava2-kdbx:2.2.4") {
    exclude(group = "ch.qos.logback")   // 不支援 Android
    exclude(group = "junit")            // 測試函式庫不應進入 runtime
}
```

日誌改用 slf4j 的 Android 綁定或 no-op 實作。

## 3. 模組邊界

```
crypto/     金鑰的一切。Argon2d 導出、Keystore 金鑰包裝/解包、生體辨識綁定。
            不認識 KDBX，不認識 UI。輸入密碼，輸出金鑰位元組。

vault/      KDBX 讀寫 + 領域模型 Entry。
            不認識檔案在哪。輸入 InputStream + 金鑰，輸出 Entry 清單。

storage/    SAF 檔案存取、URI 權限持久化、外部變動偵測、衝突另存。
            不認識加密內容，只管位元組進出與「檔案有沒有被動過」。

session/    解鎖狀態機、記憶體中 vault key 的生命週期、自動上鎖計時。

ui/         Compose 畫面。
```

**切分理由**：四層各對應一種失敗模式（金鑰導錯 / 格式解析錯 / 檔案覆蓋錯 / 金鑰滯留記憶體），可獨立測試與定位問題。`crypto` 與 `storage` 互不認識——前者不知道檔案存在，後者只看得到已加密位元組。

**更正（2026-08-15，最終全分支審查發現）**：上面「`crypto` 不認識 KDBX，不認識 UI」的說法在實作後不再完全準確。`crypto/WrappedKeyStore.kt` 會做檔案 I/O（把包裝過的 vault key 存進 app 私有目錄），`crypto/BiometricUnlock.kt` 需要一個 `FragmentActivity` 來綁定 `BiometricPrompt`。這兩者都是刻意的：`WrappedKeyStore` 存取的是 app 私有目錄，不是使用者選的 SAF 檔案，性質上比較接近「金鑰的一部分」而非「vault 的資料檔」；`BiometricPrompt` 綁定 Activity 是 Android 生體辨識 API 的硬性要求，繞不開。跨層的**依賴方向**依然乾淨（`crypto` 不依賴 `storage` 或 `vault`），只是「完全不認識檔案／UI」這句話對 `crypto` 內部這兩個檔案不成立，需要更新認知而非改架構。

## 4. 加密與金鑰管理

### 4.1 金鑰階層

```
主密碼 ──Argon2d──→ vault key ──→ 解密 KDBX
                          │
                          └──AES-256-GCM──→ wrapped key（存 app 私有目錄）
                                  ↑
                          Android Keystore 金鑰（生體辨識綁定）
```

主密碼本身**永不儲存**，任何形式皆然。

### 4.2 KDF

參數由 KDBX header 決定（開啟既有檔）。實作為 BouncyCastle 的**純 Java** Argon2（KeePassJava2 內建，非 JNI）。

#### 是 Argon2d，不是 Argon2id

原設計寫的是 Argon2id，但函式庫**只實作 Argon2d**：`Argon2.java` 寫死 `Argon2Parameters.ARGON2_d`，且註冊於 Argon2d 的 UUID `EF636DDF-…`。

Argon2d 為資料相依（data-dependent），理論上較易受旁通道分析；Argon2id 是混合式，為現行建議值。但 Argon2d 仍是 KeePass 原始預設、且為記憶體困難函式，對「手機本機的提示保險庫」此一威脅模型可接受。

**連帶風險**：KDF 登錄表是 Java `enum`（`Encryption.KeyDerivationFunction`），無法擴充，`getKdf(UUID)` 遇到未知 UUID 直接擲出例外。因此**若資料庫改用 Argon2id，本 app 將無法開啟**。由於資料庫由本 app 建立，預設不會發生；但須在文件中警告使用者不要在 KeePassXC 中把 KDF 改成 Argon2id。

#### 實機實測（Galaxy S25 / Android 16 / arm64，2026-08-13）

直接呼叫 KDF：

| 參數 | 耗時 |
|---|---|
| M=16 MiB, I=2 | 361 ms |
| M=32 MiB, I=2 | 542 ms |
| M=64 MiB, I=2 | 1712 ms |
| M=128 MiB, I=4 | 5936 ms |

**但實際存/取檔的耗時是上表的兩倍以上**，因為 `KdbxHeader.getTransformedKeyDigest()` **無任何快取**，而載入與儲存路徑各會呼叫它 2–3 次（`getHmacKey`、`createDecryptedStream`、`createEncryptedStream`）。端到端實測：

| 參數 | 實際存檔耗時 |
|---|---|
| M=16 MiB, I=2 | 1126 ms |
| **M=32 MiB, I=2** | **2241 ms** |
| M=64 MiB, I=2 | 3506 ms |

#### 決定

**建立新資料庫的預設值：memory 32 MiB、iterations 2、parallelism 2。** 於此旗艦機端到端約 2.2 秒；較慢的機器會更久，故建立資料庫時實際量測，超過 3 秒則自動下調並告知使用者。

**緩解因素**：因採用生體辨識快速解鎖（§4.4），Argon2 僅在首次解鎖與重開機後執行，日常開啟不經過 KDF。

**未來最佳化（非 v1 範圍）**：在已 vendor 的程式碼中為 transformed key 加上快取，可直接砍半解鎖時間；此為比抽換原生 Argon2 更划算的第一步。

### 4.3 KDBX 加密

- 內容加密採 **AES-256**（KDBX4 預設，相容性最佳）
- ChaCha20 為可接受的替代選項，但非預設

**已知取捨（2026-08-15，最終全分支審查發現）**：`Vault.serialize()` 目前無條件以 `KdbxHeader(4)` + AES 重新寫出檔案，不論開啟時讀到的原始格式為何。實際後果：桌面 KeePassXC 目前預設用 KDBX 3.1 / AES-KDF 建立資料庫（Task 12 互通驗收的實測結果），這種檔案只要被本 app 存過一次，就會被靜默升級成 KDBX 4 / Argon2d；若原始檔案用的是 ChaCha20，也會被換成 AES。

格式從 3.1 升到 4、KDF 從 AES-KDF 換成 Argon2d，就安全性而言是升級不是降級，資料本身不會遺失（跨格式相容性已驗證）。真正的風險是：若使用者仰賴的其他工具只認 KDBX 3.1，這個檔案存過一次之後可能在那個工具上打不開——形同把「鎖住」的風險轉嫁到使用者依賴的其他舊工具上，而非本 app。目前沒有任何使用者可見的提示告知這件事發生了。UI 計畫應在「開啟一個非本 app 建立的資料庫，且即將第一次存檔」時提示使用者格式將被升級。

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
    title: String,        // 哪個服務 → KDBX Title
    hint: String,         // 提示內容 → KDBX Password（受 inner stream 保護）
    modifiedAt: Instant,  // → KDBX Times/LastModificationTime
    history: List<HistoricalHint>   // → KDBX 自訂字串欄位，見下
)
```

刻意不設獨立的「帳號」欄位——若有需要寫入標題即可。歷史記錄的用途：改了密碼並更新提示後，若某裝置仍為舊密碼，舊提示可救援。

提示存入 KDBX 的 **Password 欄位**而非 Notes，因為 Password 是 KDBX 預設受 inner stream 保護的欄位。

### 歷史記錄的實作方式（重要：與原設計不同）

KDBX 格式本身有原生的 `<History>` 元素，但 **KeePassJava2 並未實作**——`HISTORY_ELEMENT_NAME` 常數在原始碼中僅有宣告、無任何使用處，公開 API 也不提供存取。底層的 `Element` 為 package-private，無法從外部操作真正的 History 節點。

因此歷史改以 **KDBX 自訂字串欄位**實作：

```
hist.1.at    = 2026-01-04T10:00:00Z
hist.1.hint  = 舊提示：國中同學的綽號
hist.2.at    = 2026-06-20T08:30:00Z
hist.2.hint  = 更舊的提示：第一台電腦型號
```

已實測驗證：自訂欄位可正確寫入、讀回，且在載入後修改並重新存檔時不會遺失既有欄位。

**取捨**：KeePassXC 會將這些顯示為 entry 的「額外屬性」，而非其原生的 History 分頁。功能不受影響，但呈現方式不如原生整合。

**注意**：自訂欄位預設不受 inner stream 保護（實測 `shouldProtect("hist.1.hint")` 回傳 false）。此僅影響已加密容器*內部*的 XML 層混淆，不影響靜態加密強度。若要一併保護，可繼承 `DomDatabaseWrapper` 並覆寫 `shouldProtect(String)`。

### 5.2 檔案存取

- 以 **SAF**（Storage Access Framework）存取資料庫檔
- `ACTION_OPEN_DOCUMENT_TREE` 選定**資料夾**（非單一檔案），`takePersistableUriPermission` 持久化權限
- 使用者選同步工具監看的資料夾；app 在其中尋找或建立資料庫檔
- 不使用 `MANAGE_EXTERNAL_STORAGE`（權限過大）

**更正（2026-08-14，於 Task 8 實作中發現）**：本節原訂 `ACTION_OPEN_DOCUMENT`，於實作時發現此路不通——該 intent 只授權單一檔案，不授權其所在目錄。以這種 URI 建構的 `DocumentFile`，其 `parentFile` 恆為 `null`（`androidx.documentfile:documentfile:1.0.1` 之 `RawDocumentFile` 與 `SingleDocumentFile` 皆然，已用 `javap` 反編譯該函式庫位元組碼並於真機實測雙重證實），因此**完全無法建立同目錄的兄弟檔案**——而 §5.3 的衝突偵測正是靠這個能力運作（衝突時另存 `.conflict-<timestamp>.kdbx` 到同一資料夾）。

改為 `ACTION_OPEN_DOCUMENT_TREE`：授權整個資料夾後，`DocumentFile.fromTreeUri()` 取得的目錄物件可正常 `findFile()` / `createFile()`，不依賴 `parentFile`。這與本節「使用者通常會選同步工具監看的資料夾」的原意其實一致——原意就是選資料夾，程式碼選錯了 API。

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
- Argon2d 導出：以已知測試向量驗證
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

## 9. Spike 結果（2026-08-13）

於 JVM（JDK 25）與實體 Android 裝置上實測 `KeePassJava2` 2.2.4。spike 專案保留於
[`spikes/android-kdbx/`](../../../spikes/android-kdbx/)，含修補後的 dom 模組與所有實機測試。

### JVM 端已驗證通過

| 項目 | 結果 |
|---|---|
| KDBX 4 檔頭寫出 | PASS（簽章 `03D9A29A 67FB4BB5`，版本 4.0） |
| Argon2 + AES-256 加解密 | PASS |
| 中文 UTF-8 內容來回 | PASS |
| 錯誤密碼遭拒 | PASS（擲出 `IllegalStateException`） |
| 自訂字串欄位來回 | PASS |
| 修改後重新存檔保留既有欄位 | PASS |

### 依賴分析

依賴樹對 Android 友善：guava 為 **`32.1.2-android`** 版本、BouncyCastle 可用於 Android、XML 僅用到 `javax.xml.parsers` / `transform` / `xpath`（Android 皆內建）、`java.time` 於 minSdk 29 原生支援。DOM 路徑**不含** StAX。

須排除 `logback-classic` 與 `junit`（見 §2）。

### KeePassXC 雙向互通（KeePassXC 2.7.12）

| 方向 | 結果 |
|---|---|
| KeePassXC 開啟本函式庫產生的 KDBX4/Argon2d 檔 | PASS |
| 自訂欄位在 KeePassXC 中可見（`show --all`） | PASS |
| KeePassXC 修改該檔後，自訂欄位完整保留 | PASS |
| 本函式庫開啟 KeePassXC 建立的檔（KDBX 3.1 / AES-KDF） | PASS |
| 本函式庫讀回被 KeePassXC 修改過的檔 | PASS |

KDBX 決策的核心立論（資料不會被本 app 鎖死）已雙向驗證成立。

**但有一個方向 spike 沒測到**（2026-08-14 於 Task 5 code review 中發現）：上表驗證的是「本 app 寫檔 → KeePassXC 改 → 本 app 讀回」。**從未驗證**「KeePassXC 寫檔 → 本 app 修改並存檔 → 對方的群組結構、圖示、其他欄位是否倖存」。後者才是「持有 DOM 不放而非重建文件」這個設計決策真正要保護的性質。已於實作階段補上對應的迴歸測試（Task 5）。

### 實機驗證（Galaxy S25，Android 16 / API 36，arm64-v8a）

| 測試 | 結果 |
|---|---|
| KDBX4 建立 → 儲存 → 載入來回（含中文與自訂欄位） | PASS（套用 §2.3 修補後） |
| 在裝置上讀取 KeePassXC 建立的資料庫 | PASS |
| Keystore 金鑰：`setUserAuthenticationRequired` + `setInvalidatedByBiometricEnrollment` + `AUTH_BIOMETRIC_STRONG` | PASS |
| **StrongBox 硬體支援** | **PASS（`strongbox_keystore=300`）** |

§4.4 所列的 Keystore 設定在此硬體上完全被接受，且 StrongBox 可用，不需退回 TEE。

### 因 spike 而變更的設計

1. 採 **DOM** 模組而非 Jackson（Jackson 需 StAX，Android 無）
2. dom 模組須 vendor 並修補 `cloneNode` 那一行（否則 Android 上無法寫入）
3. 依賴改為 `KeePassJava2-kdbx` 而非 `-dom`（避免 dex 重複類別）
4. 歷史記錄改以自訂字串欄位實作（函式庫未實作原生 History）
5. Argon2 預設參數自 64 MiB / I=4 下修為 32 MiB / I=2
6. KDF 實為 **Argon2d** 而非規劃的 Argon2id，並記錄 Argon2id 的相容性風險

## 10. 尚未驗證的項目

1. **生體辨識的完整解鎖流程** — 已驗證金鑰可依規格產生且受 StrongBox 保護，但 `BiometricPrompt` + `CryptoObject` 的實際解包流程需有 UI 才能測，留待實作階段。
2. **`invalidatedByBiometricEnrollment` 的實際失效行為** — 需真的在裝置上新增一枚指紋才能驗證，屬破壞性操作，留待實作階段以使用者同意的方式進行。
3. **SAF 外部變動偵測** — 需搭配實際同步工具驗證。

## 11. 開發環境（實測記錄）

- JDK：Android Studio 內建 JBR（OpenJDK 25.0.2）
- Android SDK：`/opt/homebrew/share/android-commandlinetools`（platforms 35/36、build-tools 34/35/36），**非** `~/Library/Android/sdk`
- Gradle 9.7.0、AGP 9.3.1
- 測試裝置：Samsung SM-S9210（Galaxy S25），Android 16 / API 36，已透過 adb 連線
- KeePassXC 2.7.12（`keepassxc-cli` 於 `/opt/homebrew/bin`）
