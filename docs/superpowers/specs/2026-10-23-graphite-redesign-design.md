# PassProtect 介面重新設計（A 石墨）— 設計規格

日期：2026-10-23
狀態：已實作（2026-10-23，分支 redesign/graphite）
設計稿：<https://claude.ai/artifact/DNYNfsjpgUANYGDHJL75N7>（「A 石墨（已選）」頁；artboard 名稱在本文以「設計稿：xxx」標示）
前置條件：轉場與按壓動畫的優化（共軸換頁、左滑彈簧、`animateItem`）與 release 版 R8 已在工作目錄裡，本輪建立在它們之上

## 1. 這是什麼

在三個設計方向（A 石墨、B 午夜藍、C 紙本帳冊）中，使用者選了 A。B 的深藍底色與 C 的淺色襯線帳冊風格都沒採用，之後不要再拿來當主方向提案。

把整個 app 的畫面換成「石墨」這套視覺：保留現在灰階、單一紅色警示的配色哲學，靠層次、細邊框、大標題與一致的元件做出質感；換頁時讓點下去的東西跟下一頁連起來；把編輯畫面縮短到一屏看得完。

### 使用者要解決的問題

- 看起來太單調、不夠精緻高級
- 轉場不連貫：換頁像整頁硬切，點的東西跟下一頁沒有關聯
- 新增／編輯畫面太長

### 成功標準

1. 一眼看起來比現在精緻、有個性，淺色、深色模式都成立
2. 資料夾 → 分類頁、清單列 → 詳情、「＋」→ 新增，這三個轉場是「點的東西長成下一頁」，不是整頁切換
3. 編輯畫面在常見情況（一到兩組帳號）不用捲動就看得到所有欄位和「儲存」

### 範圍

- **要改**：配色、字級、形狀、元件樣式、每個畫面的排版、轉場
- **不改**：功能、資料模型、加密與儲存、導覽路由（`PassProtectApp` 的 route 字串全部不變）、ViewModel 的公開 API
- **刻意保留**（之前已經定案、不得重新提案）：
  - Finder 式資料夾格狀、點進去整頁換到分類頁（見記憶：抽屜、樹狀、chip 都已被否決）
  - 資料夾圖示一律灰色
  - 詳情頁不放編輯／刪除，那兩個動作只在清單左滑
  - 不用桌布動態取色（`dynamicColorScheme`）
  - 解鎖畫面的指紋按鈕在螢幕下半部（貼近感應器）

## 2. 設計語彙

### 2.1 色彩

`theme/Theme.kt` 的兩組 `ColorScheme` 換成下表。表中「M3 角色」是實作時對應的 `MaterialTheme.colorScheme` 欄位；`label`、`borderStrong` 兩個 Material 3 沒有對應角色，放進新的 `PassProtectExtraColors`（以 `CompositionLocal` 提供，`PassProtectTheme` 一起設定）。

| M3 角色 / 額外色 | 用途 | 深色 | 淺色 |
|---|---|---|---|
| `background`、`surface` | 畫面底色 | `#0B0C0E` | `#F4F4F2` |
| `surfaceContainer` | 卡片、群組容器、資料夾磚、設定列 | `#141518` | `#FFFFFF` |
| `surfaceContainerHigh` | 輸入框底、列前的圖示方塊、次要按鈕底 | `#1C1D21` | `#EFEFED` |
| `outlineVariant` | 細分隔線、卡片邊框（1dp） | `#23252A` | `#E3E3E0` |
| `borderStrong`（額外） | 展開中的帳號卡邊框、虛線「新增帳號」框、chip 邊框 | `#34363C` | `#C9C9C5` |
| `onSurface` | 主要文字、圖示 | `#F2F2F0` | `#141518` |
| `onSurfaceVariant` | 次要文字、欄位值、頂列圖示 | `#C9CACE` | `#3E4046` |
| `label`（額外） | 區段標籤、說明文字、日期、資料夾圖示 | `#8E9096` | `#6A6C72` |
| `primary` | FAB、主要按鈕、聚焦底線、選中的分段、開關軌道 | `#F2F2F0` | `#141518` |
| `onPrimary` | 主要按鈕上的文字／圖示 | `#0B0C0E` | `#FFFFFF` |
| `error` / `onError` | 刪除（全 app 唯一的彩色） | `#E5484D` / `#FFFFFF` | `#C62828` / `#FFFFFF` |

規則：

