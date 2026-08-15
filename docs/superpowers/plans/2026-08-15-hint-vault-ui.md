# 密碼提示保險庫 UI 實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已完成的核心引擎（`crypto` / `vault` / `storage` / `session`）之上，建立六個 Compose 畫面（含首次設定流程），做出第一個裝得到手機上、真的能用的 app，並補上核心引擎最終審查標記的三個缺口（生體辨識串接、建立新資料庫、備份還原）。

**Architecture:** 單一 `MainActivity`（`FragmentActivity`，因為 `BiometricPrompt` 需要）+ Navigation Compose + 一個 `VaultViewModel`（純 `ViewModel`，不持有 `Context`/`Activity`）持有 `VaultSession`。畫面只透過 `StateFlow<VaultUiState>` 觀察狀態、呼叫 `VaultViewModel` 的函式；`BiometricUnlock` 需要 `FragmentActivity`，因此**不**存在 ViewModel 欄位裡，而是由呼叫端（Composable）現場建構後當參數傳入，避免跨螢幕旋轉洩漏 Activity。

**Tech Stack:** Jetpack Compose（`compose-bom:2026.06.01`）、Navigation Compose、`lifecycle-viewmodel-compose`、既有的 `KeePassJava2-kdbx` / `androidx.biometric` / `androidx.documentfile`。Compose Compiler 透過 `org.jetbrains.kotlin.plugin.compose:2.4.10` 這個獨立 Gradle plugin 啟用——本專案的 AGP 9.3.1 內建 Kotlin 編譯，沒有另外的 Kotlin Gradle plugin，這個組合已在 scratchpad 的 spike 專案中以 `clean` + `compileDebugKotlin` 實際編譯驗證過（Compose BOM、`activity-compose:1.10.1`、`navigation-compose:2.9.8`、`lifecycle-viewmodel-compose:2.11.0` 皆確認可與此環境相容）。

**Spec:** `docs/superpowers/specs/2026-08-15-hint-vault-ui-design.md`

## Global Constraints

- 主密碼 / vault key 一律走 `CharArray` / `ByteArray`，不得經過 `String`；用完呼叫 `wipe()` 或 `useThenWipe {}`。**唯一例外**：Compose 的 `TextField` 系列元件的 `value` 型別是 `String`，這是框架本身的限制，無法迴避。從輸入框讀到字串後，應立刻轉成 `CharArray` 交給下一層（`viewModel.xxx(input.toCharArray())`），不得把該字串另外複製、記錄、或存進比 Composable 生命週期更長的地方。
- 生體辨識解鎖包裝的是**主密碼本身**（UTF-8 位元組），不是抽象的 vault key——規格 §2 的安全政策變更，`crypto.BiometricUnlock` 簽章不變，語意變了。
- `VaultViewModel` 不得持有 `Context`、`Activity`、或任何依賴它們的物件（`SafVaultFile`、`BiometricUnlock`）當作欄位——這些一律由呼叫端在需要時當參數傳入。這是本計畫從核心引擎完成後新發現、必須遵守的邊界，理由是螢幕旋轉會重建 Activity 但不會重建 ViewModel。
- `VaultRepository.save()` 回傳 `SaveResult.Conflict` 時，UI 必須明確告知使用者、且不得導覽離開編輯畫面（避免使用者誤以為存檔成功覆蓋了本尊）。
- 解鎖失敗訊息必須區分「密碼錯」「格式不支援」「檔案本身找不到/壞掉」三種，不可合併成一種泛用錯誤。
- 新建檔案固定檔名 `vault.kdbx`；app 內不提供更名功能。
- 每個 Task 的 JVM 單元測試（`app/src/test/...`）不得依賴真實 Android 框架（`Context`、`ContentResolver`、`BiometricPrompt`）——這類行為改用 `app/src/androidTest/...`（真機/模擬器）或記錄進 `docs/manual-verification.md`，沿用核心引擎既有的分工。

---

