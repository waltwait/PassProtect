# 石墨介面重新設計 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 PassProtect 全部畫面換成「A 石墨」視覺（淺色／深色），縮短編輯畫面，並加上三個共享元素轉場。

**Architecture:** 先換主題（`Theme.kt` 的配色、字級、形狀＋兩個額外色），再建一組共用元件（`ui/components/`），畫面逐一改用這些元件；最後在 `PassProtectApp` 用 `SharedTransitionLayout` 包住 `NavHost` 做共享元素。所有可以抽成純函式的規則（日期文案、帳號展開、密碼一致、資料夾筆數、群組位置）都抽出來用 JVM 單元測試釘住；版面本身靠建置＋手機手動檢查。

**Tech Stack:** Kotlin、Jetpack Compose（BOM 2026.06.01）、Material 3、Navigation Compose 2.9.8、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-10-23-graphite-redesign-design.md`（執行者必須兩份一起讀；設計稿 <https://claude.ai/artifact/DNYNfsjpgUANYGDHJL75N7> 的「A 石墨（已選）」頁）

## Global Constraints

- 建置前必須 `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`（PATH 上沒有 java）。
- 每個 task 的驗證指令：`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`，期望 `BUILD SUCCESSFUL`。本機沒有模擬器或手機，儀器測試只能編譯、不能跑。
- 色值、字級、圓角一律照規格第 2 節的表，不得自行調整；畫面裡不寫死色碼或 sp，只讀 `MaterialTheme.colorScheme`、`MaterialTheme.typography`、`PassProtectTheme.extraColors`。
- 不內嵌任何字型；提示用 `FontFamily.Monospace`。
- 不動 `VaultViewModel` 的公開 API、`vault/`、`storage/`、`crypto/`、`session/`；route 字串全部不變。
- 必須保留的辨識字（儀器測試依賴）：`contentDescription`「新增」「標題」「提示」「主密碼」「確認主密碼」「搜尋」「鎖定」；文字「儲存」「解鎖」「選擇資料夾」、搜尋框 placeholder「搜尋」。唯一例外是「建立」→「建立資料庫」（Task 10 一起改測試）。
- 資料夾圖示一律 `Icons.Filled.Folder`、`extraColors.label` 色；其餘圖示改用 `Icons.Outlined` / `Icons.Rounded` 中最接近設計稿線條風格的（規格 2.4）。
- 程式註解用繁體中文，延續現有「解釋為什麼」的寫法與密度。
- 所有可點元素 ≥ 44dp；元件高度用 `heightIn(min = …)`，不用固定 `height(…)`，避免大字級被裁。
- commit 訊息沿用 repo 慣例 `feat(ui): …` / `fix(ui): …`，結尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## Review Focus

1. **很長的文字**（40 字標題、60 字提示、很長的分類名與網址）：大標題最多 2 行後省略、資料夾名稱 1 行省略、詳情頁提示永遠完整換行不截斷、收合帳號的提示預覽 1 行省略。→ Task 5、7、8 的手動檢查步驟。
2. **系統字級放大到 130%**：任何列、磚、分段、按鈕的文字都不被裁切。→ Task 3 的手動檢查步驟。
3. **鍵盤彈出**：編輯頁第二組帳號的提示欄、分類選單的「新分類」欄、首次設定的確認欄、設定頁變更主密碼的兩個欄位，都要在鍵盤上方可見（`imePadding` 已靠 manifest 的 `adjustResize` 生效）。→ Task 8、10、12 的手動檢查步驟。
4. **編輯頁經過網址搜尋往返**：展開中的帳號、分類選單選好的分類都不能被重置。→ Task 8 的 `expandedAfterRemoval` 單元測試＋往返手動檢查。
5. **轉場遇到消失的目標**：進詳情途中該筆被刪掉（顯示「找不到這筆記錄」且可返回）、預測式返回手勢拉到一半放棄（回到原頁、共享元素歸位）。→ Task 13 的手動檢查步驟。

---

### Task 0: 開分支並收好既有改動

**Files:** 無新檔；現有未 commit 的改動：動畫優化（`PassProtectApp.kt`、`PassProtectField.kt`、`PressScale.kt`、`EditScreen.kt`、`ListScreen.kt`、`UnlockScreen.kt`、`app/build.gradle.kts` 的版本號）、R8（`app/build.gradle.kts` release 區塊、`app/proguard-rules.pro`）、本規格與本計畫。

- [ ] **Step 1:** `git switch -c redesign/graphite`
- [ ] **Step 2:** 分三個 commit（不要用 `git add -p` 這類互動式指令，這個環境不支援）：`feat(ui): smoother transitions and press feedback`（六個 UI 檔）、`build: enable R8 for release; v1.0.17`（整個 `app/build.gradle.kts`＋`app/proguard-rules.pro`）、`docs: graphite redesign spec and plan`。
- [ ] **Step 3:** `git status` 期望 working tree clean。

---

### Task 1: 石墨主題

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/theme/Theme.kt`
- Test: `app/src/test/java/com/passprotect/ui/theme/GraphiteThemeTest.kt`

**Interfaces:**
- Produces:
  - `@Immutable data class PassProtectExtraColors(val label: Color, val borderStrong: Color)`
  - `internal val GraphiteDarkColors: ColorScheme`、`internal val GraphiteLightColors: ColorScheme`
  - `internal val GraphiteDarkExtra: PassProtectExtraColors`、`internal val GraphiteLightExtra: PassProtectExtraColors`
  - `object PassProtectTheme { val extraColors: PassProtectExtraColors @Composable @ReadOnlyComposable get() }`（跟 `@Composable fun PassProtectTheme(content)` 同名並存，同 `MaterialTheme` 的做法）
  - `internal val GraphiteTypography: Typography`、`internal val GraphiteShapes: Shapes`