- `tertiary` 不再當強調色用。`PassProtectField` 聚焦色改讀 `primary`，`tertiary` 設成跟 `primary` 同值，避免漏改的地方冒出舊顏色。
- 列尾箭頭（chevron）用 `label` 加 60% 透明度，不另外開色。
- 對比：`label` 在各自底色上都 ≥ 4.5:1（深色約 6:1、淺色約 4.9:1），可以放 12sp 文字。

### 2.2 字體與字級

不內嵌任何字型：中文用系統 sans（Android 上就是 Noto Sans CJK，跟設計稿用的 Noto Sans TC 同一套），提示用系統 `FontFamily.Monospace`。APK 不因字型變大。

| 用途 | 大小 / 字重 | 備註 |
|---|---|---|
| 主畫面大標題 | 32sp / Bold | 字距 0.02em |
| 其他頁大標題 | 30sp / Bold | 字距 0.02em |
| 編輯頁標題輸入 | 26sp / Bold | |
| 詳情頁提示 | 20sp / Medium、等寬 | 行高 1.5 |
| 列標題 | 16sp / Medium | |
| 欄位值、設定列名稱 | 15sp / Regular | |
| 頂列文字（「編輯」） | 17sp / Medium | |
| 次要說明、副標 | 13sp / Regular | `label` 色 |
| 區段標籤 | 13sp / Medium | 字距 0.12em、`label` 色 |
| 欄位標籤、日期 | 12sp / Regular | `label` 色 |

以上做成自訂 `Typography`（對應到 M3 的 `headlineLarge`、`headlineMedium`、`titleMedium`、`bodyLarge`、`bodyMedium`、`labelMedium`、`labelSmall` 等），畫面只讀 `MaterialTheme.typography`，不在畫面裡寫死 sp。

### 2.3 形狀與間距

| 元素 | 圓角 |
|---|---|
| 群組容器、資料夾磚、帳號卡（編輯頁） | 16dp |
| 帳號卡（詳情頁） | 18dp |
| 提示區塊（詳情頁）、輸入框 | 12dp / 10dp |
| 列前圖示方塊 | 10dp |
| FAB | 20dp（60dp 見方） |
| 主要按鈕（儲存）、chip | 全圓角 |

- 標題、區段標籤左右留白 20dp；容器左右 16dp；區段之間 28dp
- 列內距 12dp × 14dp；所有可點元素 ≥ 44dp

### 2.4 圖示

沿用 `material-icons-extended`，改用 `Icons.Outlined` / `Icons.Rounded` 中最接近設計稿線條風格的；資料夾維持實心 `Icons.Filled.Folder`、`label` 色。R8 已開，沒用到的圖示會被裁掉。

## 3. 共用元件

新增或改寫在 `ui/components/`：

| 元件 | 用途 | 說明 |
|---|---|---|
| `LargeTitleHeader` | 每頁頂部 | 頂列只放圖示按鈕（返回／搜尋／鎖定／設定），標題在下方以大字顯示，可帶副標（「20 筆提示」）與前置圖示（分類頁的資料夾方塊）。標題跟內容一起捲走，頂列固定。 |
| `SectionLabel` | 「資料夾」「未分類」「帳號」等 | 左邊標籤，右邊可選的數量（「2 組」）。 |
| `GroupedList` / `GroupedRow` | 未分類清單、分類頁清單、設定、編輯頁的「分類／網址」 | 一個圓角容器，列與列之間 1dp `outlineVariant` 分隔線。做法：容器底色設成分隔線色、列之間留 1dp 間距、每列自己填 `surfaceContainer`。 |
| `FolderTile` | 主畫面資料夾格 | 灰色資料夾圖示＋名稱＋「N 筆」，三欄等寬格線（沿用現有 `chunked(3)` 補位做法）。 |
| `EntryRow` | 清單裡的一筆記錄 | 鑰匙圖示方塊＋標題＋副標＋箭頭，放進 `SwipeActionsRow`。副標：主畫面「未分類」與分類頁顯示「（N 組帳號・）修改日期」，只有一組帳號時省略前半；搜尋結果顯示分類名，沒有分類時顯示修改日期。修改日期今年內寫「M 月 D 日修改」，往年寫「YYYY 年 M 月 D 日修改」。 |
| `HintSlip` | 詳情頁的提示區塊 | 底色比卡片深一階，小標「提示」＋等寬大字提示。 |
| `PassProtectField`（改寫） | 所有輸入框 | 填 `surfaceContainerHigh`、圓角 10dp、底線 1dp `outlineVariant`；聚焦時底線 2dp `primary`、標籤變 `onSurface`（聚焦動畫沿用現有 `updateTransition`）。`contentDescription` 規則不變。 |
| `PillButton` | 頂列「儲存」 | `primary` 底、全圓角、高 38dp；停用時降透明度。內部沿用現有 `SaveButtonPhase` 的 `AnimatedContent`。 |
| `TonalButton` | 「查看歷史」 | `surfaceContainerHigh` 底、圓角 10dp。 |
| `SnippetChip` | 常用提示詞 | `borderStrong` 邊框、全圓角、文字前加「＋」。 |
| `DashedAddButton` | 「新增帳號」 | `borderStrong` 1.5dp 虛線框、高 52dp。 |
| `SegmentedControl` | 自動上鎖 1／5／15 分鐘 | 選中段 `primary` 底、`onPrimary` 字。 |
| `SwitchRow` | 生體辨識解鎖 | 名稱＋說明＋開關（M3 `Switch` 換色）。 |