### Task 1: Compose 專案骨架 + `MainActivity`

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/hintvault/ui/MainActivity.kt`
- Test: `app/src/androidTest/java/com/hintvault/ui/MainActivitySmokeTest.kt`

**Interfaces:**
- Produces: `com.hintvault.ui.MainActivity`（`FragmentActivity`，`setContent {}` 目前顯示佔位文字，Task 16 會換成真正的導覽圖）。

- [ ] **Step 1: 加入 Compose 相關 Gradle 依賴與 plugin**

把 `app/build.gradle.kts` 換成（`compileSdk = 37`：Task 1 實際執行時發現 `lifecycle-viewmodel-compose:2.11.0` 的 AAR metadata 要求 compileSdk ≥ 37，scratchpad 的 spike 專案只驗證了 Kotlin 編譯本身，沒有觸發這個 Android 應用模組層級的檢查；`targetSdk` 不受影響，維持 36）：

```kotlin
plugins {
    id("com.android.application") version "9.3.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

android {
    namespace = "com.hintvault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hintvault"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 容器與加密，完全不修改。dom 模組改為 vendor 進 src/main/java，
    // 因此這裡不能依賴 KeePassJava2-dom，否則 dex 會有重複類別。
    implementation("org.linguafranca.pwdb:KeePassJava2-kdbx:2.2.4") {
        exclude(group = "ch.qos.logback")
        exclude(group = "junit")
    }

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

- [ ] **Step 2: 在 Manifest 註冊 `MainActivity`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="Hint Vault"
        android:allowBackup="false">
        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: 寫 `MainActivity`（含 `FLAG_SECURE`）**

```kotlin
package com.hintvault.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.fragment.app.FragmentActivity

/**
 * 用 [FragmentActivity] 而非普通的 [androidx.activity.ComponentActivity]：
 * `BiometricPrompt` 的建構子要求 FragmentActivity。它仍然是
 * androidx.activity.ComponentActivity 的子類，setContent {} 一樣能用。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 規格要求：畫面內容含密碼提示，不可被系統截圖或在「最近使用」
        // 清單裡露出縮圖。必須在 setContent 之前設，否則第一幀會漏一下。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme {
                Surface {
                    // Task 16 會換成 HintVaultApp(viewModel) 真正的導覽圖。
                    Text("Hint Vault")
                }
            }
        }
    }
}
```

- [ ] **Step 4: 寫冒煙測試**

```kotlin
package com.hintvault.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsPlaceholder() {
        composeRule.onNodeWithText("Hint Vault").assertExists()
    }
}
```

- [ ] **Step 5: 真機/模擬器上執行，確認通過**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.hintvault.ui.MainActivitySmokeTest"`
Expected: PASS。若 Compose Compiler plugin 版本或 BOM 版本與環境不相容，編譯期就會直接失敗——這是 Task 1 存在的目的之一：儘早暴露這類基礎設施風險，而不是留到後面畫面一堆時才發現。

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml \
    app/src/main/java/com/hintvault/ui/MainActivity.kt \
    app/src/androidTest/java/com/hintvault/ui/MainActivitySmokeTest.kt
git commit -m "feat(ui): 建立 Compose 骨架與 MainActivity"
```

---

### Task 2: `VaultSession` 改為持有 `Vault`

核心引擎完成後，`VaultSession` 持有的抽象 `ByteArray`「vault key」不再對應任何實際存在的東西（規格 §2 的安全政策變更後，真正有用的是解密好的 `Vault` 物件本身）。這個調整**沒有**正式呼叫端（核心引擎最終審查已確認），但已有兩個既有測試檔案跟著舊 API 走，必須一起改。

**Files:**
- Modify: `app/src/main/java/com/hintvault/session/VaultSession.kt`
- Modify: `app/src/test/java/com/hintvault/session/VaultSessionTest.kt`
- Modify: `app/src/androidTest/java/com/hintvault/EndToEndTest.kt`（第 6 步「session 生命週期」用了舊的 `unlock(ByteArray)`）

**Interfaces:**
- Produces: `VaultSession.unlock(vault: Vault)`、`VaultSession.withVault(block: (Vault) -> R): R`、`VaultSession.lock()`（語意不變：清掉持有的參照，回到 `LockState.Locked`）。
- Consumes: `com.hintvault.vault.Vault`（已存在，簽章不變）。

- [ ] **Step 1: 把 `VaultSessionTest.kt` 整份改寫成針對新 API**

`Vault` 沒有 `equals()`（一般 class，非 data class），所以用 `===`（參照相等）驗證「解鎖後拿到的就是同一個物件」，而不是比對內容。用 `KdfParams.FLOOR` 建構測試用 `Vault`，比 `DEFAULT` 快。

```kotlin
package com.hintvault.session

import com.hintvault.crypto.KdfParams
import com.hintvault.vault.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionTest {

    private fun vault() = Vault.create(KdfParams.FLOOR)

    @Test
    fun startsLocked() {
        assertEquals(LockState.Locked, VaultSession().state.value)
    }

    @Test
    fun unlockMovesToUnlocked() {
        val session = VaultSession()
        session.unlock(vault())
        assertEquals(LockState.Unlocked, session.state.value)
    }

    @Test
    fun withVaultExposesTheSameVaultInstance() {
        val session = VaultSession()
        val v = vault()
        session.unlock(v)

        val seen = session.withVault { it }
        assertSame(v, seen)
    }

    @Test
    fun lockMovesBackToLocked() {
        val session = VaultSession()
        session.unlock(vault())
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }

    @Test
    fun withVaultThrowsWhenLocked() {
        var threw = false
        try {
            VaultSession().withVault { it }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun withVaultThrowsAfterLock() {
        val session = VaultSession()
        session.unlock(vault())
        session.lock()

        var threw = false
        try {
            session.withVault { it }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun unlockingTwiceReplacesThePreviousVault() {
        val session = VaultSession()
        session.unlock(vault())
        val second = vault()
        session.unlock(second)

        assertSame(second, session.withVault { it })
    }

    @Test
    fun lockIsIdempotent() {
        val session = VaultSession()
        session.unlock(vault())
        session.lock()
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }
}
```

- [ ] **Step 2: 執行測試，確認因為簽章不符而編譯失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.session.VaultSessionTest"`
Expected: 編譯錯誤（`unlock(Vault)`/`withVault` 尚不存在於 `VaultSession`）。這就是這一步的「紅燈」——強型別語言下，簽章不符的編譯失敗與執行期斷言失敗一樣算數。

- [ ] **Step 3: 改寫 `VaultSession.kt`**

```kotlin
package com.hintvault.session

import com.hintvault.vault.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LockState {
    data object Locked : LockState
    data object Unlocked : LockState
}

/**
 * 記憶體中已解密 [Vault] 的生命週期。
 *
 * 原本持有一把抽象的 vault key（`ByteArray`），規格 §2 的安全政策變更後
 * 不再對應任何實際存在的東西——真正敏感、真正有用的是解密好的 [Vault] 物件
 * 本身。[Vault] 不是 `ByteArray`，沒有 `wipe()` 可呼叫；[lock] 因此改為
 * **捨棄參照讓 GC 回收**，而不是像舊版那樣位元覆寫。這是刻意的行為差異，
 * 不是遺漏。
 *
 * **僅限單一執行緒（主執行緒）呼叫。** 內部沒有任何同步機制——[vault]
 * 是普通的 `var`。若另一執行緒當下正在 [withVault] 的區塊內使用，[lock]
 * 會在它背後把參照清成 null，呼叫端不會收到任何訊號。之後任何要接上背景
 * 計時器（例如 `AutoLock.tick()`）觸發 [lock] 的地方，都必須先切回主執行緒
 * 再呼叫，不能直接從背景執行緒呼叫。
 */
class VaultSession {

    private val _state = MutableStateFlow<LockState>(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var vault: Vault? = null

    fun unlock(vault: Vault) {
        this.vault = vault
        _state.value = LockState.Unlocked
    }

    /**
     * 在已解鎖狀態下取用 [Vault]。刻意不提供 getter，理由與舊版相同：
     * 避免呼叫端把參照複製出去後我們失去對它生命週期的掌握。
     */
    fun <R> withVault(block: (Vault) -> R): R {
        val v = vault ?: throw IllegalStateException("保險庫已上鎖")
        return block(v)
    }

    fun lock() {
        vault = null
        _state.value = LockState.Locked
    }
}
```

- [ ] **Step 4: 執行測試，確認全部通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.session.VaultSessionTest"`
Expected: PASS（7 個測試）

- [ ] **Step 5: 修正 `EndToEndTest.kt` 第 6 步，跟上新 API**

把檔案裡「6. session 生命週期」那一段換成：

```kotlin
        // 6. session 生命週期
        val session = VaultSession()
        session.unlock(reopened)
        assertEquals(LockState.Unlocked, session.state.value)
        assertTrue(session.withVault { it.entries() }.isNotEmpty())
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
```

（原本驗證「上鎖後陣列被歸零」的 `assertArrayEquals(ByteArray(32), key)` 那行連同 `key` 變數一起刪掉——`Vault` 沒有這個性質可驗證，這是 Step 3 的 KDoc 已經記錄的刻意行為差異。）

- [ ] **Step 6: 確認整個模組（含 androidTest 原始碼集）仍能編譯**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL。這一步只驗證編譯，不需要真機——`EndToEndTest.kt` 本身仍待 Task 18 之後才會整批在真機上重跑。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hintvault/session/VaultSession.kt \
    app/src/test/java/com/hintvault/session/VaultSessionTest.kt \
    app/src/androidTest/java/com/hintvault/EndToEndTest.kt
git commit -m "refactor(session): VaultSession 改為持有 Vault 而非抽象 vault key"
```

---

### Task 3: `VaultFile.create()` — 建立新資料庫檔案

核心引擎最終審查標記的缺口之一：`SafVaultFile` 只能找既有檔案，沒有辦法建立新的。

**Files:**
- Modify: `app/src/main/java/com/hintvault/storage/VaultFile.kt`
- Modify: `app/src/main/java/com/hintvault/storage/SafVaultFile.kt`
- Modify: `app/src/test/java/com/hintvault/storage/VaultRepositoryTest.kt`（`FakeVaultFile` 需要實作新方法才能編譯）
- Modify: `app/src/androidTest/java/com/hintvault/storage/SafVaultFileTest.kt`

**Interfaces:**
- Produces: `VaultFile.create(bytes: ByteArray)` — 在目前的目錄與檔名建立新檔案；已存在時拋 `kotlin.io.FileAlreadyExistsException`（stdlib 內建型別，`kotlin.io` 屬於預設匯入，不需額外 import）。

`VaultFile` 目前只有 `SafVaultFile` 一個正式實作、`VaultRepositoryTest.FakeVaultFile` 一個測試假實作，兩者都要同時補上這個方法整個模組才編得過——這種「介面 + 全部實作者一起改」的情況沒有乾淨的單檔案紅燈可言，因此本 Task 採「先把三個檔案一起改到能編譯，再補新測試驗證行為」的順序，而非逐檔案紅綠。

- [ ] **Step 1: 幫 `VaultFile` 介面加上 `create()`**

```kotlin
interface VaultFile {
    val displayName: String
    fun read(): ByteArray
    fun write(bytes: ByteArray)
    fun lastModified(): Long
    fun writeSibling(suffix: String, bytes: ByteArray): String

    /**
     * 在目前的目錄與檔名建立新檔案。
     *
     * 實作**必須**在檔案已存在時拋出 [kotlin.io.FileAlreadyExistsException]，
     * 不可覆蓋——理由與 [writeSibling] 的「絕不覆蓋」合約相同。
     */
    fun create(bytes: ByteArray)
}
```

- [ ] **Step 2: 幫 `SafVaultFile` 補上實作**

在 `SafVaultFile.kt` 裡加一個方法（放在 `writeSibling` 之後），並在檔案頂端加 `import java.io.File`：

```kotlin
    /**
     * 直接重用 [writeSibling] 已驗證過的「絕不覆蓋既有檔案」邏輯所在的同一個
     * MIME type 常數，但語意不同：這裡撞名是**錯誤**而非該自動避開的情況，
     * 因此不呼叫 [uniqueNameFor]，而是直接檢查、直接拋例外。
     */
    override fun create(bytes: ByteArray) {
        if (directory.findFile(fileName) != null) {
            throw FileAlreadyExistsException(File(fileName))
        }
        val created = directory.createFile(SIBLING_MIME_TYPE, fileName)
            ?: throw FileNotFoundException("無法建立 $fileName")
        context.contentResolver.openOutputStream(created.uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("無法寫入 $fileName")
    }
```

（`SIBLING_MIME_TYPE` 目前在 `private companion object` 裡，`create()` 是同一個類別的成員函式，看得到。）

- [ ] **Step 3: 幫 `VaultRepositoryTest.kt` 的 `FakeVaultFile` 補上實作**

這一步純粹是讓既有測試檔案跟著介面變動繼續編譯，不改變 `VaultRepositoryTest` 既有測試的任何行為（`VaultRepository` 本身不呼叫 `create()`）。把 `FakeVaultFile` 換成：

```kotlin
    private class FakeVaultFile(
        override val displayName: String = "vault.kdbx",
        var content: ByteArray = ByteArray(0),
        var mtime: Long = 1000L,
        var exists: Boolean = true
    ) : VaultFile {
        val siblings = mutableMapOf<String, ByteArray>()
        var writeShouldFail = false

        override fun read() = content
        override fun lastModified() = mtime
        override fun write(bytes: ByteArray) {
            if (writeShouldFail) throw java.io.IOException("simulated write failure")
            content = bytes
            mtime += 1
        }
        override fun writeSibling(suffix: String, bytes: ByteArray): String {
            val name = "$displayName$suffix"
            siblings[name] = bytes
            return name
        }
        override fun create(bytes: ByteArray) {
            if (exists) throw FileAlreadyExistsException(java.io.File(displayName))
            content = bytes
            exists = true
        }

        /** 模擬同步工具在背後換掉了檔案。 */
        fun externallyReplacedWith(bytes: ByteArray) {
            content = bytes
            mtime += 500
        }
    }
```

- [ ] **Step 4: 確認整個模組編譯成功**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 在 `SafVaultFileTest.kt` 補新測試，驗證 `create()` 的真實行為**

在既有 `@Test` 後面加：

```kotlin
    @Test
    fun createWritesNewFileWithGivenContent() {
        val name = "brand-new.kdbx"
        SafVaultFile(context, directory, name).create("hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), File(dir, name).readBytes())
    }

    @Test
    fun createThrowsWhenFileAlreadyExists() {
        var threw = false
        try {
            subject().create("overwrite-attempt".toByteArray())
        } catch (_: FileAlreadyExistsException) {
            threw = true
        }
        assertTrue("已存在的檔案不可被 create() 覆蓋", threw)
        // 原始內容必須完好無損
        assertArrayEquals("v1".toByteArray(), File(dir, "saf-test.kdbx").readBytes())
    }
```

（`subject()` 沿用檔案已有的 helper，指向 `setUp()` 裡建立好的 `saf-test.kdbx`。）

- [ ] **Step 6: 真機/模擬器上執行**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.hintvault.storage.SafVaultFileTest"`
Expected: PASS（含新增的 2 個測試）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hintvault/storage/VaultFile.kt \
    app/src/main/java/com/hintvault/storage/SafVaultFile.kt \
    app/src/test/java/com/hintvault/storage/VaultRepositoryTest.kt \
    app/src/androidTest/java/com/hintvault/storage/SafVaultFileTest.kt
git commit -m "feat(storage): VaultFile.create() — 建立新資料庫檔案"
```

---

### Task 4: `VaultHealthCheck` — 備份還原偵測

核心引擎最終審查標記的缺口之二：`BackupStore.get()` 目前有寫入路徑但零呼叫端。規格 §4.8：開 app 時自動偵測資料庫檔案是否存在、檔頭是否能被基本辨識（不需要密碼——KDBX 檔頭本身未加密，是固定的 8-byte 簽章）。

**Files:**
- Create: `app/src/main/java/com/hintvault/storage/VaultHealthCheck.kt`
- Test: `app/src/test/java/com/hintvault/storage/VaultHealthCheckTest.kt`

**Interfaces:**
- Consumes: `VaultFile`（`read()`/`write()`/`create()`）、`BackupStore`（`get()`）——皆已存在。
- Produces: `VaultHealthCheck(file, backups)`、`VaultHealthCheck.Result`（`Healthy` / `RecoverableFromBackup(backupBytes)` / `Unrecoverable`）、`check(): Result`、`restoreFromBackup(backupBytes: ByteArray)`。Task 6 的 `VaultViewModel` 會是第一個正式呼叫端。

- [ ] **Step 1: 寫測試（先寫，此時 `VaultHealthCheck` 還不存在，預期編譯失敗）**

```kotlin
package com.hintvault.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class VaultHealthCheckTest {

    private val kdbxHeader = byteArrayOf(
        0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
        0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()
    )

    private class FakeFile(
        private var exists: Boolean,
        private var content: ByteArray = ByteArray(0)
    ) : VaultFile {
        override val displayName = "vault.kdbx"
        var written: ByteArray? = null
        var created: ByteArray? = null

        override fun read(): ByteArray {
            if (!exists) throw FileNotFoundException("missing")
            return content
        }
        override fun write(bytes: ByteArray) {
            if (!exists) throw FileNotFoundException("missing")
            written = bytes
            content = bytes
        }
        override fun lastModified() = 1000L
        override fun writeSibling(suffix: String, bytes: ByteArray) = displayName + suffix
        override fun create(bytes: ByteArray) {
            created = bytes
            content = bytes
            exists = true
        }
    }

    private class FakeBackupStore(private val backup: ByteArray?) : BackupStore {
        override fun put(bytes: ByteArray) {}
        override fun get() = backup
    }

    @Test
    fun healthyFileReturnsHealthy() {
        val file = FakeFile(exists = true, content = kdbxHeader + "rest".toByteArray())
        val result = VaultHealthCheck(file, FakeBackupStore(null)).check()
        assertTrue(result is VaultHealthCheck.Result.Healthy)
    }

    @Test
    fun missingFileWithNoBackupReturnsUnrecoverable() {
        val file = FakeFile(exists = false)
        val result = VaultHealthCheck(file, FakeBackupStore(null)).check()
        assertTrue(result is VaultHealthCheck.Result.Unrecoverable)
    }

    @Test
    fun missingFileWithValidBackupReturnsRecoverableFromBackup() {
        val backup = kdbxHeader + "backup-content".toByteArray()
        val file = FakeFile(exists = false)
        val result = VaultHealthCheck(file, FakeBackupStore(backup)).check()

        assertTrue(result is VaultHealthCheck.Result.RecoverableFromBackup)
        assertArrayEquals(backup, (result as VaultHealthCheck.Result.RecoverableFromBackup).backupBytes)
    }

    @Test
    fun corruptHeaderWithValidBackupReturnsRecoverableFromBackup() {
        val backup = kdbxHeader + "backup-content".toByteArray()
        val file = FakeFile(exists = true, content = "not-a-kdbx-file".toByteArray())
        val result = VaultHealthCheck(file, FakeBackupStore(backup)).check()

        assertTrue(result is VaultHealthCheck.Result.RecoverableFromBackup)
    }

    @Test
    fun corruptHeaderWithNoUsableBackupReturnsUnrecoverable() {
        val file = FakeFile(exists = true, content = "not-a-kdbx-file".toByteArray())
        // 備份本身也壞掉的情況：不能假裝可以還原
        val result = VaultHealthCheck(file, FakeBackupStore("also-corrupt".toByteArray())).check()

        assertTrue(result is VaultHealthCheck.Result.Unrecoverable)
    }

    @Test
    fun restoreFromBackupWritesToExistingFile() {
        val file = FakeFile(exists = true, content = "corrupt".toByteArray())
        val backup = kdbxHeader + "restored".toByteArray()

        VaultHealthCheck(file, FakeBackupStore(backup)).restoreFromBackup(backup)

        assertArrayEquals(backup, file.written)
    }

    @Test
    fun restoreFromBackupCreatesFileWhenMissing() {
        val file = FakeFile(exists = false)
        val backup = kdbxHeader + "restored".toByteArray()

        VaultHealthCheck(file, FakeBackupStore(backup)).restoreFromBackup(backup)

        assertArrayEquals(backup, file.created)
    }
}
```

- [ ] **Step 2: 執行測試，確認因為型別不存在而編譯失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.storage.VaultHealthCheckTest"`
Expected: 編譯失敗（`VaultHealthCheck` 不存在）

- [ ] **Step 3: 寫 `VaultHealthCheck`**

```kotlin
package com.hintvault.storage

/**
 * 開 app 時，在要求密碼之前先確認資料庫檔案本身是否健全。
 *
 * 只檢查 KDBX 檔頭簽章（前 8 bytes），不做完整格式解析——完整解析需要密碼
 * 才能驗證後續內容，那是 `Vault.open()` 的責任（見 `UnsupportedVaultFormatException`）。
 * 這裡只回答一個更基本的問題：這個檔案存不存在、開得起來、前 8 bytes 像不像 KDBX。
 */
class VaultHealthCheck(
    private val file: VaultFile,
    private val backups: BackupStore
) {

    sealed interface Result {
        data object Healthy : Result
        data class RecoverableFromBackup(val backupBytes: ByteArray) : Result
        data object Unrecoverable : Result
    }

    fun check(): Result {
        val bytes = try {
            file.read()
        } catch (_: Exception) {
            return recoverableOrUnrecoverable()
        }
        return if (bytes.hasKdbxSignature()) Result.Healthy else recoverableOrUnrecoverable()
    }

    /**
     * 使用者確認還原後呼叫。先試著蓋掉既有檔案；若檔案根本不存在（`write()`
     * 會擲例外），退回 `create()`——重用 Task 3 剛加的「絕不覆蓋既有檔案」
     * 建檔邏輯，這裡走到這一步時確實不存在既有檔案，語意上不衝突。
     */
    fun restoreFromBackup(backupBytes: ByteArray) {
        try {
            file.write(backupBytes)
        } catch (_: Exception) {
            file.create(backupBytes)
        }
    }

    private fun recoverableOrUnrecoverable(): Result {
        val backup = backups.get()
        return if (backup != null && backup.hasKdbxSignature()) {
            Result.RecoverableFromBackup(backup)
        } else {
            Result.Unrecoverable
        }
    }

    private fun ByteArray.hasKdbxSignature(): Boolean =
        size >= KDBX_SIGNATURE.size && KDBX_SIGNATURE.indices.all { this[it] == KDBX_SIGNATURE[it] }

    private companion object {
        val KDBX_SIGNATURE = byteArrayOf(
            0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
            0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()
        )
    }
}
```

- [ ] **Step 4: 執行測試，確認全部通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.storage.VaultHealthCheckTest"`
Expected: PASS（7 個測試）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/storage/VaultHealthCheck.kt \
    app/src/test/java/com/hintvault/storage/VaultHealthCheckTest.kt
git commit -m "feat(storage): VaultHealthCheck — 備份還原偵測"
```

---

### Task 5: `ByteArray.toUtf8CharArray()` — 生體辨識解鎖的反向轉換

生體辨識解鎖包裝的是主密碼的 UTF-8 位元組（規格 §2）；`BiometricUnlock.unlock()` 回傳 `ByteArray?`，但 `Vault.open()` 要的是 `CharArray`。兩者中間需要一個不經過 `String` 的解碼函式，對稱於既有的 `CharArray.toUtf8Bytes()`。

**Files:**
- Modify: `app/src/main/java/com/hintvault/crypto/Secrets.kt`
- Modify: `app/src/test/java/com/hintvault/crypto/SecretsTest.kt`

**Interfaces:**
- Produces: `ByteArray.toUtf8CharArray(): CharArray`

- [ ] **Step 1: 在 `SecretsTest.kt` 加測試**

```kotlin
    @Test
    fun toUtf8CharArrayDecodesMultibyteCorrectly() {
        val bytes = "提示".toByteArray(Charsets.UTF_8)
        assertArrayEquals("提示".toCharArray(), bytes.toUtf8CharArray())
    }

    @Test
    fun toUtf8CharArrayRoundTripsWithToUtf8Bytes() {
        val original = "提示：貓咪的生日 🔐".toCharArray()
        val roundTripped = original.toUtf8Bytes().toUtf8CharArray()
        assertArrayEquals(original, roundTripped)
    }

    @Test
    fun toUtf8CharArrayHandlesLongCjkInputWithoutTruncation() {
        val text = "提示".repeat(64)
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertArrayEquals(text.toCharArray(), bytes.toUtf8CharArray())
    }
```

- [ ] **Step 2: 執行測試，確認因為函式不存在而編譯失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.SecretsTest"`
Expected: 編譯失敗（`toUtf8CharArray` 不存在）

- [ ] **Step 3: 在 `Secrets.kt` 加實作**

加在 `toUtf8Bytes()` 之後：

```kotlin
/**
 * UTF-8 解碼且不經過 String，理由與 [toUtf8Bytes] 相同：String 不可變、
 * 無法歸零。
 *
 * 預先配置 `size` 個 char 的緩衝區：UTF-8 每個 byte 最多解出 1 個 char
 * （單一 ASCII byte 對應 1 char；多 byte 序列則對應更少 char），因此輸出
 * 的 char 數量必然不超過輸入的 byte 數量，`size` 保證足夠、不會觸發緩衝區
 * 擴充。這與 [toUtf8Bytes] 選擇 `size * 3` 是同一個理由的鏡像版本：中途
 * 擴充會讓被丟棄的舊緩衝區裝著明文卻清不掉。
 *
 * `ByteBuffer.wrap(this)` 直接包住呼叫端傳入的陣列，不複製——呼叫端的
 * 陣列本身要不要清空是呼叫端的責任，這裡不動它，對稱於 [toUtf8Bytes]
 * 用 `CharBuffer.wrap(this)` 時同樣不清空呼叫端的 CharArray。
 */
fun ByteArray.toUtf8CharArray(): CharArray {
    val out = CharBuffer.allocate(size)
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    decoder.decode(ByteBuffer.wrap(this), out, true)
    decoder.flush(out)

    val chars = CharArray(out.position())
    out.flip()
    out.get(chars)
    out.array().fill(' ')
    return chars
}
```

- [ ] **Step 4: 執行測試，確認全部通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.SecretsTest"`
Expected: PASS（原本 6 個 + 新增 3 個）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/crypto/Secrets.kt \
    app/src/test/java/com/hintvault/crypto/SecretsTest.kt
git commit -m "feat(crypto): ByteArray.toUtf8CharArray() — 生體辨識解鎖的反向轉換"
```

---

### Task 6: `VaultViewModel` — 首次設定 + 解鎖

這是最大的一塊。先講清楚整個 Task 6-8 共用的設計決策，因為規格文件本身沒有完全講清楚，是這一輪實作時才浮現的缺口：

**主密碼快取的存放位置**：`Vault.serialize(masterPassword: CharArray)` 每次存檔都需要主密碼——KeePassJava2 沒有「已經解鎖過、之後存檔不用再給密碼」這種介面。若不快取，使用者每編輯一筆就要重新輸入主密碼，不符合規格 §4.4「解鎖 → 清單 → 編輯 → 存檔」的隱含流程。解法：`VaultViewModel`（不是 `VaultSession`——規格 §3 明確把 `VaultSession` 定義為只管 `Vault`）自己持有一個 `masterPasswordCache: CharArray?` 私有欄位，在解鎖成功時寫入（不論是手動輸入還是生體辨識解出的），`lock()` 與 `onCleared()` 時清空。這與規格 §2 已經接受的風險同一等級——密碼在解鎖期間本來就會以明文存在記憶體中，這裡只是把「多久存在」從單次操作延長到整個 unlocked session，沒有引入新的風險類別。

**可測試性邊界**：`VaultViewModel` 不持有 `Context`——所有需要它的操作（挑資料夾、建立 `SafVaultFile`）都把 `Context`當**參數**傳入對應函式，而非建構子欄位。這讓 `VaultViewModel` 本身可以用無參數建構子在 JVM 測試裡直接 `new`。但 SAF 相關的協調函式（`onFolderChosen`/`checkExistingSetup`）本身仍然依賴 `DocumentFile.fromTreeUri()`/`ContentResolver`，這兩個在純 JVM 測試環境下用不了（本專案沒有 Robolectric）——因此本 Task 額外開一個 `internal` 測試用入口 `setRepositoryForTest()`，讓測試繞過 SAF、直接注入已就緒的 `VaultRepository`，藉此測到解鎖/存檔邏輯本身。SAF 協調函式的真實行為留給 Task 18 的真機端到端測試與 `docs/manual-verification.md`。

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/VaultUiState.kt`
- Create: `app/src/main/java/com/hintvault/ui/VaultLocationPrefs.kt`
- Create: `app/src/main/java/com/hintvault/ui/VaultViewModel.kt`
- Create: `app/src/test/java/com/hintvault/ui/MainDispatcherRule.kt`（不是 `main`：它依賴 `org.junit.rules.TestWatcher`/`kotlinx.coroutines.test.*`，兩者都只在 `testImplementation` 才有，放進 `main` 會編不過）
- Test: `app/src/test/java/com/hintvault/ui/VaultViewModelTest.kt`

**Interfaces:**
- Consumes: `VaultSession`、`VaultRepository`、`VaultHealthCheck`、`SafVaultFile`、`FileBackupStore`、`Vault`、`WrongPasswordException`、`UnsupportedVaultFormatException`、`crypto.BiometricUnlock`、`crypto.KeyPermanentlyInvalidException`、`crypto.WrappedKeyStore`、`crypto.KeystoreKeys`、`crypto.Secrets`（`toUtf8Bytes`/`toUtf8CharArray`/`wipe`）。
- Produces（本 Task 完成的部分）：
  - `VaultUiState(setup: SetupState, lockState: LockState, unlockError: UnlockError?, entries: List<Entry>, biometricEnabled: Boolean, autoLockPolicy: AutoLockPolicy, saveMessage: SaveMessage?)`
  - `SetupState`（`CheckingFolder` / `NeedsFolderPicker` / `NeedsFileChoice(candidateNames)` / `NeedsNewVaultPassword` / `NeedsBackupDecision(backupBytes)` / `Ready`）
  - `UnlockError`（`WRONG_PASSWORD` / `UNSUPPORTED_FORMAT` / `FILE_MISSING` / `BIOMETRIC_INVALIDATED`）
  - `VaultViewModel.uiState: StateFlow<VaultUiState>`
  - `VaultViewModel.checkExistingSetup(context: Context)`、`onFolderChosen(context: Context, treeUri: Uri)`、`onFileChosen(context: Context, fileName: String)`、`onNewVaultPasswordChosen(context: Context, masterPassword: CharArray)`、`resolveBackupDecision(accept: Boolean)`、`unlockWithPassword(masterPassword: CharArray)`、`unlockWithBiometric(biometric: BiometricUnlock)`、`lock()`
  - Task 7 會繼續往這個檔案加 `saveEntry`/`deleteEntry`；Task 8 會加設定相關函式。`SaveMessage` 型別放在這裡先定義，因為 `VaultUiState` 需要它，但實際產生它的邏輯在 Task 7。

- [ ] **Step 1: 定義 UI 狀態型別**

```kotlin
package com.hintvault.ui

import com.hintvault.session.LockState
import com.hintvault.session.AutoLockPolicy
import com.hintvault.vault.Entry

data class VaultUiState(
    val setup: SetupState = SetupState.CheckingFolder,
    val lockState: LockState = LockState.Locked,
    val unlockError: UnlockError? = null,
    /**
     * 解鎖中（跑 Argon2 或等生體辨識）的畫面用來顯示進度指示，避免看起來像卡住。
     * 刻意放在這裡由 ViewModel 明確管理每個結束路徑，而不是讓畫面自己用
     * `LaunchedEffect(unlockError)` 之類的方式從其他狀態的變化去推——那種做法在
     * 「使用者取消生體辨識」（unlockError 不變）或「連續兩次同一種錯誤」
     * （enum 值沒變，Compose 不會重新觸發 effect）這兩種情況下都會讓指示器卡住轉不停。
     */
    val isUnlocking: Boolean = false,
    val entries: List<Entry> = emptyList(),
    val biometricEnabled: Boolean = false,
    val autoLockPolicy: AutoLockPolicy = AutoLockPolicy(),
    val saveMessage: SaveMessage? = null
)

enum class UnlockError { WRONG_PASSWORD, UNSUPPORTED_FORMAT, FILE_MISSING, BIOMETRIC_INVALIDATED }

sealed interface SetupState {
    data object CheckingFolder : SetupState
    data object NeedsFolderPicker : SetupState
    data class NeedsFileChoice(val candidateNames: List<String>) : SetupState
    data object NeedsNewVaultPassword : SetupState
    data class NeedsBackupDecision(val backupBytes: ByteArray) : SetupState
    data object Ready : SetupState
}

sealed interface SaveMessage {
    data object Saved : SaveMessage
    data class Conflict(val conflictFileName: String) : SaveMessage
}
```

- [ ] **Step 2: 記住使用者選過的資料夾/檔名**

規格 §4 的流程圖預設「已選過資料夾 → 直接進【解鎖】」，這代表選擇結果必須跨 app 重啟持久化——`VaultUriPermissions.isStillGranted()` 只驗證 SAF 授權是否還有效，不記得「是哪一個 URI」，這是本輪新補的一塊：

```kotlin
package com.hintvault.ui

import android.content.Context
import android.net.Uri

/** 記住使用者選過的資料夾與檔名，讓下次啟動不必重新選一次。 */
class VaultLocationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("vault_location", Context.MODE_PRIVATE)

    data class Location(val treeUri: Uri, val fileName: String)

    fun get(): Location? {
        val uriString = prefs.getString(KEY_URI, null) ?: return null
        val fileName = prefs.getString(KEY_FILE, null) ?: return null
        return Location(Uri.parse(uriString), fileName)
    }

    fun put(treeUri: Uri, fileName: String) {
        prefs.edit().putString(KEY_URI, treeUri.toString()).putString(KEY_FILE, fileName).apply()
    }

    private companion object {
        const val KEY_URI = "tree_uri"
        const val KEY_FILE = "file_name"
    }
}
```

- [ ] **Step 3: 測試用的 `MainDispatcherRule`**

`viewModelScope` 內部用 `Dispatchers.Main`，JVM 單元測試環境沒有 Android 的 Main looper，需要 `kotlinx-coroutines-test` 頂替一個。這個 rule 會被 Task 6/7/8 的測試共用：

```kotlin
package com.hintvault.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

- [ ] **Step 4: 寫失敗測試（解鎖邏輯，繞過 SAF）**

```kotlin
package com.hintvault.ui

import com.hintvault.crypto.KdfParams
import com.hintvault.session.LockState
import com.hintvault.storage.FileStamp
import com.hintvault.storage.SaveResult
import com.hintvault.storage.VaultFile
import com.hintvault.storage.VaultRepository
import com.hintvault.vault.Vault
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VaultViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fastKdf = KdfParams(memoryMib = 8, iterations = 2, parallelism = 2)
    private fun pw(text: String = "correct horse") = text.toCharArray()

    /** JVM 上可用的最小假實作，繞過真正的 SAF。 */
    private class FakeVaultFile(var content: ByteArray) : VaultFile {
        override val displayName = "vault.kdbx"
        override fun read() = content
        override fun write(bytes: ByteArray) { content = bytes }
        override fun lastModified() = 1000L
        override fun writeSibling(suffix: String, bytes: ByteArray) = displayName + suffix
        override fun create(bytes: ByteArray) { content = bytes }
    }

    private class FakeBackupStore : com.hintvault.storage.BackupStore {
        var stored: ByteArray? = null
        override fun put(bytes: ByteArray) { stored = bytes }
        override fun get() = stored
    }

    private fun readyViewModel(bytes: ByteArray): VaultViewModel {
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val stamp = FileStamp.of(bytes, file.lastModified())
        return VaultViewModel().apply {
            setRepositoryForTest(repo, stamp)
            // MainDispatcherRule 只替換 Dispatchers.Main；attemptUnlock() 內部另外
            // withContext(Dispatchers.Default) 切到真正的執行緒池做 KDF 運算，
            // 那個切換不受 UnconfinedTestDispatcher 控制，測試斷言會在協程真正
            // 跑完之前就跑到下一行。用同一個 seam 換成 Dispatchers.Unconfined，
            // 讓整條協程留在呼叫端執行緒上同步跑完。
            setUnlockDispatcherForTest(kotlinx.coroutines.Dispatchers.Unconfined)
        }
    }

    @Test
    fun unlockWithCorrectPasswordMovesToReadyAndUnlocked() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)

        vm.unlockWithPassword(pw())

        assertEquals(SetupState.Ready, vm.uiState.value.setup)
        assertEquals(LockState.Unlocked, vm.uiState.value.lockState)
        assertNull(vm.uiState.value.unlockError)
    }

    @Test
    fun unlockWithWrongPasswordSetsWrongPasswordError() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw("right"))
        val vm = readyViewModel(bytes)

        vm.unlockWithPassword(pw("wrong"))

        assertEquals(UnlockError.WRONG_PASSWORD, vm.uiState.value.unlockError)
        assertEquals(LockState.Locked, vm.uiState.value.lockState)
    }

    @Test
    fun unlockWithGarbageBytesSetsWrongPasswordError() = runTest {
        // Vault.open() 對完全不是 KDBX 的輸入，實際丟出的正是 WrongPasswordException——
        // 見 Vault.kt 的 WrongPasswordException KDoc：「密碼錯誤，或檔案損毀 / 非 KDBX」。
        // 不是含糊帶過的猜測，是函式庫的真實 catch-all 行為。
        val vm = readyViewModel("not a kdbx file at all".toByteArray())

        vm.unlockWithPassword(pw())

        assertEquals(UnlockError.WRONG_PASSWORD, vm.uiState.value.unlockError)
    }

    @Test
    fun unlockWipesThePasswordArrayPassedIn() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        val password = pw()

        vm.unlockWithPassword(password)

        assertEquals(' ', password[0])
    }

    @Test
    fun lockClearsEntriesAndReturnsToLocked() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        vm.lock()

        assertEquals(LockState.Locked, vm.uiState.value.lockState)
        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun resolveBackupDecisionAcceptingRestoresAndMovesToReady() = runTest {
        val goodBytes = Vault.create(fastKdf).serialize(pw())
        val file = FakeVaultFile(ByteArray(0))
        val backups = FakeBackupStore().apply { stored = goodBytes }
        val repo = VaultRepository(file, backups)
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(ByteArray(0), 0L))
            setFileAndBackupsForTest(file, backups)
            forceSetupStateForTest(SetupState.NeedsBackupDecision(goodBytes))
        }

        vm.resolveBackupDecision(accept = true)

        assertEquals(SetupState.Ready, vm.uiState.value.setup)
        assertEquals(goodBytes.toList(), file.content.toList())
    }

    @Test
    fun resolveBackupDecisionDecliningStillMovesToReadyWithoutRestoring() = runTest {
        val file = FakeVaultFile(ByteArray(0))
        val backups = FakeBackupStore().apply { stored = "backup".toByteArray() }
        val repo = VaultRepository(file, backups)
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(ByteArray(0), 0L))
            setFileAndBackupsForTest(file, backups)
            forceSetupStateForTest(SetupState.NeedsBackupDecision("backup".toByteArray()))
        }

        vm.resolveBackupDecision(accept = false)

        assertEquals(SetupState.Ready, vm.uiState.value.setup)
        assertTrue(file.content.isEmpty())
    }
}
```

（`unlockWithGarbageBytesSetsWrongPasswordError`：`Vault.open()` 對「完全不是 KDBX」的輸入，實際丟出的就是 `WrongPasswordException`——`Vault.kt` 的 catch-all 只把「Unknown Cipher UUID」這個特定情況分流到 `UnsupportedVaultFormatException`，其餘一律包成 `WrongPasswordException`。斷言應直接比對這個結果，不需要（也不應該）用排除法含糊帶過。）

- [ ] **Step 5: 執行測試，確認因為 `VaultViewModel` 不存在而編譯失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.ui.VaultViewModelTest"`
Expected: 編譯失敗