- [ ] **Step 1: 寫失敗的測試** `GraphiteThemeTest`（色值直接引用規格 2.1 表）

```kotlin
private fun contrast(a: Color, b: Color): Float {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05f) / (lo + 0.05f)
}

@Test fun darkBackgroundIsGraphite() = assertEquals(Color(0xFF0B0C0E), GraphiteDarkColors.background)
@Test fun lightCardIsWhite() = assertEquals(Color(0xFFFFFFFF), GraphiteLightColors.surfaceContainer)
@Test fun labelPassesAaOnBackgroundInBothModes() {
    assertTrue(contrast(GraphiteDarkExtra.label, GraphiteDarkColors.background) >= 4.5f)
    assertTrue(contrast(GraphiteLightExtra.label, GraphiteLightColors.background) >= 4.5f)
}
@Test fun valuesPassAaOnCards() {
    assertTrue(contrast(GraphiteDarkColors.onSurfaceVariant, GraphiteDarkColors.surfaceContainer) >= 4.5f)
    assertTrue(contrast(GraphiteLightColors.onSurfaceVariant, GraphiteLightColors.surfaceContainer) >= 4.5f)
}
@Test fun primaryButtonTextIsReadable() {
    assertTrue(contrast(GraphiteDarkColors.onPrimary, GraphiteDarkColors.primary) >= 4.5f)
    assertTrue(contrast(GraphiteLightColors.onPrimary, GraphiteLightColors.primary) >= 4.5f)
}
@Test fun tertiaryMirrorsPrimary() {
    assertEquals(GraphiteDarkColors.primary, GraphiteDarkColors.tertiary)
    assertEquals(GraphiteLightColors.primary, GraphiteLightColors.tertiary)
}
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'com.passprotect.ui.theme.GraphiteThemeTest'`，期望編譯失敗（`GraphiteDarkColors` 未定義）。
- [ ] **Step 3: 實作。** 照規格 2.1 表填兩組 `darkColorScheme` / `lightColorScheme`（`tertiary`、`onTertiary` 設成跟 `primary`、`onPrimary` 同值）；`GraphiteTypography` 的角色對應：`headlineLarge` 32/Bold、`headlineMedium` 30/Bold（兩者 `letterSpacing = 0.02.em`）、`headlineSmall` 26/Bold、`titleMedium` 17/Medium、`titleSmall` 16/Medium（列標題）、`bodyLarge` 16/Normal（輸入）、`bodyMedium` 15/Normal（值）、`bodySmall` 13/Normal（副標）、`labelLarge` 13/Medium `letterSpacing = 0.12.em`（區段標籤）、`labelMedium` 12/Normal（欄位標籤、日期）、`labelSmall` 11/Normal `letterSpacing = 0.12.em`。`GraphiteShapes`：`extraSmall` 10dp、`small` 12dp、`medium` 16dp、`large` 18dp、`extraLarge` 20dp。`PassProtectTheme(content)` 依 `isSystemInDarkTheme()` 選一組，`CompositionLocalProvider(LocalExtraColors provides …)` 包住 `MaterialTheme(colorScheme, typography, shapes)`。保留並更新檔頭那段配色理由註解（灰階＋唯一紅色的理由不變，加一句石墨是 2026-10 從三個方向選定）。
- [ ] **Step 4:** 同 Step 2 指令，期望 6 個測試 PASS；再跑 Global Constraints 的完整驗證指令。
- [ ] **Step 5:** `git commit -m "feat(ui): graphite theme tokens"`

---

### Task 2: 文案與日期格式

**Files:**
- Create: `app/src/main/java/com/passprotect/ui/format/EntryFormat.kt`
- Test: `app/src/test/java/com/passprotect/ui/format/EntryFormatTest.kt`

**Interfaces:**
- Produces:
  - `enum class EntryRowMode { Browse, Search }`
  - `fun modifiedLabel(at: Instant, now: Instant, zone: ZoneId): String`
  - `fun longDate(at: Instant, zone: ZoneId): String`
  - `fun entryRowSubtitle(entry: Entry, mode: EntryRowMode, now: Instant, zone: ZoneId): String`
  - `fun historyCountLabel(count: Int): String`
  - `fun replacedLabel(at: Instant, zone: ZoneId): String`

- [ ] **Step 1: 寫失敗的測試**（`zone = ZoneId.of("Asia/Taipei")`、`now = Instant.parse("2026-10-23T04:00:00Z")`）