`SwipeActionsRow` 調整：清單列現在是群組容器裡相連的列，圓角由容器負責，`SwipeActionsRow` 自己不再 `clip(EntryCardShape)`；露出的「編輯／刪除」鈕高度跟著列高。

## 4. 各畫面

### 4.1 主畫面（設計稿：深色・主畫面、淺色・主畫面）

- 頂列右側：搜尋、鎖定、設定三個圖示按鈕；不再有 `TopAppBar` 標題
- 大標題「提示保險庫」＋副標「N 筆提示」
- 「資料夾」區段：`FolderTile` 三欄格，每格顯示該分類筆數
- 「未分類」區段：`GroupedList` + `EntryRow`；沒有任何分類時不顯示「未分類」標籤（同現在）
- FAB：60dp、圓角 20dp、`primary` 底、`contentDescription = "新增"`
- 搜尋：點搜尋圖示，在大標題下方展開搜尋框（沿用現有開合與 `animateItem` 例外規則），placeholder 維持「搜尋」；有關鍵字時資料夾與未分類換成搜尋結果的 `GroupedList`
- 空狀態沿用現有文案，改用 `label` 色

### 4.2 分類頁（設計稿：分類頁）

- 大標題＝分類名，前置灰色資料夾方塊（52dp），副標「N 筆提示」
- `GroupedList` + `EntryRow`（副標規則見第 3 節）
- （原本清單下方有一行「往左滑一列可以編輯或刪除」的提示，2026-10-24 依使用者要求拿掉）

### 4.3 詳情（設計稿：深色・詳情、淺色・詳情）

- 大標題＝記錄標題
- 下方一排膠囊：分類（灰色資料夾圖示）、網址（地球圖示，可點開瀏覽器，行為同現在）
- 每組帳號一張卡：上方「帳號 N」（只有一組時不顯示編號）與帳號名稱；中間 `HintSlip`；底部 `TonalButton`「查看歷史」與右側「N 筆舊提示」／「沒有舊提示」（取 `account.history.size`）
- 最下方「最後修改：YYYY 年 M 月 D 日」

### 4.4 新增／編輯（設計稿：深色・編輯、淺色・編輯）

結構（由上而下）：

1. 頂列：返回、置中的「新增」或「編輯」、右側 `PillButton`「儲存」（停用條件與三段動畫同現在）
2. 存檔失敗／衝突訊息：維持在最上方，改成 `surfaceContainer` 底的提示條
3. 標題：大字輸入框（26sp），`contentDescription = "標題"`
4. 「分類／網址」`GroupedList`：
   - 分類列：顯示目前分類（空白時顯示「未分類」），點了開 `ModalBottomSheet`：最上面一列「未分類」（清空分類），接著列出既有分類（`GroupedList`，目前的分類打勾），最下面一個「新分類」輸入框；選了就關閉。取代原本欄位下方那排 `SuggestionChip`
   - 網址列：列內直接是輸入框，右側搜尋按鈕（導去 `WebSearchScreen`，行為同現在）
5. 「帳號」區段，右側顯示「N 組」：
   - 同一時間只有**一組**帳號展開：帳號欄、提示欄（附「存成常用提示詞」星號）、常用提示詞 chip 列、右上角刪除這組的 ×（只剩一組時不顯示）
   - 其他帳號收成一列摘要（「帳號 N・帳號名稱」＋等寬的提示預覽），點一下就展開它、原本展開的那組收合
   - 預設展開第一組；按「新增帳號」會新增一組並展開它
   - 展開中的帳號 id 用 `rememberSaveable` 保存，撐過網址搜尋那趟往返（同現有欄位的處理理由）