- [ ] **Step 6: 寫 `VaultViewModel`（本 Task 涵蓋的部分）**

```kotlin
package com.hintvault.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hintvault.crypto.BiometricUnlock
import com.hintvault.crypto.KeyPermanentlyInvalidException
import com.hintvault.crypto.toUtf8CharArray
import com.hintvault.crypto.wipe
import com.hintvault.session.LockState
import com.hintvault.session.VaultSession
import com.hintvault.storage.BackupStore
import com.hintvault.storage.FileBackupStore
import com.hintvault.storage.FileStamp
import com.hintvault.storage.SafVaultFile
import com.hintvault.storage.VaultFile
import com.hintvault.storage.VaultHealthCheck
import com.hintvault.storage.VaultRepository
import com.hintvault.storage.VaultUriPermissions
import com.hintvault.vault.UnsupportedVaultFormatException
import com.hintvault.vault.Vault
import com.hintvault.vault.WrongPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VaultViewModel : ViewModel() {

    private val session = VaultSession()
    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var directory: DocumentFile? = null
    private var file: VaultFile? = null
    private var backups: BackupStore? = null
    private var repository: VaultRepository? = null
    private var loadedStamp: FileStamp? = null
    private var masterPasswordCache: CharArray? = null

    /**
     * `attemptUnlock()` 把 Argon2 運算切到這個 dispatcher 上跑，離開主執行緒。
     * 注入而非寫死 `Dispatchers.Default`，理由與 `AutoLock` 注入 `now: () -> Long`
     * 相同：`kotlinx-coroutines-test` 的 `UnconfinedTestDispatcher` 只替換
     * `Dispatchers.Main`，`Dispatchers.Default` 不受它控制——寫死的話，JVM 測試裡
     * `unlockWithPassword()` 呼叫後協程會被丟到真正的背景執行緒池，下一行斷言
     * 會在協程真正跑完之前就執行，測出間歇性失敗。
     */
    private var unlockDispatcher: CoroutineDispatcher = Dispatchers.Default

    // ---- 首次設定 / 資料夾偵測 ----

    fun checkExistingSetup(context: Context) {
        val biometricEnabled = com.hintvault.crypto.WrappedKeyStore(keyDir(context)).exists()
        _uiState.update { it.copy(biometricEnabled = biometricEnabled) }

        val location = VaultLocationPrefs(context).get()
        if (location == null || !VaultUriPermissions.isStillGranted(context, location.treeUri)) {
            _uiState.update { it.copy(setup = SetupState.NeedsFolderPicker) }
            return
        }
        val dir = DocumentFile.fromTreeUri(context, location.treeUri)
        if (dir == null || dir.findFile(location.fileName) == null) {
            _uiState.update { it.copy(setup = SetupState.NeedsFolderPicker) }
            return
        }
        directory = dir
        selectFile(context, dir, location.fileName)
    }

    fun onFolderChosen(context: Context, treeUri: Uri) {
        VaultUriPermissions.persist(context, treeUri)
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return
        directory = dir

        val candidates = dir.listFiles().mapNotNull { it.name }.filter { it.endsWith(".kdbx") }
        when {
            candidates.isEmpty() ->
                _uiState.update { it.copy(setup = SetupState.NeedsNewVaultPassword) }
            candidates.size == 1 ->
                selectFile(context, dir, candidates.single())
            else ->
                _uiState.update { it.copy(setup = SetupState.NeedsFileChoice(candidates)) }
        }
    }

    fun onFileChosen(context: Context, fileName: String) {
        val dir = directory ?: return
        selectFile(context, dir, fileName)
    }

    fun onNewVaultPasswordChosen(context: Context, masterPassword: CharArray) {
        val dir = directory ?: run { masterPassword.wipe(); return }
        val fileName = "vault.kdbx"
        val newFile = SafVaultFile(context, dir, fileName)
        val vault = Vault.create()
        val bytes = vault.serialize(masterPassword)
        newFile.create(bytes)

        val backupStore = FileBackupStore(File(context.filesDir, "backups"))
        file = newFile
        backups = backupStore
        repository = VaultRepository(newFile, backupStore)
        loadedStamp = FileStamp.of(bytes, newFile.lastModified())
        VaultLocationPrefs(context).put(dir.uri, fileName)

        cacheMasterPassword(masterPassword)
        masterPassword.wipe()
        session.unlock(vault)
        _uiState.update {
            it.copy(setup = SetupState.Ready, lockState = LockState.Unlocked, entries = vault.entries())
        }
    }

    fun resolveBackupDecision(accept: Boolean) {
        val state = uiState.value.setup as? SetupState.NeedsBackupDecision ?: return
        if (accept) {
            val f = file
            val b = backups
            if (f != null && b != null) VaultHealthCheck(f, b).restoreFromBackup(state.backupBytes)
        }
        _uiState.update { it.copy(setup = SetupState.Ready) }
    }

    private fun selectFile(context: Context, dir: DocumentFile, fileName: String) {
        val safFile = SafVaultFile(context, dir, fileName)
        val backupStore = FileBackupStore(File(context.filesDir, "backups"))
        file = safFile
        backups = backupStore
        repository = VaultRepository(safFile, backupStore)
        VaultLocationPrefs(context).put(dir.uri, fileName)

        when (val result = VaultHealthCheck(safFile, backupStore).check()) {
            VaultHealthCheck.Result.Healthy ->
                _uiState.update { it.copy(setup = SetupState.Ready) }
            is VaultHealthCheck.Result.RecoverableFromBackup ->
                _uiState.update { it.copy(setup = SetupState.NeedsBackupDecision(result.backupBytes)) }
            VaultHealthCheck.Result.Unrecoverable ->
                _uiState.update { it.copy(setup = SetupState.Ready, unlockError = UnlockError.FILE_MISSING) }
        }
    }

    private fun keyDir(context: Context) = File(context.filesDir, "keys")

    // ---- 解鎖 ----

    fun unlockWithPassword(masterPassword: CharArray) {
        viewModelScope.launch { attemptUnlock(masterPassword) }
    }

    /**
     * `isUnlocking` 由這裡（以及 [attemptUnlock]）明確管理每一條結束路徑，不讓畫面自己
     * 用 `LaunchedEffect(unlockError)` 之類的方式從別的狀態變化去推——那種做法在
     * 「使用者取消生體辨識」（`unlockError` 不變）與「連續兩次同一種錯誤」
     * （enum 值沒變，Compose 不會重新觸發 effect）這兩種情況下都會讓進度指示器卡住轉不停，
     * 是 Task 10 review 抓到的真實 bug，不是假設性風險。
     */
    fun unlockWithBiometric(biometric: BiometricUnlock) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUnlocking = true, unlockError = null) }
            val passwordBytes = try {
                biometric.unlock()
            } catch (e: KeyPermanentlyInvalidException) {
                _uiState.update { it.copy(biometricEnabled = false, unlockError = UnlockError.BIOMETRIC_INVALIDATED) }
                null
            }
            if (passwordBytes == null) {
                _uiState.update { it.copy(isUnlocking = false) }
                return@launch
            }
            val password = passwordBytes.toUtf8CharArray()
            passwordBytes.wipe()
            attemptUnlock(password)
        }
    }

    private suspend fun attemptUnlock(masterPassword: CharArray) {
        val repo = repository
        if (repo == null) {
            masterPassword.wipe()
            return
        }
        _uiState.update { it.copy(isUnlocking = true) }
        try {
            val loaded = withContext(unlockDispatcher) { repo.load() }
            val vault = withContext(unlockDispatcher) { Vault.open(loaded.bytes, masterPassword) }
            loadedStamp = loaded.stamp
            cacheMasterPassword(masterPassword)
            session.unlock(vault)
            _uiState.update {
                it.copy(
                    setup = SetupState.Ready,
                    lockState = LockState.Unlocked,
                    entries = vault.entries(),
                    unlockError = null,
                    isUnlocking = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WrongPasswordException) {
            _uiState.update { it.copy(unlockError = UnlockError.WRONG_PASSWORD, isUnlocking = false) }
        } catch (e: UnsupportedVaultFormatException) {
            _uiState.update { it.copy(unlockError = UnlockError.UNSUPPORTED_FORMAT, isUnlocking = false) }
        } catch (e: Exception) {
            _uiState.update { it.copy(unlockError = UnlockError.FILE_MISSING, isUnlocking = false) }
        } finally {
            masterPassword.wipe()
        }
    }

    fun lock() {
        session.lock()
        masterPasswordCache?.wipe()
        masterPasswordCache = null
        _uiState.update { it.copy(lockState = LockState.Locked, entries = emptyList(), isUnlocking = false) }
    }

    private fun cacheMasterPassword(masterPassword: CharArray) {
        masterPasswordCache?.wipe()
        masterPasswordCache = masterPassword.copyOf()
    }

    override fun onCleared() {
        masterPasswordCache?.wipe()
        super.onCleared()
    }

    // ---- 測試用入口：繞過 SAF，直接注入已就緒的 repository ----

    internal fun setRepositoryForTest(repository: VaultRepository, stamp: FileStamp) {
        this.repository = repository
        this.loadedStamp = stamp
    }

    internal fun setFileAndBackupsForTest(file: VaultFile, backups: BackupStore) {
        this.file = file
        this.backups = backups
    }

    internal fun forceSetupStateForTest(setup: SetupState) {
        _uiState.update { it.copy(setup = setup) }
    }

    internal fun setUnlockDispatcherForTest(dispatcher: CoroutineDispatcher) {
        unlockDispatcher = dispatcher
    }
}
```