```kotlin
@Test fun sameYearShowsMonthAndDay() =
    assertEquals("10 月 18 日修改", modifiedLabel(Instant.parse("2026-10-18T02:00:00Z"), now, zone))
@Test fun earlierYearShowsYear() =
    assertEquals("2025 年 11 月 20 日修改", modifiedLabel(Instant.parse("2025-11-20T02:00:00Z"), now, zone))
@Test fun dayIsTakenInLocalZone() = // 台北已經是 10/18 凌晨
    assertEquals("10 月 18 日修改", modifiedLabel(Instant.parse("2026-10-17T20:00:00Z"), now, zone))
@Test fun longDateHasYear() =
    assertEquals("2026 年 10 月 20 日", longDate(Instant.parse("2026-10-20T02:00:00Z"), zone))
@Test fun browseSubtitleWithOneAccountIsJustTheDate()        // → "10 月 18 日修改"
@Test fun browseSubtitleWithTwoAccountsPrefixesTheCount()    // → "2 組帳號・10 月 20 日修改"
@Test fun searchSubtitleShowsCategory()                      // category = "金融" → "金融"
@Test fun searchSubtitleWithoutCategoryFallsBackToDate()     // category = "" → "10 月 18 日修改"
@Test fun historyCountLabels() {
    assertEquals("沒有舊提示", historyCountLabel(0))
    assertEquals("2 筆舊提示", historyCountLabel(2))
}
@Test fun replacedLabelReadsAsSentence() =
    assertEquals("2026 年 8 月 3 日換掉", replacedLabel(Instant.parse("2026-08-03T02:00:00Z"), zone))
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'com.passprotect.ui.format.EntryFormatTest'`，期望編譯失敗。
- [ ] **Step 3:** 實作上列六個函式；月、日不補零（「8 月 3 日」）；「・」是全形中點 U+30FB。`InstantFormat.kt` 的 `toDisplayString()` 保留（Task 7 之後若無人使用再刪）。
- [ ] **Step 4:** 同 Step 2，期望全部 PASS。
- [ ] **Step 5:** `git commit -m "feat(ui): entry date and subtitle copy helpers"`

---

### Task 3: 共用元件

**Files:**
- Create: `app/src/main/java/com/passprotect/ui/components/Grouped.kt`
- Create: `app/src/main/java/com/passprotect/ui/components/Headers.kt`
- Create: `app/src/main/java/com/passprotect/ui/components/Buttons.kt`
- Create: `app/src/main/java/com/passprotect/ui/components/HintSlip.kt`
- Test: `app/src/test/java/com/passprotect/ui/components/GroupPositionTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `PassProtectTheme.extraColors`、`GraphiteTypography` 角色
- Produces:
  - `Grouped.kt`：`enum class GroupPosition { Only, First, Middle, Last; companion object { fun of(index: Int, count: Int): GroupPosition } }`、`fun GroupPosition.shape(radius: Dp = 16.dp): Shape`、`@Composable fun GroupedRow(position: GroupPosition, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit)`、`@Composable fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, position: GroupPosition)`
  - `Headers.kt`：`@Composable fun IconTopBar(onBack: (() -> Unit)? = null, centerTitle: String? = null, actions: @Composable RowScope.() -> Unit = {})`、`@Composable fun LargeTitleHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, leading: (@Composable () -> Unit)? = null, large: Boolean = false)`、`@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null)`
  - `Buttons.kt`：`PillButton(onClick, modifier, enabled = true, content: @Composable RowScope.() -> Unit)`、`PrimaryWideButton(text: String, onClick, modifier, enabled = true)`（兩者內建 `pressScale`，各自持有 `MutableInteractionSource`）、`TonalButton(text: String, onClick, modifier, icon: ImageVector? = null)`、`SnippetChip(text: String, onClick, modifier)`、`RemovableChip(text: String, onRemove, modifier)`、`DashedAddButton(text: String, onClick, modifier)`、`<T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier)`
  - `HintSlip.kt`：`@Composable fun HintSlip(hint: String, modifier: Modifier = Modifier, label: String = "提示", fontSize: TextUnit = 20.sp)`

- [ ] **Step 1: 寫失敗的測試** `GroupPositionTest`

```kotlin
@Test fun singleRowIsOnly() = assertEquals(GroupPosition.Only, GroupPosition.of(0, 1))
@Test fun firstOfThree() = assertEquals(GroupPosition.First, GroupPosition.of(0, 3))
@Test fun middleOfThree() = assertEquals(GroupPosition.Middle, GroupPosition.of(1, 3))
@Test fun lastOfThree() = assertEquals(GroupPosition.Last, GroupPosition.of(2, 3))
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'com.passprotect.ui.components.GroupPositionTest'`，期望編譯失敗。
- [ ] **Step 3: 實作四個檔案。** 決策：
  - 群組分隔線：`GroupedRow` 本身 `clip(position.shape())` + `surfaceContainer` 底；非 `First`／`Only` 的列在頂端畫 1dp `outlineVariant` 線（`HorizontalDivider`）。靜態群組（設定、編輯頁的分類／網址）把 `GroupedRow` 直接疊在 `Column` 裡；`LazyColumn` 的群組逐 item 用同一個元件，不需要外框容器。這取代規格第 3 節「容器底色當分隔線」的做法，視覺相同但對 lazy 清單也成立。
  - `IconTopBar`：高 60dp（含上方 16dp），返回鈕在左；`centerTitle` 用 `titleMedium` 置中；`actions` 靠右。圖示按鈕 44dp、圖示色 `onSurfaceVariant`。
  - `LargeTitleHeader`：`large = true` 用 `headlineLarge`（主畫面），否則 `headlineMedium`；`maxLines = 2`、`TextOverflow.Ellipsis`；`leading` 是 52dp 圓角 16dp 方塊（分類頁的資料夾）；左右 20dp。
  - `SectionLabel`：`labelLarge` + `extraColors.label`，上 28dp 下 12dp，左右 20dp。
  - `PillButton` 高 38dp、全圓角、`primary`/`onPrimary`、停用時 `alpha = 0.38f`。`PrimaryWideButton` 全寬、`heightIn(min = 54.dp)`、圓角 16dp。`TonalButton` `surfaceContainerHigh` 底、圓角 10dp。`SnippetChip` 文字「＋ $text」、`borderStrong` 1dp 邊框、全圓角。`RemovableChip` 的 × 按鈕 `contentDescription = "刪除「$text」"`（沿用現有文案）。`DashedAddButton` 用 `drawBehind` 畫 1.5dp `borderStrong` 虛線圓角 16dp 框（`PathEffect.dashPathEffect`）。`SegmentedControl` 外框 `background` 底、圓角 12dp、內距 4dp，選中段 `primary`/`onPrimary`，每段 `Modifier.semantics { selected = … }`。
  - `SwitchRow`：M3 `Switch`，`checkedTrackColor = primary`、`checkedThumbColor = onPrimary`；整列 `toggleable`，標題 `bodyMedium`、說明 `labelMedium` + `label` 色。
  - `HintSlip`：`background` 底（比卡片深一階）、1dp `outlineVariant` 邊框、`small` 圓角、內距 14×16；小標 `labelSmall` + `label` 色；提示文字 `FontFamily.Monospace`、`FontWeight.Medium`、`fontSize`、行高 1.5，不設 `maxLines`。
- [ ] **Step 4:** 同 Step 2，期望 4 個測試 PASS；再跑完整驗證指令。
- [ ] **Step 5（Review Focus 2）：** 逐一檢查本 task 每個元件，凡是高度都是 `heightIn(min = …)`；把結果寫進 commit 訊息內文一行「all component heights are minimums」。
- [ ] **Step 6:** `git commit -m "feat(ui): graphite shared components"`

---

### Task 4: 輸入框改成石墨樣式

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/components/PassProtectField.kt`