6. `DashedAddButton`「新增帳號」

`entryToSave()` 與帳號歷史規則完全不動。

### 4.5 舊提示（設計稿：舊提示）

- 大標題「舊提示」，副標「記錄標題・帳號名稱」（帳號名稱空白時只顯示標題）
- 「目前的提示」卡片：顯示這組帳號現在的提示，當對照
- 「更早之前」：時間軸，由新到舊，每點顯示舊提示（等寬）與「YYYY 年 M 月 D 日換掉」（`HistoricalHint.at` 就是被換掉的時間）
- 沒有歷史時：時間軸位置顯示「這組帳號還沒有換過提示」

### 4.6 解鎖（設計稿：解鎖）

- 上方約 20% 高度處：鎖頭圖示方塊、「提示保險庫」、「已上鎖」
- 下方（保留現在 78／22 的位置原則）：92dp 圓形指紋按鈕（`contentDescription = "驗證指紋"`）、「輕觸以驗證指紋」、底線文字按鈕「改用主密碼」
- 主密碼模式：同一個下方區塊換成主密碼欄（`contentDescription = "主密碼"`）＋全寬主要按鈕「解鎖」；兩種模式切換用淡入淡出
- 錯誤訊息顯示在按鈕上方，文案不變；轉圈沿用現有 `AnimatedVisibility`

### 4.7 首次設定（設計稿：首次設定・建立資料庫）

- 選擇資料夾：圖示方塊、標題「選擇同步資料夾」、說明「例如 Syncthing、Nextcloud 的資料夾」、全寬主要按鈕「選擇資料夾」（文字不變，冒煙測試依賴它）
- 多個檔案：說明文字＋檔名的 `GroupedList`
- 建立資料庫：標題「建立新的資料庫」、說明（含「忘記主密碼就打不開資料庫」）、主密碼、確認主密碼；確認欄有內容時即時顯示「兩次輸入一致」（勾）或「兩次輸入不一致」；底部全寬按鈕「建立資料庫」與一行「建立約需 3 到 6 秒，期間請勿離開」
- 備份還原對話框：沿用 M3 `AlertDialog`，靠主題換色

### 4.8 設定（設計稿：設定）

區段順序改成：

1. **安全**（`GroupedList`）
   - 生體辨識解鎖：`SwitchRow`（開／關的 ViewModel 呼叫同現在）
   - 自動上鎖：說明＋`SegmentedControl`（1／5／15 分鐘）
   - 離開 app 後：說明＋`SegmentedControl`（立即／30 秒／1 分鐘，預設 30 秒；2026-10-24 加，讓人能切去別的 app 複製再回來貼上）
   - 變更主密碼：一列，點了在原地展開兩個密碼欄＋「變更」按鈕，再點收合
2. **常用提示詞**：說明、新增輸入框（右側＋）、可刪除的 chip（換行排列，不再橫向捲動）
3. **資料庫**：一列「資料庫位置／重新選擇同步資料夾」

### 4.9 網址搜尋

只換頂列樣式（返回＋標題），WebView 本身不動。

## 5. 轉場

延續已完成的部分（共軸換頁、上鎖／解鎖只淡入淡出、左滑彈簧＋速度接手、清單增刪 `animateItem`、按壓縮放、存檔鈕三段動畫），本輪新增：

| 轉場 | 做法 |
|---|---|
| 資料夾 → 分類頁 | 共享元素：資料夾磚的圖示 → 分類頁標題旁的資料夾方塊；分類名稱文字 → 大標題 |
| 清單列 → 詳情 | 容器轉換：列的外框（`sharedBounds`，key 用 entry id）長成詳情頁內容區；標題文字 → 大標題 |
| FAB → 新增 | 容器轉換：FAB 長成新增頁 |
| 編輯頁帳號展開／收合 | `animateContentSize` + 內容淡入淡出 |
| 設定頁「變更主密碼」展開 | 同上 |
| 解鎖頁指紋／主密碼切換 | 交叉淡化 |

規則：