- [ ] **Step 7: 執行測試，確認全部通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.ui.VaultViewModelTest"`
Expected: PASS（7 個測試）

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/VaultUiState.kt \
    app/src/main/java/com/hintvault/ui/VaultLocationPrefs.kt \
    app/src/main/java/com/hintvault/ui/VaultViewModel.kt \
    app/src/test/java/com/hintvault/ui/MainDispatcherRule.kt \
    app/src/test/java/com/hintvault/ui/VaultViewModelTest.kt
git commit -m "feat(ui): VaultViewModel — 首次設定與解鎖流程"
```

---

### Task 7: `VaultViewModel` — 新增/編輯/刪除 + 衝突處理

**Files:**
- Modify: `app/src/main/java/com/hintvault/ui/VaultViewModel.kt`
- Modify: `app/src/test/java/com/hintvault/ui/VaultViewModelTest.kt`

**Interfaces:**
- Consumes: `Vault.upsert(entry: Entry)`、`Vault.delete(id: UUID): Boolean`、`Vault.serialize(masterPassword: CharArray)`、`VaultRepository.save(bytes, stamp): SaveResult`。
- Produces: `VaultViewModel.saveEntry(entry: Entry)`、`VaultViewModel.deleteEntry(id: UUID)`。

- [ ] **Step 1: 加測試**

```kotlin
    @Test
    fun saveEntryPersistsAndUpdatesEntriesInState() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        val entry = com.hintvault.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "github.com",
            hint = "提示",
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)

        assertEquals(1, vm.uiState.value.entries.size)
        assertEquals("github.com", vm.uiState.value.entries.single().title)
        assertEquals(SaveMessage.Saved, vm.uiState.value.saveMessage)
    }

    @Test
    fun saveEntryDetectsExternalConflictAndDoesNotLoseTheChange() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(bytes, file.lastModified()))
            // 這裡沒有走 readyViewModel()，要自己補上這個 seam，理由同 readyViewModel()
            // 內的註解：persist() 現在也透過 unlockDispatcher 跑，不換掉的話這個測試會
            // 在協程真正跑完之前就讀到 uiState。
            setUnlockDispatcherForTest(kotlinx.coroutines.Dispatchers.Unconfined)
        }
        vm.unlockWithPassword(pw())

        // 外部（模擬同步工具）在解鎖之後改了檔案
        file.content = file.content + byteArrayOf(0)

        val entry = com.hintvault.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "conflict-test",
            hint = "提示",
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)

        assertTrue(vm.uiState.value.saveMessage is SaveMessage.Conflict)
        // 「沒有把這筆改動弄丟」不能只看訊息種類，記憶體中的 vault（entries 來源）
        // 也必須還看得到這筆——upsert() 是直接改記憶體中的 Vault，衝突只影響存檔，
        // 不影響已經做的修改是否還在。
        assertTrue(vm.uiState.value.entries.any { it.title == "conflict-test" })
    }

    @Test
    fun deleteEntryRemovesItFromStateAndPersists() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())
        val entry = com.hintvault.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "to-delete",
            hint = "提示",
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)

        vm.deleteEntry(entry.id)

        assertTrue(vm.uiState.value.entries.isEmpty())
    }