**Interfaces:**
- Consumes: Task 1 色彩
- Produces: `PassProtectField` 簽章不變

- [ ] **Step 1:** 改樣式：欄位 `surfaceContainerHigh` 底、`extraSmall`（10dp）圓角、`heightIn(min = 46.dp)`；底線未聚焦 1dp `outlineVariant`、聚焦 2dp `primary`；標籤 `labelMedium`，未聚焦 `extraColors.label`、聚焦 `onSurface`；游標 `primary`。沿用現有 `updateTransition` 聚焦動畫、`contentDescription = label`、外部改值時游標移到最後的邏輯，全部不動。把「聚焦色取 tertiary」那段註解改成「取 primary」並說明 tertiary 已不當強調色。
- [ ] **Step 2:** 完整驗證指令，期望 `BUILD SUCCESSFUL`。
- [ ] **Step 3:** `git commit -m "feat(ui): restyle PassProtectField for graphite"`

---

### Task 5: 主畫面

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/screens/ListScreen.kt`
- Test: `app/src/test/java/com/passprotect/ui/screens/ListScreenFoldersTest.kt`

**Interfaces:**
- Consumes: Task 2 `entryRowSubtitle`、`EntryRowMode`；Task 3 `IconTopBar`、`LargeTitleHeader`、`SectionLabel`、`GroupedRow`、`GroupPosition`
- Produces:
  - `internal data class FolderSummary(val name: String, val count: Int)`、`internal fun folderSummaries(entries: List<Entry>): List<FolderSummary>`
  - `@Composable private fun EntryRow(entry: Entry, mode: EntryRowMode, position: GroupPosition, navController: NavController, viewModel: VaultViewModel, modifier: Modifier = Modifier)`（Task 6 的分類頁共用；Task 13 會在裡面加共享元素）
  - `@Composable private fun FolderTile(summary: FolderSummary, onClick: () -> Unit)`

- [ ] **Step 1: 寫失敗的測試** `ListScreenFoldersTest`

```kotlin
@Test fun foldersAreCountedAndSortedAndBlankIsExcluded() {
    val entries = listOf(entry(category = "金融"), entry(category = "工作"), entry(category = "金融"), entry(category = ""))
    assertEquals(listOf(FolderSummary("工作", 1), FolderSummary("金融", 2)), folderSummaries(entries))
}
@Test fun noCategoriesMeansNoFolders() = assertEquals(emptyList<FolderSummary>(), folderSummaries(listOf(entry(category = ""))))
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'com.passprotect.ui.screens.ListScreenFoldersTest'`，期望編譯失敗。
- [ ] **Step 3: 實作 `folderSummaries`**（排序沿用現有 `sorted()` 的字串自然序）。
- [ ] **Step 4:** 同 Step 2，期望 PASS。
- [ ] **Step 5: 改版面**（規格 4.1、設計稿「深色・主畫面」）：
  - 拿掉 `TopAppBar`，改 `IconTopBar`，`actions` 依序搜尋（`contentDescription` 展開前「搜尋」、展開後「關閉搜尋」，同現在）、鎖定「鎖定」、設定「設定」。
  - 整頁是一個 `LazyColumn`：第一個 item 是 `LargeTitleHeader("提示保險庫", subtitle = "${entries.size} 筆提示", large = true)`，接著搜尋框（`AnimatedVisibility` 開合同現在）、`setupError` 提示、「資料夾」`SectionLabel`＋三欄 `FolderTile` 格（沿用 `chunked(3)` 補位，名稱 `maxLines = 1` 省略、副字「N 筆」）、「未分類」`SectionLabel`＋`EntryRow` items（`key` = entry id、保留 `Modifier.animateItem()`）。
  - 搜尋框是私有 `SearchField`：`BasicTextField`，外觀同 `PassProtectField` 的填色、圓角與聚焦底線，但**沒有上方標籤**；前置放大鏡、後置關閉鈕（`contentDescription = "關閉搜尋"`）；placeholder「搜尋」**必須畫在 `decorationBox` 裡**，因為 `UiEndToEndTest` 用 `onNodeWithText("搜尋").performTextInput(…)` 對它打字，placeholder 必須落在可編輯的那個節點上，而且畫面上不能有第二個文字是「搜尋」的節點。
  - 搜尋中：資料夾與未分類換成搜尋結果的 `EntryRow`（`EntryRowMode.Search`、不加 `animateItem`，同現在的理由）。
  - 空狀態 `EmptyEntriesMessage`：文案不變，圖示與文字改 `extraColors.label`。
  - FAB：60dp、`MaterialTheme.shapes.extraLarge`、`primary`/`onPrimary`、`contentDescription = "新增"`，加上 `pressScale`。
  - `EntryRow`：鑰匙圖示放 36dp `surfaceContainerHigh` 方塊、標題 `titleSmall`（`maxLines = 2`）、副標 `entryRowSubtitle(…, now = Instant.now(), zone = ZoneId.systemDefault())`、尾端箭頭（`extraColors.label` 60% 透明度）。
  - `SwipeActionsRow`：外層 `clip` 從固定 `EntryCardShape` 改成參數 `shape: Shape`，由 `EntryRow` 傳 `position.shape()`；分隔線畫在 `SwipeActionsRow` 外面（不跟著滑動）。彈簧、速度接手、28% 門檻全部不動；把那段 clip 註解改寫成新的理由。刪掉不再使用的 `Card`、`EntryCardShape`。
- [ ] **Step 6:** 完整驗證指令，期望 `BUILD SUCCESSFUL`。
- [ ] **Step 7（Review Focus 1）：** 在手機上新增一筆 40 字標題、分類名 20 字的記錄，確認主畫面列標題兩行後省略、資料夾名稱一行省略。
- [ ] **Step 8:** `git commit -m "feat(ui): graphite main screen"`

---

### Task 6: 分類頁

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/screens/ListScreen.kt`（`CategoryEntriesScreen`）