- 石墨的動態個性是**俐落、不回彈**：共享元素用臨界阻尼彈簧（`dampingRatio = 1f`、`StiffnessMediumLow`），其餘沿用現有的共軸時序
- 實作：`PassProtectApp` 用 `SharedTransitionLayout` 包住 `NavHost`，透過 `CompositionLocal` 把 `SharedTransitionScope` 與各目的地的 `AnimatedContentScope` 交給畫面。API 若仍標 `@ExperimentalSharedTransitionApi` 就 opt-in
- 沒有對應共享元素的換頁（設定、舊提示、網址搜尋）維持共軸換頁
- 預測式返回手勢下，共享元素跟著手勢進度倒退（Navigation Compose 內建支援，需在手機上驗）
- 每個共享元素照角色設定縮放、淡入淡出與疊放順序（`ui/motion/SharedElements.kt`，2026-10-24 補上）。轉場途中配對到的東西畫在 overlay，換頁的淡入淡出跟外層的裁切都管不到它們，用預設值會出現：整頁幾乎原尺寸滑向那一列再自己淡掉（關閉時「閃一下就下去」）、FAB 被放大成一大塊色塊、被截斷的資料夾名稱被放大好幾倍。目前的分工：
  - 整頁（詳情、新增）：等比縮到蓋滿外框並裁切、頂端對齊，不淡入淡出，畫在最底層
  - 被點的東西（清單列、FAB）：疊在整頁上面淡入淡出；清單列帶著自己的圓角，FAB 不縮放、留在右下角原地淡掉
  - 資料夾磚 ↔ 標題旁方塊：每格用動畫中的大小重畫（邊框、圓角維持原粗細），兩邊都不透明
  - 文字與圖示：照行高比例縮放、靠左，永遠畫在最上層
- 列被搬去 overlay 時原位會空出來：左滑的「編輯／刪除」只在那一列真的被拉開時才畫，平常底下墊的是跟列同色的底
- 系統「移除動畫」設定由 Compose 自動遵守，不另外處理

## 6. 會動到的檔案

| 檔案 | 變更 |
|---|---|
| `ui/theme/Theme.kt` | 兩組新配色、`Typography`、`Shapes`、`PassProtectExtraColors` |
| `ui/components/*` | 第 3 節的新元件；`PassProtectField` 改寫 |
| `ui/screens/ListScreen.kt` | 主畫面、分類頁、`EntryRow`、`SwipeActionsRow` 調整 |
| `ui/screens/DetailScreen.kt` | 4.3 |
| `ui/screens/EditScreen.kt` | 4.4（分類 bottom sheet、單一展開帳號） |
| `ui/screens/HistoryScreen.kt` | 4.5 |
| `ui/screens/UnlockScreen.kt` | 4.6 |
| `ui/screens/SetupScreen.kt` | 4.7 |
| `ui/screens/SettingsScreen.kt` | 4.8 |
| `ui/screens/WebSearchScreen.kt` | 4.9 |
| `ui/PassProtectApp.kt` | `SharedTransitionLayout` |

ViewModel、`vault/`、`storage/`、`crypto/`、`session/` 都不動。

## 7. 實作分段

每段結束都要能建置、能裝到手機上用：

1. 主題與共用元件（換完主題，舊畫面就會先跟著換色）
2. 主畫面、分類頁、詳情
3. 新增／編輯
4. 解鎖、首次設定、舊提示、設定、網址搜尋
5. 共享元素轉場

## 8. 測試

- **JVM 單元測試**：`entryToSave` 等邏輯不動，現有測試應全數照過
- **儀器測試**（`UiEndToEndTest`、`MainActivitySmokeTest`）靠以下文字與 `contentDescription` 找元件，重新設計後必須維持：
  - `contentDescription`：「新增」「標題」「提示」「主密碼」「確認主密碼」「搜尋」「鎖定」
  - 文字：「儲存」「解鎖」「選擇資料夾」、搜尋框 placeholder「搜尋」
  - **例外**：建立資料庫的按鈕文字從「建立」改成「建立資料庫」，`UiEndToEndTest` 對應那一行要一起改
- **手機手動驗證**（本機沒有模擬器）：每個畫面的淺色／深色、三個共享元素轉場（含預測式返回手勢）、鍵盤彈出時欄位不被蓋住（`imePadding`）、TalkBack 唸得出每個圖示按鈕、release 版（R8）跑一次解鎖與存檔

## 9. 不在本輪

- 大標題捲動時縮進頂列
- 內嵌字型（中文襯線、JetBrains Mono）
- 平板／橫向版面
- 任何新功能（例如複製提示、詳情頁編輯）