```

- [ ] **Step 2: 執行測試，確認因為函式不存在而編譯失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.ui.VaultViewModelTest"`
Expected: 編譯失敗

- [ ] **Step 3: 在 `VaultViewModel.kt` 加入 CRUD**

加在 `lock()` 之前，並在檔案頂端補 `import com.hintvault.storage.SaveResult`、`import com.hintvault.vault.Entry`、`import java.util.UUID`、`import kotlinx.coroutines.sync.Mutex`、`import kotlinx.coroutines.sync.withLock`：

`Vault.serialize()` 跑的是同一顆 Argon2 KDF（~2 秒等級），與 Task 6 的 `attemptUnlock()` 完全同等級的耗時操作。若 `persist()` 寫成一般同步函式，之後 Task 13 的編輯畫面在按下「儲存」時會直接卡住主執行緒 2 秒——這正是 Task 6 引入 `unlockDispatcher` 這個注入點所要避免的同一類問題，這裡必須套用一致的處理，而不是重新發明或忽略。

但改成非同步後有第二個問題必須一併處理：`saveEntry`/`deleteEntry` 用 `viewModelScope.launch` 各自起一個協程後立刻返回，若使用者（或之後 Task 13 的畫面）連續呼叫兩次，兩個協程的 `persist()` 可能同時在跑——`vault.serialize()` 在背景執行緒讀取同一個 `db`（DOM 樹）的同時，另一個呼叫的 `vault.upsert()`/`delete()` 可能正在主執行緒改它，而且第二個呼叫讀到的 `loadedStamp` 可能是第一個呼叫更新前的舊值，導致明明是自己存的檔卻被誤判成外部衝突。用一個 `Mutex` 把 `persist()` 的臨界區（讀 `loadedStamp`、`serialize`、`save`、更新 `loadedStamp`）包起來解決——`stamp` 的讀取必須搬到鎖裡面，確保讀到的是最新值：

```kotlin
    // ---- 清單 / CRUD ----

    /** 序列化多個 saveEntry/deleteEntry/changeMasterPassword 呼叫，避免同時對同一個
     *  Vault 做 serialize()、或用過期的 loadedStamp 誤判成外部衝突。 */
    private val persistMutex = Mutex()

    /**
     * 開始一次新的存檔前，先把 `saveMessage` 清成 null。
     *
     * `SaveMessage.Saved` 是 `data object`（單例），如果連續兩次存檔都成功，
     * 第二次寫入的 `Saved` 跟第一次是同一個值——`EditScreen` 用
     * `LaunchedEffect(uiState.saveMessage)` 偵測這個值的變化來決定要不要導覽離開，
     * 而 Compose 只在 key **真的變了**才會重新觸發 effect。若不清成 null，
     * 第二筆存檔完成時 `saveMessage` 從 `Saved` 變成同一個 `Saved`，Compose 判斷
     * 「沒變」，effect 不會重新觸發，畫面就卡住走不掉——這是 Task 18 端到端測試
     * 連續新增兩筆記錄時抓到的真實 bug，不是假設性風險。清成 null 保證每次存檔
     * 完成時，一定是「null → 真正的結果」這種貨真價實的變化，不論上一次存檔的
     * 結果是什麼。
     */
    fun saveEntry(entry: Entry) {
        val vault = session.withVault { it }
        vault.upsert(entry)
        _uiState.update { it.copy(saveMessage = null) }
        viewModelScope.launch { persist(vault) }
    }

    fun deleteEntry(id: UUID) {
        val vault = session.withVault { it }
        vault.delete(id)
        _uiState.update { it.copy(saveMessage = null) }
        viewModelScope.launch { persist(vault) }
    }

    private suspend fun persist(vault: Vault) {
        val password = masterPasswordCache ?: return
        val repo = repository ?: return
        persistMutex.withLock {
            val stamp = loadedStamp ?: return@withLock
            val bytes = withContext(unlockDispatcher) { vault.serialize(password) }
            when (val result = withContext(unlockDispatcher) { repo.save(bytes, stamp) }) {
                is SaveResult.Saved -> {
                    loadedStamp = result.newStamp
                    _uiState.update { it.copy(saveMessage = SaveMessage.Saved, entries = vault.entries()) }
                }
                is SaveResult.Conflict -> {
                    _uiState.update {
                        it.copy(saveMessage = SaveMessage.Conflict(result.conflictFileName), entries = vault.entries())
                    }
                }
            }
        }
    }
```