**Interfaces:**
- Consumes: Task 5 `EntryRow`；Task 3 `IconTopBar`、`LargeTitleHeader`

- [ ] **Step 1:** 照規格 4.2：`IconTopBar(onBack = { navController.popBackStack() })`；`LazyColumn` 第一個 item 是 `LargeTitleHeader(categoryName, subtitle = "${filtered.size} 筆提示", leading = { 灰色資料夾 })`；接著 `EntryRow(…, EntryRowMode.Browse, …)` 並保留 `animateItem()`；清單後一行 `labelMedium` + `label` 色「往左滑一列可以編輯或刪除」。空分類維持 `EmptyEntriesMessage(hasFilter = true)`。
- [ ] **Step 2:** 完整驗證指令。
- [ ] **Step 3:** `git commit -m "feat(ui): graphite category screen"`

---

### Task 7: 詳情

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/screens/DetailScreen.kt`

**Interfaces:**
- Consumes: Task 2 `historyCountLabel`、`longDate`；Task 3 `IconTopBar`、`LargeTitleHeader`、`HintSlip`、`TonalButton`

- [ ] **Step 1:** 照規格 4.3 與設計稿「深色・詳情」：`IconTopBar(onBack)`；`LargeTitleHeader(entry.title)`；膠囊列（分類：灰色資料夾＋名稱、1dp `outlineVariant` 邊框；網址：地球圖示、`surfaceContainer` 底、點擊行為同現在）；每組帳號一張 `MaterialTheme.shapes.large` 卡片（`surfaceContainer` 底、1dp `outlineVariant` 邊框）：頂列「帳號 N」（只有一組時不顯示）＋人像圖示與帳號名稱、`HintSlip(account.hint)`、底列 `TonalButton("查看歷史", icon = History)` 與 `historyCountLabel(account.history.size)`；最後一行「最後修改：${longDate(entry.modifiedAt, zone)}」。找不到記錄時的「找不到這筆記錄，可能已被刪除」也套 `IconTopBar(onBack)`，讓使用者回得去（Review Focus 5）。保留檔頭「不放編輯／刪除」的 KDoc。
- [ ] **Step 2:** 完整驗證指令。
- [ ] **Step 3（Review Focus 1）：** 手機上把一組提示改成 60 字，確認詳情頁完整換行沒有被截斷。
- [ ] **Step 4:** `git commit -m "feat(ui): graphite detail screen"`

---

### Task 8: 新增／編輯

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/screens/EditScreen.kt`
- Create: `app/src/main/java/com/passprotect/ui/screens/CategoryPickerSheet.kt`
- Test: `app/src/test/java/com/passprotect/ui/screens/EditScreenAccountsTest.kt`