- [ ] **Step 4: 執行測試，確認全部通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.ui.VaultViewModelTest"`
Expected: PASS（10 個測試）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/VaultViewModel.kt \
    app/src/test/java/com/hintvault/ui/VaultViewModelTest.kt
git commit -m "feat(ui): VaultViewModel — 新增/編輯/刪除與衝突處理"
```

---

### Task 8: `VaultViewModel` — 設定（生體辨識 / 自動上鎖 / 變更主密碼）

**Files:**
- Modify: `app/src/main/java/com/hintvault/ui/VaultViewModel.kt`
- Modify: `app/src/test/java/com/hintvault/ui/VaultViewModelTest.kt`

**Interfaces:**
- Consumes: `crypto.KeystoreKeys`、`crypto.WrappedKeyStore`、`session.AutoLockPolicy`。
- Produces: `VaultViewModel.enrollBiometric(biometric: BiometricUnlock)`、`disableBiometric(context: Context)`、`setAutoLockTimeout(millis: Long)`、`changeMasterPassword(newPassword: CharArray, biometric: BiometricUnlock?)`。

生體辨識實際互動（`BiometricPrompt`）依規格 §5 不強行寫自動測試——`enrollBiometric`/`unlockWithBiometric`（Task 6 已寫）都是「呼叫 `BiometricUnlock` 的方法、再共用同一段已被測過的解鎖/存檔邏輯」的薄包裝，`BiometricUnlock` 本身無法在不啟動真正 `BiometricPrompt` 的情況下於 JVM 測試假冒（它是一般 class，非 `open`，且建構子需要真的 `FragmentActivity`）。本 Task 因此只單元測試不涉及 `BiometricUnlock` 的部分（`setAutoLockTimeout`、`changeMasterPassword` 的存檔/衝突邏輯本身），`enrollBiometric`/`disableBiometric` 的真實行為記入 `docs/manual-verification.md`（Step 6）。

- [ ] **Step 1: 加測試**

```kotlin
    @Test
    fun setAutoLockTimeoutUpdatesPolicy() = runTest {
        val vm = VaultViewModel()
        vm.setAutoLockTimeout(15 * 60_000L)
        assertEquals(15 * 60_000L, vm.uiState.value.autoLockPolicy.idleTimeoutMillis)
    }

    @Test
    fun changeMasterPasswordReserializesAndSaves() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw("old-password"))
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw("old-password"))

        vm.changeMasterPassword(pw("new-password"), biometric = null)

        assertEquals(SaveMessage.Saved, vm.uiState.value.saveMessage)
    }

    @Test
    fun changeMasterPasswordThenReopeningWithNewPasswordWorks() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw("old-password"))
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(bytes, file.lastModified()))
            setUnlockDispatcherForTest(kotlinx.coroutines.Dispatchers.Unconfined)
        }
        vm.unlockWithPassword(pw("old-password"))

        vm.changeMasterPassword(pw("new-password"), biometric = null)

        // 舊密碼開不起來了，新密碼開得起來——直接用底層 Vault.open 驗證存檔內容本身
        var threwWithOldPassword = false
        try {
            Vault.open(file.content, pw("old-password"))
        } catch (_: WrongPasswordException) {
            threwWithOldPassword = true
        }
        assertTrue(threwWithOldPassword)
        Vault.open(file.content, pw("new-password")) // 不拋例外即為成功
    }
```

- [ ] **Step 2: 執行測試，確認因為函式不存在而編譯失敗**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.ui.VaultViewModelTest"`
Expected: 編譯失敗

- [ ] **Step 3: 加入設定相關函式**

加在 `deleteEntry`/`persist` 之後，並在檔案頂端補 `import com.hintvault.crypto.KeystoreKeys`、`import com.hintvault.crypto.WrappedKeyStore`、`import com.hintvault.crypto.toUtf8Bytes`、`import com.hintvault.session.AutoLockPolicy`：

```kotlin
    // ---- 設定 ----

    /**
     * 前提：`masterPasswordCache` 非 null，代表本次 session 已經用主密碼
     * 或生體辨識成功解鎖過一次——規格 §4.6「必須先以主密碼手動解鎖一次，
     * 才能啟用生體辨識」在這裡是自動滿足的，因為兩條解鎖路徑（Task 6）
     * 都會先寫入這個欄位。
     *
     * `BiometricUnlock` 本身無法在 JVM 環境下實際觸發 `BiometricPrompt`，
     * 因此本函式的真實行為只在真機/模擬器上驗證（見 docs/manual-verification.md）。
     */
    fun enrollBiometric(biometric: BiometricUnlock) {
        val password = masterPasswordCache ?: return
        viewModelScope.launch {
            val bytes = password.toUtf8Bytes()
            val success = try {
                biometric.enroll(bytes)
            } finally {
                bytes.wipe()
            }
            if (success) _uiState.update { it.copy(biometricEnabled = true) }
        }
    }

    fun disableBiometric(context: Context) {
        KeystoreKeys().deleteKey()
        WrappedKeyStore(keyDir(context)).clear()
        _uiState.update { it.copy(biometricEnabled = false) }
    }

    fun setAutoLockTimeout(millis: Long) {
        _uiState.update { it.copy(autoLockPolicy = it.autoLockPolicy.copy(idleTimeoutMillis = millis)) }
    }

    /**
     * 若已啟用生體辨識，[biometric] 不可為 null——重新呼叫 `enroll()` 會用
     * 新密碼蓋掉包裝檔（`BiometricUnlock.enroll()` 內部一開始就 `store.clear()`），
     * 這是規格 §4.6 點名「忘了同步更新會導致生體辨識解鎖用舊密碼失敗」
     * 這個風險最高步驟的處理方式：不是額外清除，而是靠重新 enroll 自然取代。
     */
    fun changeMasterPassword(newPassword: CharArray, biometric: BiometricUnlock?) {
        // 同 Task 7 的 persist()：vault.serialize() 是同一顆 Argon2 KDF，必須離開呼叫端
        // 執行緒，走同一個 unlockDispatcher seam，不能同步跑在呼叫端（之後 Task 15 的
        // 設定畫面會直接呼叫這個函式）。用同一個 persistMutex：若這個呼叫與
        // saveEntry/deleteEntry 幾乎同時發生，兩者都在動同一份 loadedStamp 與同一個檔案，
        // 必須互斥，理由與 Task 7 的 persist() 完全相同——stamp 的讀取也同樣搬到鎖裡面。
        //
        // vault.serialize()/repo.save() 都可能拋例外（例如檔案被外部刪除，見
        // VaultRepository.load() 的說明）；若沒接住就直接讓例外往外傳，newPassword
        // 會被漏清。理由與寫法都同 attemptUnlock()：catch CancellationException 原樣
        // 重丟（不能把「協程被取消」誤判成別的錯誤狀態），其餘都在 finally 裡清空，
        // 保證不論哪條路徑離開都只清空一次。
        // 同 saveEntry/deleteEntry：saveMessage 是 data object 單例，連續兩次都成功
        // 會是同一個 Saved 值，EditScreen（以及萬一之後有其他畫面）用來判斷「該不該
        // 反應」的 LaunchedEffect key 偵測不到變化。這裡先清成 null，保證這次呼叫
        // 結束時一定是「null → 真正的結果」的貨真價實變化。
        val vault = try {
            session.withVault { it }
        } catch (e: Exception) {
            newPassword.wipe()
            throw e
        }
        _uiState.update { it.copy(saveMessage = null) }
        viewModelScope.launch {
            try {
                val repo = repository ?: return@launch
                persistMutex.withLock {
                    val stamp = loadedStamp ?: return@withLock
                    val bytes = withContext(unlockDispatcher) { vault.serialize(newPassword) }
                    when (val result = withContext(unlockDispatcher) { repo.save(bytes, stamp) }) {
                        is SaveResult.Saved -> {
                            loadedStamp = result.newStamp
                            cacheMasterPassword(newPassword)
                            if (uiState.value.biometricEnabled && biometric != null) {
                                enrollBiometric(biometric)
                            }
                            _uiState.update { it.copy(saveMessage = SaveMessage.Saved) }
                        }
                        is SaveResult.Conflict -> {
                            _uiState.update { it.copy(saveMessage = SaveMessage.Conflict(result.conflictFileName)) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                newPassword.wipe()
            }
        }
    }
```

- [ ] **Step 4: 執行測試，確認全部通過**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hintvault.ui.VaultViewModelTest"`
Expected: PASS（13 個測試）

- [ ] **Step 5: 執行整個 JVM 測試套件，確認沒有破壞既有測試**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部通過

- [ ] **Step 6: 在 `docs/manual-verification.md` 補上生體辨識設定相關的檢查項**

在檔案裡加一節（沿用既有格式）：

```markdown
## 設定畫面：生體辨識啟用/停用

- [ ] 已用主密碼解鎖後，設定畫面開啟「生體辨識解鎖」開關 → 跳出 BiometricPrompt →
      通過後開關維持開啟；殺掉 app 重開、生體辨識解鎖成功
- [ ] 開啟開關後，在驗證畫面按取消 → 開關應維持關閉（enroll 失敗不該假裝成功）
- [ ] 關閉「生體辨識解鎖」開關 → 殺掉 app 重開，應直接進主密碼輸入框，不再跳生體辨識
- [ ] 已啟用生體辨識時變更主密碼 → 殺掉 app 重開、生體辨識解鎖 → 應解出**新**密碼對應的資料庫，
      而不是卡在用舊密碼失敗
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/VaultViewModel.kt \
    app/src/test/java/com/hintvault/ui/VaultViewModelTest.kt \
    docs/manual-verification.md
git commit -m "feat(ui): VaultViewModel — 生體辨識設定/自動上鎖/變更主密碼"
```

---

### Task 9: 首次設定畫面（`SetupScreen`）

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/screens/SetupScreen.kt`

**Interfaces:**
- Consumes: `SetupState`、`VaultViewModel.onFolderChosen/onFileChosen/onNewVaultPasswordChosen/resolveBackupDecision`。

- [ ] **Step 1: 寫畫面**

```kotlin
package com.hintvault.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hintvault.ui.SetupState
import com.hintvault.ui.VaultViewModel

@Composable
fun SetupScreen(state: SetupState, viewModel: VaultViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderChosen(context, uri)
    }

    when (state) {
        SetupState.CheckingFolder -> CircularProgressIndicator()

        SetupState.NeedsFolderPicker -> Column(Modifier.padding(16.dp)) {
            Text("選擇要用來存放資料庫的同步資料夾（例如 Syncthing 或 Nextcloud 的同步目錄）")
            Button(onClick = { pickFolder.launch(null) }) { Text("選擇資料夾") }
        }

        is SetupState.NeedsFileChoice -> Column(Modifier.padding(16.dp)) {
            Text("這個資料夾裡找到多個資料庫檔案，請選擇一個")
            state.candidateNames.forEach { name ->
                TextButton(onClick = { viewModel.onFileChosen(context, name) }) { Text(name) }
            }
        }

        SetupState.NeedsNewVaultPassword -> {
            var password by remember { mutableStateOf("") }
            var confirm by remember { mutableStateOf("") }
            Column(Modifier.padding(16.dp)) {
                Text("這個資料夾裡沒有找到資料庫，設定主密碼建立一個新的（檔名固定為 vault.kdbx）")
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("主密碼") }, visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it },
                    label = { Text("確認主密碼") }, visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    onClick = { viewModel.onNewVaultPasswordChosen(context, password.toCharArray()) },
                    enabled = password.isNotEmpty() && password == confirm
                ) { Text("建立") }
            }
        }

        is SetupState.NeedsBackupDecision -> AlertDialog(
            onDismissRequest = {},
            title = { Text("偵測到資料庫檔案異常") },
            text = { Text("找不到資料庫檔案，或檔案本身已損毀。是否從最近一次的備份還原？") },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveBackupDecision(true) }) { Text("還原") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveBackupDecision(false) }) { Text("不還原") }
            }
        )

        SetupState.Ready -> {}
    }
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/screens/SetupScreen.kt
git commit -m "feat(ui): 首次設定畫面（選資料夾/建立新庫/備份還原提示）"
```

---

### Task 10: 解鎖畫面（`UnlockScreen`）

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/screens/UnlockScreen.kt`