**Interfaces:**
- Consumes: Task 3 `IconTopBar`、`PillButton`、`GroupedRow`、`SectionLabel`、`SnippetChip`、`DashedAddButton`；Task 4 `PassProtectField`
- Produces:
  - `internal fun expandedAfterRemoval(ids: List<UUID>, removed: UUID, expanded: UUID): UUID`
  - `@Composable internal fun CategoryPickerSheet(current: String, existing: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: 寫失敗的測試** `EditScreenAccountsTest`（`a`、`b`、`c` 三個 UUID，`ids = listOf(a, b, c)`）

```kotlin
@Test fun removingAnotherAccountKeepsTheExpandedOne() = assertEquals(b, expandedAfterRemoval(ids, removed = a, expanded = b))
@Test fun removingTheExpandedOneExpandsTheNextOne() = assertEquals(c, expandedAfterRemoval(ids, removed = b, expanded = b))
@Test fun removingTheExpandedLastOneExpandsThePreviousOne() = assertEquals(b, expandedAfterRemoval(ids, removed = c, expanded = c))
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'com.passprotect.ui.screens.EditScreenAccountsTest'`，期望編譯失敗。
- [ ] **Step 3:** 實作 `expandedAfterRemoval`（`ids` 是移除前的清單）。
- [ ] **Step 4:** 同 Step 2，期望 PASS；`EditScreenEntryTest` 也照過。
- [ ] **Step 5: 改版面**（規格 4.4、設計稿「深色・編輯」）：
  - 頂列 `IconTopBar(onBack, centerTitle = if (existing == null) "新增" else "編輯") { PillButton(…) }`，把現有 `SaveButtonPhase` 的 `AnimatedContent` 與停用條件原封搬進 `PillButton` 的 content（`pressScale` 已內建，刪掉原本的 `saveInteractionSource`），文字「儲存」不變。
  - 存檔衝突／失敗訊息：改成 `surfaceContainer` 底、圓角 12dp 的提示條，文案不變。
  - 標題：私有 `TitleField`（`BasicTextField`、`headlineSmall`、底線 1dp `outlineVariant`、上方 `labelMedium` 標籤「標題」、`semantics { contentDescription = "標題" }`）。
  - 分類／網址：兩列 `GroupedRow`。分類列顯示目前分類，空白顯示「未分類」，點了開 `CategoryPickerSheet`；網址列內是 `BasicTextField`（`contentDescription = "網址"`）＋搜尋按鈕（`contentDescription = "搜尋網址"`，導覽同現在）。刪掉原本的 `SuggestionChip` 排。
  - `CategoryPickerSheet`：`ModalBottomSheet`；第一列「未分類」（`onPick("")`）、接著既有分類（目前的打勾）、最後 `PassProtectField(label = "新分類")` 加確定鈕；選了就 `onPick` 並關閉。
  - 帳號：`SectionLabel("帳號", trailing = "${accountFields.size} 組")`；`var expandedId by rememberSaveable { mutableStateOf(accountFields.first().id.toString()) }`（存字串，撐過網址搜尋往返）。展開那組：`MaterialTheme.shapes.medium` 卡片、1dp `borderStrong` 邊框、「帳號 N」＋刪除 ×（`contentDescription = "刪除這組帳號"`，只剩一組時不顯示；刪除時用 `expandedAfterRemoval` 決定下一個展開）、`PassProtectField("帳號（選填）")`、`PassProtectField("提示", monospace = true)`＋星號（`contentDescription = "存成常用提示詞"`）、`SnippetChip` 列（只在展開那組）。其他組：一列摘要（「帳號 N・帳號名稱」＋等寬提示預覽 `maxLines = 1` 省略），點了設成 `expandedId`。卡片加 `animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))`。
  - `DashedAddButton("新增帳號")`：新增一組並把 `expandedId` 設成它。
  - `entryToSave()`、`AccountFieldsSaver`、`WEB_SEARCH_RESULT_KEY` 處理不動。
- [ ] **Step 6:** 完整驗證指令。
- [ ] **Step 7（Review Focus 3、4）：** 手機上：(a) 兩組帳號、展開第二組，點提示欄，欄位在鍵盤上方；(b) 分類選單的「新分類」欄在鍵盤上方；(c) 展開第二組、從選單選一個分類，再按網址搜尋、選一個網址回來，第二組仍展開、分類仍是剛選的。
- [ ] **Step 8:** `git commit -m "feat(ui): graphite edit screen with collapsible accounts"`

---

### Task 9: 解鎖

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/screens/UnlockScreen.kt`

**Interfaces:**
- Consumes: Task 3 `PrimaryWideButton`；Task 4 `PassProtectField`

- [ ] **Step 1:** 照規格 4.6、設計稿「解鎖」：上方 `Spacer(weight(0.2f))` 後鎖頭方塊（64dp、圓角 20dp、`surfaceContainer` 底）、「提示保險庫」（`headlineSmall`）、「已上鎖」（`label` 色）；中間 `Spacer(weight(1f))`；下方區塊保留現有 78／22 位置原則的註解精神（底部 `Spacer(weight(0.22f))` 等比例），內容用 `Crossfade(targetState = usePassword || !state.biometricEnabled)`：指紋模式是 92dp 圓形按鈕（`surfaceContainer` 底、1dp `borderStrong` 邊框、`contentDescription = "驗證指紋"`）、「輕觸以驗證指紋」、底線文字按鈕「改用主密碼」；主密碼模式是 `PassProtectField(label = "主密碼")` + `PrimaryWideButton("解鎖")`（`pressScale` 已內建）。錯誤訊息文案不變，放在按鈕上方；`AnimatedVisibility` 轉圈與生物辨識的兩個 effect 不動。
- [ ] **Step 2:** 完整驗證指令。
- [ ] **Step 3:** `git commit -m "feat(ui): graphite unlock screen"`

---

### Task 10: 首次設定

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/screens/SetupScreen.kt`
- Modify: `app/src/androidTest/java/com/passprotect/ui/UiEndToEndTest.kt:89`
- Test: `app/src/test/java/com/passprotect/ui/screens/SetupPasswordMatchTest.kt`

**Interfaces:**
- Consumes: Task 3 `PrimaryWideButton`、`GroupedRow`；Task 4 `PassProtectField`
- Produces: `internal enum class PasswordMatch { Empty, Match, Mismatch }`、`internal fun passwordMatch(password: String, confirm: String): PasswordMatch`

- [ ] **Step 1: 寫失敗的測試**

```kotlin
@Test fun emptyConfirmShowsNothing() = assertEquals(PasswordMatch.Empty, passwordMatch("abc", ""))
@Test fun bothEmptyShowsNothing() = assertEquals(PasswordMatch.Empty, passwordMatch("", ""))
@Test fun equalIsMatch() = assertEquals(PasswordMatch.Match, passwordMatch("abc", "abc"))
@Test fun differentIsMismatch() = assertEquals(PasswordMatch.Mismatch, passwordMatch("abc", "abd"))
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'com.passprotect.ui.screens.SetupPasswordMatchTest'`，期望編譯失敗。
- [ ] **Step 3:** 實作 `passwordMatch`。
- [ ] **Step 4:** 同 Step 2，期望 PASS。
- [ ] **Step 5: 改版面**（規格 4.7、設計稿「首次設定・建立資料庫」）：
  - 選擇資料夾：圖示方塊、標題「選擇同步資料夾」、說明「例如 Syncthing、Nextcloud 的資料夾」、`PrimaryWideButton("選擇資料夾")`（文字不可變）。
  - 多個檔案：說明＋檔名 `GroupedRow` 清單。
  - 建立資料庫：上方對齊（不再垂直置中）的圖示方塊、標題「建立新的資料庫」、說明「這個資料夾裡還沒有資料庫。設定一組主密碼來建立；忘記主密碼就打不開資料庫，請記牢。」、兩個 `PassProtectField`（標籤「主密碼」「確認主密碼」不變）、依 `passwordMatch` 顯示「兩次輸入一致」（勾）或「兩次輸入不一致」（`error` 色）、`Spacer(weight(1f))`、`PrimaryWideButton("建立資料庫")`（啟用條件同現在）、一行「建立約需 3 到 6 秒，期間請勿離開」。保留 `imePadding` 與現有註解（改寫「垂直置中」那句為新的上方對齊理由）。
  - 備份還原 `AlertDialog`：不改，靠主題換色。
- [ ] **Step 6:** `UiEndToEndTest.kt:89` 的 `onNodeWithText("建立")` 改成 `onNodeWithText("建立資料庫")`。
- [ ] **Step 7:** 完整驗證指令（含 `compileDebugAndroidTestKotlin`）。
- [ ] **Step 8（Review Focus 3）：** 手機上清掉 app 資料走一次首次設定，確認欄打字時在鍵盤上方、一致／不一致提示即時切換。
- [ ] **Step 9:** `git commit -m "feat(ui): graphite setup screens"`

---

### Task 11: 舊提示

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/screens/HistoryScreen.kt`

**Interfaces:**
- Consumes: Task 2 `replacedLabel`；Task 3 `IconTopBar`、`LargeTitleHeader`、`SectionLabel`、`HintSlip`

- [ ] **Step 1:** 照規格 4.5、設計稿「舊提示」：`IconTopBar(onBack)`；`LargeTitleHeader("舊提示", subtitle = 標題・帳號名稱（帳號名稱空白時只有標題）)`；`HintSlip(account.hint, label = "目前的提示", fontSize = 18.sp)`；`SectionLabel("更早之前")`；時間軸 `LazyColumn`：每項左側 9dp 空心圓點＋1dp `outlineVariant` 直線（最後一項不畫線）、右側等寬舊提示（`onSurfaceVariant`）與 `replacedLabel(h.at, zone)`。沒有歷史時時間軸位置顯示「這組帳號還沒有換過提示」。找不到記錄／帳號時同 Task 7 的處理。
- [ ] **Step 2:** 完整驗證指令。
- [ ] **Step 3:** `git commit -m "feat(ui): graphite history timeline"`

---

### Task 12: 設定與網址搜尋頂列

**Files:**
- Modify: `app/src/main/java/com/passprotect/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/passprotect/ui/screens/WebSearchScreen.kt`

**Interfaces:**
- Consumes: Task 3 `IconTopBar`、`LargeTitleHeader`、`SectionLabel`、`GroupedRow`、`SwitchRow`、`SegmentedControl`、`RemovableChip`、`PrimaryWideButton`；Task 4 `PassProtectField`

- [ ] **Step 1:** 照規格 4.8、設計稿「設定」，區段順序「安全 → 常用提示詞 → 資料庫」：
  - 安全：`SwitchRow("生體辨識解鎖", "用指紋或臉部辨識取代輸入主密碼", …, GroupPosition.First)`（開關呼叫同現在）；自動上鎖列（說明「沒有操作多久後自動上鎖」＋`SegmentedControl(listOf(60_000L to "1 分鐘", 300_000L to "5 分鐘", 900_000L to "15 分鐘"), selected = uiState.autoLockPolicy.idleTimeoutMillis, onSelect = viewModel::setAutoLockTimeout)`）；「變更主密碼」列（`GroupPosition.Last`），點了切換 `var changingPassword by rememberSaveable`，`AnimatedVisibility` 展開兩個 `PassProtectField`（「新主密碼」「確認新主密碼」）＋`PrimaryWideButton("變更")`（啟用條件與呼叫同現在），變更後收合。
  - 常用提示詞：說明、`PassProtectField("新增提示詞")`＋新增鈕（`contentDescription = "新增"`）、`FlowRow` 排 `RemovableChip`（取代橫向捲動）。
  - 資料庫：一列 `GroupedRow(GroupPosition.Only)`「資料庫位置／重新選擇同步資料夾」，點了 `pickFolder.launch(null)`。
  - 刪掉 `SettingsSectionLabel`。保留 `imePadding` + `verticalScroll` 與那段註解。
- [ ] **Step 2:** `WebSearchScreen`：`TopAppBar` 換成 `IconTopBar(onBack, centerTitle = "搜尋網址")`，WebView 不動。
- [ ] **Step 3:** 完整驗證指令。
- [ ] **Step 4（Review Focus 3）：** 手機上展開「變更主密碼」，點確認欄，欄位在鍵盤上方。
- [ ] **Step 5:** `git commit -m "feat(ui): graphite settings and web search bar"`