**Interfaces:**
- Consumes: `VaultUiState`、`VaultViewModel.unlockWithPassword/unlockWithBiometric`、`crypto.BiometricUnlock`、`crypto.WrappedKeyStore`。

- [ ] **Step 1: 寫畫面**

```kotlin
package com.hintvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.hintvault.crypto.BiometricUnlock
import com.hintvault.crypto.WrappedKeyStore
import com.hintvault.session.LockState
import com.hintvault.ui.UnlockError
import com.hintvault.ui.VaultUiState
import com.hintvault.ui.VaultViewModel
import java.io.File

@Composable
fun UnlockScreen(state: VaultUiState, viewModel: VaultViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    val biometric = remember(activity) {
        activity?.let {
            BiometricUnlock(it, store = WrappedKeyStore(File(context.applicationContext.filesDir, "keys")))
        }
    }

    // 進度指示的開關（state.isUnlocking）完全交給 VaultViewModel 管理，畫面自己不猜。
    // 舊版曾經在這裡用本地 var + LaunchedEffect(unlockError) 推斷「該不該還亮著」，
    // 但使用者取消生體辨識時 unlockError 不會變、連續兩次同一種錯誤時 enum 值也不會變，
    // 兩種情況下 Compose 都不會重新觸發 effect，指示器就卡住轉不停——這是 Task 10 review
    // 抓到的真實 bug，修法是把這個信號整個搬到 ViewModel 側，不再由畫面自己推斷。
    LaunchedEffect(state.biometricEnabled, biometric, usePassword) {
        if (state.biometricEnabled && !usePassword && biometric != null) {
            viewModel.unlockWithBiometric(biometric)
        }
    }

    Column(Modifier.padding(16.dp)) {
        when (state.unlockError) {
            UnlockError.WRONG_PASSWORD -> Text("密碼錯誤，請再試一次")
            UnlockError.UNSUPPORTED_FORMAT ->
                Text("此資料庫使用本 app 不支援的格式（例如 KeePassXC 用 Argon2id 建立的資料庫），請到 KeePassXC 把 KDF 改回 Argon2d")
            UnlockError.FILE_MISSING -> Text("找不到資料庫檔案")
            UnlockError.BIOMETRIC_INVALIDATED -> Text("生體辨識金鑰已失效，請改用主密碼")
            null -> {}
        }

        if (state.lockState == LockState.Locked && (usePassword || !state.biometricEnabled)) {
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("主密碼") }, visualTransformation = PasswordVisualTransformation()
            )
            Button(onClick = {
                viewModel.unlockWithPassword(password.toCharArray())
                password = ""
            }) { Text("解鎖") }
        }

        if (state.biometricEnabled && !usePassword) {
            TextButton(onClick = { usePassword = true }) { Text("改用主密碼") }
        }

        // 規格 §4.1：即使是生體辨識，實際開檔仍完整跑一次 Argon2d（約 2.2 秒），
        // 畫面必須顯示進度指示，不可看起來像卡住。
        if (state.isUnlocking) {
            CircularProgressIndicator()
        }
    }
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/screens/UnlockScreen.kt
git commit -m "feat(ui): 解鎖畫面（主密碼 + 生體辨識）"
```

---

### Task 11: 清單／搜尋畫面（`ListScreen`）

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/screens/ListScreen.kt`

搜尋過濾的 Compose UI 測試需要一個可運作的 `NavController`／完整導覽環境；在 Task 16 導覽圖接上後，於 Task 18 的端到端測試裡一併驗證會比在此拼湊一個假的 nav host 更真實，因此本 Task 不單獨建測試檔。

**Interfaces:**
- Consumes: `List<Entry>`、`NavController`、`VaultViewModel.lock`。

- [ ] **Step 1: 寫畫面**

```kotlin
package com.hintvault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.hintvault.ui.VaultViewModel
import com.hintvault.vault.Entry

@Composable
fun ListScreen(entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) {
        entries
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .sortedBy { it.title }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提示保險庫") },
                actions = {
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(Icons.Default.Lock, contentDescription = "鎖定")
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("edit_new") }) {
                Icon(Icons.Default.Add, contentDescription = "新增")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜尋") },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn {
                items(filtered, key = { it.id.toString() }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.modifiedAt.toString()) },
                        modifier = Modifier.clickable { navController.navigate("detail/${entry.id}") }
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

（`ListScreenTest.kt` 需要一個可運作的 `NavController`／完整導覽環境，留到 Task 16 導覽圖接上後，在 Task 16 一併撰寫 Compose UI 測試，比在此單獨拼湊一個假的 nav host 更真實。這裡先只保留檔案清單裡的 Test 條目作為 Task 16 的提醒，不在本 Task 建立空測試檔。）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/screens/ListScreen.kt
git commit -m "feat(ui): 清單／搜尋畫面"
```

---

### Task 12: 詳情畫面（`DetailScreen`）

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/screens/DetailScreen.kt`

**Interfaces:**
- Consumes: `List<Entry>`、`NavController`、`VaultViewModel.deleteEntry`。

- [ ] **Step 1: 寫畫面**

```kotlin
package com.hintvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.hintvault.ui.VaultViewModel
import com.hintvault.vault.Entry
import java.util.UUID

@Composable
fun DetailScreen(entryId: UUID, entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val entry = entries.firstOrNull { it.id == entryId }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (entry == null) {
        Text("找不到這筆記錄，可能已被刪除")
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text(entry.title) }) }) { padding ->
        Column(Modifier.padding(padding)) {
            Text(entry.hint)
            Text("最後修改：${entry.modifiedAt}")
            Row {
                Button(onClick = { navController.navigate("edit/${entry.id}") }) { Text("編輯") }
                Button(onClick = { navController.navigate("history/${entry.id}") }) { Text("查看歷史") }
                Button(onClick = { showDeleteConfirm = true }) { Text("刪除") }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("確定刪除「${entry.title}」？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(entry.id)
                    showDeleteConfirm = false
                    navController.popBackStack()
                }) { Text("刪除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/screens/DetailScreen.kt
git commit -m "feat(ui): 詳情畫面（編輯/刪除/查看歷史）"
```

---

### Task 13: 編輯畫面（`EditScreen`）

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/screens/EditScreen.kt`

**Interfaces:**
- Consumes: `List<Entry>`、`NavController`、`VaultViewModel.saveEntry`、`VaultViewModel.uiState`（讀 `saveMessage`）。

- [ ] **Step 1: 寫畫面**

```kotlin
package com.hintvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.hintvault.ui.SaveMessage
import com.hintvault.ui.VaultViewModel
import com.hintvault.vault.Entry
import java.time.Instant
import java.util.UUID

@Composable
fun EditScreen(entryId: UUID?, entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val existing = entryId?.let { id -> entries.firstOrNull { it.id == id } }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var hint by remember { mutableStateOf(existing?.hint.orEmpty()) }
    val uiState by viewModel.uiState.collectAsState()

    // VaultViewModel 沒有「用完就清掉 saveMessage」的機制，所以這個畫面一進來，
    // uiState.saveMessage 可能還留著上一次別的畫面（例如 DetailScreen 的刪除、
    // 或這個畫面自己上一次的存檔）留下的舊值。若只憑 saveMessage 本身的型別判斷
    // 是否要導覽/顯示衝突訊息，畫面一開就會誤把舊訊息當成「這次」存檔的結果——
    // 輕則是新增畫面一開就自己跳走，重則是把不相干的舊衝突訊息掛在使用者根本
    // 還沒碰過的這筆記錄上。用 didAttemptSave 這個畫面自己的旗標擋住：只有在
    // 這次畫面存活期間真的按過「儲存」，才理會 saveMessage 的變化。
    var didAttemptSave by remember { mutableStateOf(false) }

    // 只在成功存檔時離開這個畫面；SaveMessage.Conflict 時必須留在原地，
    // 讓使用者看到規格 §4.4 要求的衝突訊息，不可靜默導覽走。
    LaunchedEffect(uiState.saveMessage) {
        if (didAttemptSave && uiState.saveMessage is SaveMessage.Saved) {
            navController.popBackStack()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(if (existing == null) "新增" else "編輯") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            if (didAttemptSave) {
                (uiState.saveMessage as? SaveMessage.Conflict)?.let {
                    Text("修改已存到另一個檔案（${it.conflictFileName}），同步資料夾裡的本尊沒有被動到，請到 KeePassXC 手動合併")
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("標題") })
            OutlinedTextField(value = hint, onValueChange = { hint = it }, label = { Text("提示") })
            Button(
                onClick = {
                    didAttemptSave = true
                    val entry = Entry(
                        id = existing?.id ?: UUID.randomUUID(),
                        title = title,
                        hint = hint,
                        modifiedAt = Instant.now(),
                        history = existing?.history ?: emptyList()
                    )
                    viewModel.saveEntry(entry)
                },
                enabled = title.isNotBlank()
            ) { Text("儲存") }
        }
    }
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/screens/EditScreen.kt
git commit -m "feat(ui): 編輯畫面（含衝突訊息顯示）"
```

---

### Task 14: 歷史畫面（`HistoryScreen`）

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/screens/HistoryScreen.kt`

**Interfaces:**
- Consumes: `List<Entry>`（讀 `Entry.history`）。

- [ ] **Step 1: 寫畫面**

```kotlin
package com.hintvault.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import com.hintvault.vault.Entry
import java.util.UUID

@Composable
fun HistoryScreen(entryId: UUID, entries: List<Entry>) {
    val entry = entries.firstOrNull { it.id == entryId }
    val history = entry?.history?.sortedByDescending { it.at }.orEmpty()

    Scaffold(topBar = { TopAppBar(title = { Text(entry?.title ?: "歷史紀錄") }) }) { padding ->
        if (history.isEmpty()) {
            Text("沒有歷史紀錄", modifier = androidx.compose.ui.Modifier.padding(padding))
        } else {
            LazyColumn(androidx.compose.ui.Modifier.padding(padding)) {
                items(history) { h ->
                    ListItem(
                        headlineContent = { Text(h.hint) },
                        supportingContent = { Text(h.at.toString()) }
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/screens/HistoryScreen.kt
git commit -m "feat(ui): 歷史紀錄畫面（唯讀）"
```

---

### Task 15: 設定畫面（`SettingsScreen`）

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/screens/SettingsScreen.kt`

**Interfaces:**
- Consumes: `VaultUiState`、`VaultViewModel.enrollBiometric/disableBiometric/setAutoLockTimeout/changeMasterPassword/onFolderChosen`、`crypto.BiometricUnlock`、`crypto.WrappedKeyStore`。

- [ ] **Step 1: 寫畫面**

```kotlin
package com.hintvault.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.fragment.app.FragmentActivity
import com.hintvault.crypto.BiometricUnlock
import com.hintvault.crypto.WrappedKeyStore
import com.hintvault.ui.VaultUiState
import com.hintvault.ui.VaultViewModel
import java.io.File

@Composable
fun SettingsScreen(uiState: VaultUiState, viewModel: VaultViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderChosen(context, uri)
    }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    fun currentBiometric(): BiometricUnlock? =
        activity?.let { BiometricUnlock(it, store = WrappedKeyStore(File(context.filesDir, "keys"))) }

    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            Text("資料庫位置")
            Button(onClick = { pickFolder.launch(null) }) { Text("重選資料夾") }

            Row {
                Text("生體辨識解鎖")
                Switch(
                    checked = uiState.biometricEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            currentBiometric()?.let { viewModel.enrollBiometric(it) }
                        } else {
                            viewModel.disableBiometric(context)
                        }
                    }
                )
            }

            Text("自動上鎖逾時")
            Row {
                listOf(1L to "1 分鐘", 5L to "5 分鐘", 15L to "15 分鐘").forEach { (minutes, label) ->
                    val millis = minutes * 60_000L
                    TextButton(onClick = { viewModel.setAutoLockTimeout(millis) }) {
                        Text(label + if (uiState.autoLockPolicy.idleTimeoutMillis == millis) "（使用中）" else "")
                    }
                }
            }

            Text("變更主密碼")
            OutlinedTextField(
                value = newPassword, onValueChange = { newPassword = it },
                label = { Text("新主密碼") }, visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                value = confirmPassword, onValueChange = { confirmPassword = it },
                label = { Text("確認新主密碼") }, visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    val biometric = if (uiState.biometricEnabled) currentBiometric() else null
                    viewModel.changeMasterPassword(newPassword.toCharArray(), biometric)
                    newPassword = ""
                    confirmPassword = ""
                },
                enabled = newPassword.isNotEmpty() && newPassword == confirmPassword
            ) { Text("變更") }
        }
    }
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/screens/SettingsScreen.kt
git commit -m "feat(ui): 設定畫面（資料夾/生體辨識/自動上鎖/變更主密碼）"
```

---

### Task 16: 導覽圖（`HintVaultApp`），接上 `MainActivity`

**Files:**
- Create: `app/src/main/java/com/hintvault/ui/HintVaultApp.kt`
- Modify: `app/src/main/java/com/hintvault/ui/MainActivity.kt`
- Modify: `app/src/androidTest/java/com/hintvault/ui/MainActivitySmokeTest.kt`

**Interfaces:**
- Consumes: 全部 7 個畫面 Composable、`VaultViewModel`。
- Produces: `HintVaultApp(viewModel: VaultViewModel)`。

- [ ] **Step 1: 寫導覽圖**

```kotlin
package com.hintvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hintvault.session.LockState
import com.hintvault.ui.screens.DetailScreen
import com.hintvault.ui.screens.EditScreen
import com.hintvault.ui.screens.HistoryScreen
import com.hintvault.ui.screens.ListScreen
import com.hintvault.ui.screens.SettingsScreen
import com.hintvault.ui.screens.SetupScreen
import com.hintvault.ui.screens.UnlockScreen
import java.util.UUID