---

### Task 13: 共享元素轉場

**Files:**
- Create: `app/src/main/java/com/passprotect/ui/motion/SharedElements.kt`
- Modify: `app/src/main/java/com/passprotect/ui/PassProtectApp.kt`
- Modify: `app/src/main/java/com/passprotect/ui/screens/ListScreen.kt`（`FolderTile`、`EntryRow`、FAB、`CategoryEntriesScreen` 標題）
- Modify: `app/src/main/java/com/passprotect/ui/screens/DetailScreen.kt`、`EditScreen.kt`

**Interfaces:**
- Consumes: Task 5 `FolderTile`、`EntryRow`；Task 6、7、8 的頁首
- Produces:
  - `val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }`
  - `val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }`
  - `object SharedKeys { fun folderIcon(name: String): String; fun folderTitle(name: String): String; fun entryBounds(id: UUID): String; fun entryTitle(id: UUID): String; const val NEW_ENTRY: String }`
  - `@Composable fun Modifier.sharedElementOrSelf(key: String): Modifier`、`@Composable fun Modifier.sharedBoundsOrSelf(key: String): Modifier`（任一 scope 為 null 時原樣回傳，讓畫面在沒有 `SharedTransitionLayout` 的地方照常運作）

- [ ] **Step 1:** 寫 `SharedElements.kt`。`boundsTransform` 一律 `spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)`；`sharedBounds` 用 `resizeMode = ScaleToBounds()`、enter/exit `fadeIn()`/`fadeOut()`。API 若標 `@ExperimentalSharedTransitionApi` 就在檔案層級 `@file:OptIn`。
- [ ] **Step 2:** `PassProtectApp`：`SharedTransitionLayout { CompositionLocalProvider(LocalSharedTransitionScope provides this) { NavHost(…) } }`；每個 `composable(…) { … }` 內再 `CompositionLocalProvider(LocalNavAnimatedScope provides this)`（抽一個 `NavGraphBuilder.screen(route) { backStackEntry -> }` 小幫手避免每個目的地重寫）。共軸換頁、上鎖閘門淡入淡出的現有設定不動。
- [ ] **Step 3:** 套用 key：
  - `FolderTile` 圖示 `sharedElementOrSelf(SharedKeys.folderIcon(name))`、名稱 `sharedElementOrSelf(SharedKeys.folderTitle(name))`；分類頁 `LargeTitleHeader` 的 `leading` 圖示與標題用同樣兩個 key。
  - `EntryRow` 外層（`SwipeActionsRow` 內、會滑動的那層）`sharedBoundsOrSelf(SharedKeys.entryBounds(id))`、標題 `sharedElementOrSelf(SharedKeys.entryTitle(id))`；詳情頁內容 `Column` 用 `entryBounds`、大標題用 `entryTitle`。搜尋結果與分類頁同一筆記錄共用同一 key（同一時間只有一處在畫面上）。
  - FAB `sharedBoundsOrSelf(SharedKeys.NEW_ENTRY)`；新增頁（`entryId == null`）的整頁內容用同一 key。
- [ ] **Step 4:** 完整驗證指令，再加 `./gradlew :app:assembleRelease`，期望 `BUILD SUCCESSFUL`（確認 R8 對新 API 沒有缺類別警告變錯誤）。
- [ ] **Step 5（Review Focus 5）：** 手機上（release 版）：(a) 點資料夾、點記錄、點「＋」三個轉場都是元素長成下一頁；(b) 在詳情頁用預測式返回手勢拉到一半放棄，回到詳情且元素歸位；拉到底，元素縮回清單列；(c) 在詳情頁時另一台裝置同步刪掉該筆（或在分類頁左滑刪除後立刻點同一列），顯示「找不到這筆記錄」且返回鍵可用。
- [ ] **Step 6:** `git commit -m "feat(ui): shared element transitions for folder, entry and new"`

---

### Task 14: 收尾驗證與版本號

**Files:**
- Modify: `app/build.gradle.kts`（`versionCode = 19`、`versionName = "1.0.18"`）
- Modify: `docs/superpowers/specs/2026-10-23-graphite-redesign-design.md`（狀態改「已實作」）

- [ ] **Step 1:** 改版本號與規格狀態。
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest :app:assembleRelease :app:compileDebugAndroidTestKotlin`，期望 `BUILD SUCCESSFUL`。
- [ ] **Step 3:** `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` 期望 `Signer #1 certificate DN: CN=PassProtect…`；`aapt2 dump xmltree --file AndroidManifest.xml …` 期望沒有 `debuggable`；APK 大小與 R8 開啟時（約 4.4 MB）同一量級。
- [ ] **Step 4:** `grep -rn "Color(0x" app/src/main/java/com/passprotect/ui --include='*.kt' | grep -v theme/Theme.kt` 期望沒有輸出（畫面沒有寫死色碼）。
- [ ] **Step 5:** 手機上淺色、深色各走一輪：首次設定（清資料）、解鎖（指紋＋主密碼）、主畫面、搜尋、分類頁、左滑編輯刪除、詳情、舊提示、新增、編輯、設定每一項；系統字級調到 130% 再看一次（Review Focus 2）；TalkBack 掃一次主畫面與編輯頁，每個圖示按鈕都唸得出名稱。
- [ ] **Step 6:** `git commit -m "chore: v1.0.18 graphite redesign"`