@Composable
fun HintVaultApp(viewModel: VaultViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.checkExistingSetup(context) }

    // 首次設定尚未完成、或已上鎖：一律導去對應畫面。
    // 這個 effect 是「進入未解鎖狀態」唯一的導覽來源，其餘畫面之間的
    // 切換（清單 → 詳情 → 編輯…）由各畫面自己呼叫 navController.navigate()。
    LaunchedEffect(uiState.setup, uiState.lockState) {
        val target = when {
            uiState.setup != SetupState.Ready -> "setup"
            uiState.lockState == LockState.Locked -> "unlock"
            else -> null
        }
        if (target != null && navController.currentDestination?.route != target) {
            navController.navigate(target) { popUpTo(0) }
        }
    }

    NavHost(navController = navController, startDestination = "setup") {
        composable("setup") { SetupScreen(uiState.setup, viewModel) }
        composable("unlock") { UnlockScreen(uiState, viewModel) }
        composable("list") { ListScreen(uiState.entries, navController, viewModel) }
        composable("detail/{entryId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            DetailScreen(id, uiState.entries, navController, viewModel)
        }
        composable("edit_new") { EditScreen(null, uiState.entries, navController, viewModel) }
        composable("edit/{entryId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            EditScreen(id, uiState.entries, navController, viewModel)
        }
        composable("history/{entryId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            HistoryScreen(id, uiState.entries)
        }
        composable("settings") { SettingsScreen(uiState, viewModel) }
    }

    // 一旦設定完成且已解鎖，且目前停在 setup/unlock，導去清單。
    // 分兩個 LaunchedEffect 是為了讓「鎖定」永遠優先於「導去清單」，
    // 避免兩個方向的導覽競爭同一次 recomposition。
    LaunchedEffect(uiState.setup, uiState.lockState) {
        if (uiState.setup == SetupState.Ready && uiState.lockState == LockState.Unlocked) {
            val current = navController.currentDestination?.route
            if (current == "setup" || current == "unlock") {
                navController.navigate("list") { popUpTo(0) }
            }
        }
    }
}
```

- [ ] **Step 2: 把 `MainActivity` 接上真正的導覽圖**

```kotlin
package com.hintvault.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme {
                Surface {
                    HintVaultApp(viewModel)
                }
            }
        }
    }
}
```

- [ ] **Step 3: 刪掉 Task 1 的冒煙測試（斷言的文字已經不存在了），改成驗證真正的起始畫面**

把 `MainActivitySmokeTest.kt` 內容換成：

```kotlin
package com.hintvault.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsSetupOrUnlockScreen() {
        // 全新安裝、沒有任何持久化的資料夾選擇 → 應落在首次設定畫面。
        composeRule.onNodeWithText("選擇資料夾").assertExists()
    }
}
```

- [ ] **Step 4: 真機/模擬器上執行**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.hintvault.ui.MainActivitySmokeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/HintVaultApp.kt \
    app/src/main/java/com/hintvault/ui/MainActivity.kt \
    app/src/androidTest/java/com/hintvault/ui/MainActivitySmokeTest.kt
git commit -m "feat(ui): 導覽圖，接上 MainActivity"
```

---

### Task 17: `AutoLock` 接上 Activity 生命週期

**Files:**
- Modify: `app/src/main/java/com/hintvault/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `session.AutoLock`、`session.AutoLockPolicy`、`VaultViewModel.uiState`（讀 `autoLockPolicy`）、`VaultViewModel.lock()`。

- [ ] **Step 1: 加上生命週期事件轉發 + 定期 `tick()` + 螢幕關閉偵測**

```kotlin
package com.hintvault.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.hintvault.session.AutoLock
import com.hintvault.session.AutoLockPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    private var autoLock: AutoLock = newAutoLock(AutoLockPolicy())
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            autoLock.tick()
            tickHandler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            autoLock.onScreenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // 設定畫面改自動上鎖逾時後，policy 會變；AutoLock 本身沒有「換 policy」
        // 的 API，重建一個新的最單純，成本可忽略。
        lifecycleScope.launch {
            viewModel.uiState.map { it.autoLockPolicy }.distinctUntilChanged()
                .collect { policy -> autoLock = newAutoLock(policy) }
        }

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        setContent {
            MaterialTheme {
                Surface {
                    HintVaultApp(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        autoLock.onReturnToForeground()
        tickHandler.postDelayed(tickRunnable, TICK_INTERVAL_MILLIS)
    }

    override fun onPause() {
        super.onPause()
        autoLock.onEnterBackground()
        tickHandler.removeCallbacks(tickRunnable)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLock.onUserInteraction()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenOffReceiver)
    }

    private fun newAutoLock(policy: AutoLockPolicy) = AutoLock(
        policy = policy,
        now = { SystemClock.elapsedRealtime() },
        onLock = { viewModel.lock() }
    )

    private companion object {
        const val TICK_INTERVAL_MILLIS = 15_000L
    }
}
```

- [ ] **Step 2: 編譯確認**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 在 `docs/manual-verification.md` 補上自動上鎖的真機檢查項**

```markdown
## 自動上鎖

- [ ] 解鎖後閒置超過設定的逾時 → app 自動回到解鎖畫面
- [ ] 解鎖後按 Home 鍵切到背景 → 立即上鎖（`AutoLockPolicy.backgroundGraceMillis = 0`）
- [ ] 解鎖後按電源鍵關閉螢幕 → 立即上鎖，不套用任何寬限期
- [ ] 解鎖後持續操作（捲動清單、輸入文字）→ 不會在逾時內被誤判為閒置而上鎖
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hintvault/ui/MainActivity.kt docs/manual-verification.md
git commit -m "feat(ui): AutoLock 接上 Activity 生命週期"
```

---

### Task 18: 真機端到端測試

比照核心引擎 Task 12 的做法：在真機/模擬器上完整跑一次「首次設定 → 建立資料庫 → 新增一筆 → 手動上鎖 → 用主密碼解鎖 → 確認看得到那筆」。與 `EndToEndTest.kt`（核心引擎的、不含 UI 的端到端測試）分開，避免同名衝突，也避免混淆「測的是核心引擎」還是「測的是 UI 層」。

生體辨識部分（跳 `BiometricPrompt`、真的用指紋通過）無法在 Compose UI 測試裡自動觸發系統層級的生體辨識 UI，留給 `docs/manual-verification.md`，不強行寫自動測試——與規格 §5 的既有立場一致。

**Files:**
- Create: `app/src/androidTest/java/com/hintvault/ui/UiEndToEndTest.kt`

**Interfaces:**
- Consumes: `HintVaultApp`、`VaultViewModel`、全部畫面。

- [ ] **Step 1: 寫測試**

```kotlin
package com.hintvault.ui

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiEndToEndTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var syncDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        syncDir = File(context.cacheDir, "ui-e2e-${System.nanoTime()}").apply { mkdirs() }
    }

    /**
     * 這個測試不透過真正的 SAF 資料夾選擇器（那需要系統 UI 互動，Compose
     * 測試框架碰不到），而是直接呼叫 `viewModel.onFolderChosen()` 對應的
     * 底層路徑：用 `DocumentFile.fromFile()` 包一個普通目錄，走與
     * `SafVaultFileTest`／核心引擎 `EndToEndTest` 相同的既有測試模式。
     */
    @Test
    fun firstLaunchCreateEntryLockUnlockShowsEntry() {
        composeRule.activity.runOnUiThread {
            val vm = composeRule.activity.let { activity ->
                androidx.lifecycle.ViewModelProvider(activity)[VaultViewModel::class.java]
            }
            vm.onFolderChosen(
                composeRule.activity,
                DocumentFile.fromFile(syncDir).uri
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("主密碼").performTextInput("correct horse battery staple")
        composeRule.onNodeWithText("確認主密碼").performTextInput("correct horse battery staple")
        composeRule.onNodeWithText("建立").performClick()

        composeRule.waitForIdle()
        // 「新增」是 FAB 的 contentDescription，不是可見文字，要用
        // onNodeWithContentDescription 找；「標題」「提示」「儲存」在
        // EditScreen 上確實是可見文字（欄位標籤/按鈕文字），用 onNodeWithText。
        composeRule.onNodeWithContentDescription("新增").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("標題").performTextInput("github.com")
        composeRule.onNodeWithText("提示").performTextInput("提示：貓的名字 + 生日")
        composeRule.onNodeWithText("儲存").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("github.com").assertExists()

        // 規格 §5 要求搜尋過濾要有 Compose UI 測試覆蓋：新增第二筆，
        // 用搜尋框過濾，確認不相關的那筆被濾掉、相關的那筆還在。
        composeRule.onNodeWithContentDescription("新增").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("標題").performTextInput("example.com")
        composeRule.onNodeWithText("提示").performTextInput("提示：無關的另一筆")
        composeRule.onNodeWithText("儲存").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("搜尋").performTextInput("github")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("github.com").assertExists()
        composeRule.onNodeWithText("example.com").assertDoesNotExist()

        // 「鎖定」同樣是 TopAppBar 上圖示按鈕的 contentDescription。
        composeRule.onNodeWithContentDescription("鎖定").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("主密碼").performTextInput("correct horse battery staple")
        composeRule.onNodeWithText("解鎖").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("github.com").assertExists()
    }
}
```

- [ ] **Step 2: 真機/模擬器上執行**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.hintvault.ui.UiEndToEndTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/hintvault/ui/UiEndToEndTest.kt
git commit -m "test: UI 層端到端驗證"
```

---

## 完成後

全部 18 個 Task 完成、真機測試都跑過一輪後，比照核心引擎的流程：跑一次全分支審查（`superpowers:finishing-a-development-branch` 前的整體 review），確認：

- `docs/superpowers/specs/2026-08-13-password-hint-vault-design.md` §4.1/§4.2 與 `docs/superpowers/specs/2026-08-15-hint-vault-ui-design.md` 之間沒有互相矛盾的地方
- `docs/manual-verification.md` 累積下來的檢查項（Task 8、17，加上規格既有的生體辨識邊界案例）在真機上真的走過一輪
- 三個核心引擎最終審查標記的缺口（生體辨識串接、建立新資料庫、備份還原）確實都有測試覆蓋到，不是只有 UI 接上去但底層邏輯沒被驗證過
