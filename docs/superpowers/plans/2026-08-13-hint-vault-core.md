# 密碼提示保險庫 — 實作計畫（核心引擎）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立一個安全的 Android 保險庫引擎，以 KDBX 4 格式儲存密碼提示，支援主密碼與生體辨識解鎖、SAF 外部檔案存取、以及絕不覆蓋外部改動的衝突偵測。

**Architecture:** 五層互不相依的 package：`crypto`（金鑰）、`vault`（KDBX 與領域模型）、`storage`（SAF 位元組進出與變動偵測）、`session`（金鑰生命週期）、`ui`（Compose）。`crypto` 不認識檔案，`storage` 不認識加密內容。KDBX 讀寫建立在自行 vendor 並修補的 KeePassJava2 `dom` 模組之上。

**Tech Stack:** Kotlin、Jetpack Compose、KeePassJava2-kdbx 2.2.4、AndroidX Biometric、Android Keystore（StrongBox）、Gradle 9.7.0、AGP 9.3.1。

## 範圍說明

本計畫涵蓋規格 §2–§6、§8 —— 亦即完整的資料與安全核心，含所有測試。完成後會得到一個經過測試、可與 KeePassXC 互通的保險庫引擎，但**尚無使用者介面**。

規格 §7 的六個 Compose 畫面另立計畫（`2026-08-13-hint-vault-ui.md`），於本計畫完成後撰寫。這樣切分的理由：UI 的每個畫面都建立在此處的 `VaultSession` 與 `Vault` API 之上，介面定案前寫 UI 計畫會產生大量返工。**本計畫的最後一項任務會產出一個可在裝置上執行的端到端驗證程式，因此本計畫獨立交付即為可運作、可驗證的軟體。**

### 明確移交給 UI 計畫的規格項目

這些是規格中屬於本計畫範圍、但技術上必須有 Activity 或畫面才能實作的項目。列在此處以免落入兩份計畫之間的縫隙：

| 規格項目 | 內容 |
|---|---|
| §4.6 | 所有 Activity 設 `FLAG_SECURE` |
| §4.6 | 提示欄位設 `importantForAutofill="no"` |
| §4.4 | `BiometricPrompt` 的實際解鎖流程（本計畫已備妥 `BiometricUnlock` API 與金鑰，但無畫面可觸發） |
| §6 | 把 `AutoLock` 接上 Activity 生命週期與 `ACTION_SCREEN_OFF` 廣播 |
| §5.2 | `ACTION_OPEN_DOCUMENT` 選檔流程與 `takePersistableUriPermission` 的呼叫時機 |

### 關於 §8 的「Argon2 測試向量」

規格 §8 要求以已知測試向量驗證 KDF。本計畫不另寫測試向量測試，理由是 Task 12 的 KeePassXC 互通驗收是**更強**的檢查：它以一個完全獨立的實作驗證了 KDF、cipher、HMAC、XML 序列化整條鏈的正確性。單獨的 Argon2 測試向量只會驗到 BouncyCastle 的正確性，而那不是本專案的程式碼。

若 Task 12 Step 4 失敗，才需要退回來以測試向量隔離問題出在 KDF 還是其他環節。

## Global Constraints

以下為專案級要求，**每一項任務都隱含包含本節**：

- 語言為 Kotlin。唯一例外是 vendor 進來的 `org/linguafranca/pwdb/kdbx/dom/*.java`，維持 Java 原樣。
- `minSdk = 29`、`compileSdk = 36`、`targetSdk = 36`。
- app package 為 `com.hintvault`。
- 依賴 `org.linguafranca.pwdb:KeePassJava2-kdbx:2.2.4`，且**必須**排除 `ch.qos.logback` 與 `junit`。**不得**依賴 `KeePassJava2-dom`（會與 vendor 的程式碼造成 dex 重複類別）。
- 主密碼與金鑰一律以 `CharArray` / `ByteArray` 承載，**不得**使用 `String`，用畢立即歸零。
- **不得**將提示內容、金鑰或主密碼寫入 log，任何層級皆然。
- `AndroidManifest.xml` 必須設 `android:allowBackup="false"`。
- 所有 Activity 必須設 `FLAG_SECURE`。
- KDF 為 **Argon2d**（函式庫限制，非 Argon2id），預設參數 memory 32 MiB、iterations 2、parallelism 2。
- 內容加密為 AES-256。
- 提示存入 KDBX 的 **Password** 欄位（受 inner stream 保護），**非** Notes 欄位。
- 建置環境：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`、`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`。專案根目錄需有 `local.properties` 含 `sdk.dir=$ANDROID_HOME`（此檔已列入 `.gitignore`）。
- Gradle 以 `/private/tmp/.../scratchpad/gradle-9.7.0/bin/gradle` 執行，或由開發者自行安裝的 Gradle 9.7.0 執行。第一項任務會建立 wrapper，此後一律用 `./gradlew`。

## File Structure

```
settings.gradle.kts                             Gradle 專案定義
build.gradle.kts                                根建置檔
gradle/wrapper/                                 Gradle wrapper
app/build.gradle.kts                            app module 建置檔與依賴
app/src/main/AndroidManifest.xml                allowBackup=false

app/src/main/java/org/linguafranca/pwdb/kdbx/dom/    vendor 的 KDBX DOM 實作（Java，
    DomDatabaseWrapper.java                          Apache-2.0，僅 DomSerializableDatabase
    DomEntryWrapper.java                             有一行 Android 修補）
    DomGroupWrapper.java
    DomHelper.java
    DomIconWrapper.java
    DomSerializableDatabase.java

app/src/main/java/com/hintvault/
    crypto/
        Secrets.kt              ByteArray / CharArray 歸零工具
        KdfParams.kt            Argon2 參數與建立時的耗時校準
        KeystoreKeys.kt         Android Keystore 金鑰產生與取得，StrongBox 退回 TEE
        WrappedKeyStore.kt      被包裝的 vault key 在 app 私有目錄的存取
        BiometricUnlock.kt      BiometricPrompt + CryptoObject 的包裝與解包
    vault/
        Entry.kt                領域模型 Entry 與 HistoricalHint
        HistoryCodec.kt         history ↔ KDBX 自訂字串欄位的編解碼
        Vault.kt                KDBX 開啟 / 修改 / 序列化，保留未知內容
    storage/
        FileStamp.kt            lastModified + SHA-256 的組合與比對
        VaultFile.kt            SAF 讀寫與 URI 權限持久化
        BackupStore.kt          覆寫前的前一版密文備份
        ConflictWriter.kt       衝突時另存 .conflict-<timestamp>.kdbx
    session/
        LockState.kt            解鎖狀態的封閉類別
        VaultSession.kt         記憶體中 vault key 的生命週期
        AutoLock.kt             背景 / 螢幕關閉 / 閒置逾時的上鎖觸發

app/src/test/java/com/hintvault/            JVM 單元測試（不需裝置）
app/src/androidTest/java/com/hintvault/     Instrumented 測試（需裝置）
```

---

### Task 1: 專案骨架與 vendor 的 KDBX 層

建立可建置的 Android 專案，把 spike 驗證過的 KDBX 層搬進來，並在真機上證明它仍然可用。這項任務把所有建置設定、manifest、vendor 程式碼折疊在一起，因為它們共同構成同一個可驗證的成果：一個能在 Android 上讀寫 KDBX 的 app module。

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/org/linguafranca/pwdb/kdbx/dom/` （自 `spikes/android-kdbx/` 複製 6 個 .java）
- Test: `app/src/androidTest/java/com/hintvault/VendoredKdbxSmokeTest.kt`

**Interfaces:**
- Consumes: 無（第一項任務）
- Produces: 可建置的 `:app` module；`org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper` 可於 app 程式碼中使用

- [ ] **Step 1: 建立 Gradle 專案檔**

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "hint-vault"
include(":app")
```

`build.gradle.kts`（根，留空即可，插件版本宣告在 app）：

```kotlin
// 插件版本宣告於 app/build.gradle.kts
```

`app/build.gradle.kts`：

**AGP 9.x 內建 Kotlin 編譯，不可再套用獨立的 `org.jetbrains.kotlin.android` plugin。** 實測 2.1.0 / 2.2.0 / 2.3.0 / 2.4.10 全部失敗：舊版撞 extension 衝突，新版被 AGP 直接以「不再需要，請移除」拒絕。因此下方沒有 Kotlin plugin，也沒有 `kotlinOptions` 區塊；Kotlin 原始碼由 AGP 自行編譯（已於真機驗證 Kotlin 測試可編譯執行）。

```kotlin
plugins {
    id("com.android.application") version "9.3.1"
}

android {
    namespace = "com.hintvault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hintvault"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
```

- [ ] **Step 2: 建立 manifest**

`app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="Hint Vault"
        android:allowBackup="false" />
</manifest>
```

- [ ] **Step 3: 搬入 vendor 的 dom 模組**

```bash
mkdir -p app/src/main/java/org/linguafranca/pwdb/kdbx/dom
cp spikes/android-kdbx/app/src/main/java/org/linguafranca/pwdb/kdbx/dom/*.java \
   app/src/main/java/org/linguafranca/pwdb/kdbx/dom/
grep -n "PATCH (Android)" app/src/main/java/org/linguafranca/pwdb/kdbx/dom/DomSerializableDatabase.java
```

最後一行必須有輸出。若無，表示複製到的是未修補的版本，Android 上寫入會失敗。

- [ ] **Step 4: 建立 Gradle wrapper**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
gradle wrapper --gradle-version 9.7.0
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

- [ ] **Step 5: 寫冒煙測試**

`app/src/androidTest/java/com/hintvault/VendoredKdbxSmokeTest.kt`：

```kotlin
package com.hintvault

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxHeader
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.security.Argon2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class VendoredKdbxSmokeTest {

    /**
     * 上游 DomSerializableDatabase.save() 在 Android 上會擲出
     * "Cannot copy node of type 9"。本測試會在 vendor 的修補遺失時失敗。
     */
    @Test
    fun writesAndReadsKdbx4OnDevice() {
        val header = KdbxHeader(4).apply {
            keyDerivationFunction = Argon2.getInstance()
            kdfParameters.putULong("M", 16L * 1024 * 1024)  // 測試用小參數，求快
            kdfParameters.putULong("I", 2)
            kdfParameters.putUInt("P", 2)
        }
        val pw = "smoke".toByteArray()

        val db = DomDatabaseWrapper()
        val entry = db.newEntry().apply {
            title = "github.com"
            password = "提示：貓的名字"
        }
        db.rootGroup.addEntry(entry)

        val out = ByteArrayOutputStream()
        db.save(KdbxStreamFormat(header), KdbxCreds(pw), out)

        val reloaded = DomDatabaseWrapper.load(
            KdbxCreds(pw), ByteArrayInputStream(out.toByteArray())
        )
        val got = reloaded.rootGroup.entries[0]
        assertEquals("github.com", got.title)
        assertEquals("提示：貓的名字", got.password)
    }
}
```

- [ ] **Step 6: 在真機上執行測試**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
adb devices    # 必須列出一台裝置
./gradlew :app:connectedDebugAndroidTest
```

預期：BUILD SUCCESSFUL，1 test passed。

若出現 `Cannot copy node of type 9`，表示 Step 3 搬入的是未修補版本，回到 Step 3。

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts app gradle gradlew gradlew.bat .gitignore
git commit -m "feat: Android 專案骨架與 vendor 的 KDBX 層"
```

---

### Task 2: 記憶體衛生工具

規格 §4.5 要求金鑰與主密碼不得以 `String` 承載且用畢歸零。這項工具是後續所有 crypto 程式碼的基礎，先建立並測試。

**Files:**
- Create: `app/src/main/java/com/hintvault/crypto/Secrets.kt`
- Test: `app/src/test/java/com/hintvault/crypto/SecretsTest.kt`

**Interfaces:**
- Consumes: 無
- Produces:
  - `fun ByteArray.wipe()` — 以 0 覆寫全部位元組
  - `fun CharArray.wipe()` — 以 '\u0000' 覆寫全部字元
  - `fun <R> ByteArray.useThenWipe(block: (ByteArray) -> R): R`
  - `fun <R> CharArray.useThenWipe(block: (CharArray) -> R): R`
  - `fun CharArray.toUtf8Bytes(): ByteArray` — 不經過 String 的 UTF-8 編碼

- [ ] **Step 1: 寫失敗的測試**

`app/src/test/java/com/hintvault/crypto/SecretsTest.kt`：

```kotlin
package com.hintvault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SecretsTest {

    @Test
    fun wipeZeroesByteArray() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        bytes.wipe()
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes)
    }

    @Test
    fun wipeZeroesCharArray() {
        val chars = charArrayOf('a', 'b', 'c')
        chars.wipe()
        assertArrayEquals(charArrayOf('\u0000', '\u0000', '\u0000'), chars)
    }

    @Test
    fun useThenWipeReturnsValueAndWipes() {
        val bytes = byteArrayOf(9, 9)
        val size = bytes.useThenWipe { it.size }
        assertEquals(2, size)
        assertArrayEquals(byteArrayOf(0, 0), bytes)
    }

    @Test
    fun useThenWipeWipesEvenOnThrow() {
        val bytes = byteArrayOf(7, 7)
        try {
            bytes.useThenWipe<Unit> { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(byteArrayOf(0, 0), bytes)
    }

    @Test
    fun toUtf8BytesEncodesMultibyteCorrectly() {
        val chars = "提示".toCharArray()
        assertArrayEquals("提示".toByteArray(Charsets.UTF_8), chars.toUtf8Bytes())
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.SecretsTest"
```

預期：編譯失敗，找不到 `wipe`。

- [ ] **Step 3: 寫實作**

`app/src/main/java/com/hintvault/crypto/Secrets.kt`：

```kotlin
package com.hintvault.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 以 0 覆寫。用於金鑰與其他機密位元組。 */
fun ByteArray.wipe() = fill(0)

/** 以 NUL 覆寫。用於主密碼。 */
fun CharArray.wipe() = fill('\u0000')

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
 *
 * 錯誤處理明確設為 `REPLACE`：低階 `CharsetEncoder` 預設為 `REPORT`，遇到
 * 落單的代理字元會**靜默截斷**而不擲出例外，提示內容會被無聲損毀。
 * `String.toByteArray()` 走的是 `REPLACE`，測試也以它為基準比對，
 * 明確設定可讓本函式成為其等價替代。
 */
fun CharArray.toUtf8Bytes(): ByteArray {
    val out = ByteBuffer.allocate(size * 3)
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    encoder.encode(CharBuffer.wrap(this), out, true)
    encoder.flush(out)

    val bytes = ByteArray(out.position())
    out.flip()
    out.get(bytes)
    out.array().fill(0)
    return bytes
}
```

- [ ] **Step 4: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.SecretsTest"
```

預期：5 tests passed。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/crypto/Secrets.kt \
        app/src/test/java/com/hintvault/crypto/SecretsTest.kt
git commit -m "feat(crypto): 機密資料的歸零工具"
```

---

### Task 3: 領域模型與 history 編解碼

規格 §5.1。history 以 KDBX 自訂字串欄位承載，編解碼是純邏輯，可在 JVM 上完整測試，先做完再碰 KDBX。

**Files:**
- Create: `app/src/main/java/com/hintvault/vault/Entry.kt`
- Create: `app/src/main/java/com/hintvault/vault/HistoryCodec.kt`
- Test: `app/src/test/java/com/hintvault/vault/HistoryCodecTest.kt`

**Interfaces:**
- Consumes: 無
- Produces:
  - `data class HistoricalHint(val at: Instant, val hint: String)`
  - `data class Entry(val id: UUID, val title: String, val hint: String, val modifiedAt: Instant, val history: List<HistoricalHint>)`
  - `object HistoryCodec { fun encode(history: List<HistoricalHint>): Map<String, String>; fun decode(properties: Map<String, String>): List<HistoricalHint>; const val MAX_ENTRIES: Int }`

**設計決定（規格未指定，於此定案）：** history 上限為 20 筆，超出時捨棄最舊者。理由：規格未設上限，但自訂欄位會使檔案無限成長；20 筆遠超過實際救援所需。索引 1 為最近的一筆舊提示，索引遞增代表越舊。

- [ ] **Step 1: 寫失敗的測試**

`app/src/test/java/com/hintvault/vault/HistoryCodecTest.kt`：

```kotlin
package com.hintvault.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HistoryCodecTest {

    private val older = HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), "國中同學的綽號")
    private val newer = HistoricalHint(Instant.parse("2026-06-20T08:30:00Z"), "第一台電腦型號")

    @Test
    fun encodesNewestFirst() {
        val props = HistoryCodec.encode(listOf(older, newer))
        assertEquals("2026-06-20T08:30:00Z", props["hist.1.at"])
        assertEquals("第一台電腦型號", props["hist.1.hint"])
        assertEquals("2026-01-04T10:00:00Z", props["hist.2.at"])
        assertEquals("國中同學的綽號", props["hist.2.hint"])
    }

    @Test
    fun decodeReversesEncode() {
        val original = listOf(newer, older)
        assertEquals(original, HistoryCodec.decode(HistoryCodec.encode(original)))
    }

    @Test
    fun decodeIgnoresUnrelatedProperties() {
        val props = mapOf(
            "Title" to "github.com",
            "Password" to "目前的提示",
            "hist.1.at" to "2026-06-20T08:30:00Z",
            "hist.1.hint" to "第一台電腦型號"
        )
        assertEquals(listOf(newer), HistoryCodec.decode(props))
    }

    @Test
    fun decodeSkipsIncompletePairs() {
        // 只有 at 沒有 hint，或反之，都不成一筆
        val props = mapOf(
            "hist.1.at" to "2026-06-20T08:30:00Z",
            "hist.2.hint" to "沒有時間戳"
        )
        assertTrue(HistoryCodec.decode(props).isEmpty())
    }

    @Test
    fun decodeSkipsUnparseableTimestamp() {
        val props = mapOf("hist.1.at" to "not-a-timestamp", "hist.1.hint" to "x")
        assertTrue(HistoryCodec.decode(props).isEmpty())
    }

    @Test
    fun encodeTruncatesToMaxEntriesKeepingNewest() {
        val many = (1..30).map {
            HistoricalHint(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(it.toLong()), "hint$it")
        }
        val props = HistoryCodec.encode(many)
        assertEquals(HistoryCodec.MAX_ENTRIES * 2, props.size)
        assertEquals("hint30", props["hist.1.hint"])          // 最新的留下
        assertEquals(null, props["hist.${HistoryCodec.MAX_ENTRIES + 1}.hint"])
    }

    @Test
    fun encodeEmptyHistoryProducesNoProperties() {
        assertTrue(HistoryCodec.encode(emptyList()).isEmpty())
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.vault.HistoryCodecTest"
```

預期：編譯失敗，找不到 `HistoricalHint`。

- [ ] **Step 3: 寫實作**

`app/src/main/java/com/hintvault/vault/Entry.kt`：

```kotlin
package com.hintvault.vault

import java.time.Instant
import java.util.UUID

/** 某筆記錄先前使用過的提示。改了密碼但某裝置還是舊密碼時，靠這個救援。 */
data class HistoricalHint(val at: Instant, val hint: String) {
    /**
     * 必須覆寫。data class 生成的 toString() 會輸出所有欄位，而這個型別會傳遍
     * vault / storage / session / ui 每一層——任何一句 `Log.e(TAG, "$h")`
     * 或崩潰回報工具自動擷取物件狀態，提示內容就外洩了。
     */
    override fun toString(): String = "HistoricalHint(at=$at, hint=<redacted>)"
}

/**
 * 一筆提示記錄。
 *
 * 刻意沒有「帳號」欄位——需要的話寫在 [title] 裡。
 * [hint] 對應 KDBX 的 Password 欄位，因為那是 KDBX 預設受 inner stream 保護的欄位。
 */
data class Entry(
    val id: UUID,
    val title: String,
    val hint: String,
    val modifiedAt: Instant,
    val history: List<HistoricalHint> = emptyList()
) {
    /** 同 [HistoricalHint]：不讓提示內容進入任何字串化路徑。 */
    override fun toString(): String =
        "Entry(id=$id, title=$title, hint=<redacted>, modifiedAt=$modifiedAt, history=${history.size} 筆)"
}
```

`app/src/main/java/com/hintvault/vault/HistoryCodec.kt`：

```kotlin
package com.hintvault.vault

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * history 與 KDBX 自訂字串欄位之間的轉換。
 *
 * KDBX 原生有 <History> 元素，但 KeePassJava2 沒有實作，且底層 Element 為
 * package-private 無法從外部操作。因此改用自訂欄位：
 *
 *     hist.1.at   = 2026-06-20T08:30:00Z
 *     hist.1.hint = 第一台電腦型號
 *
 * 索引 1 為最近的一筆舊提示，遞增代表越舊。KeePassXC 會把這些顯示為
 * entry 的「額外屬性」。
 */
object HistoryCodec {

    /** 超過此數量時捨棄最舊的。避免自訂欄位讓檔案無限成長。 */
    const val MAX_ENTRIES = 20

    private const val PREFIX = "hist."
    private val KEY_PATTERN = Regex("""^hist\.(\d+)\.(at|hint)$""")

    fun encode(history: List<HistoricalHint>): Map<String, String> =
        history.sortedByDescending { it.at }
            .take(MAX_ENTRIES)
            .withIndex()
            .flatMap { (i, h) ->
                listOf(
                    "$PREFIX${i + 1}.at" to h.at.toString(),
                    "$PREFIX${i + 1}.hint" to h.hint
                )
            }
            .toMap()

    fun decode(properties: Map<String, String>): List<HistoricalHint> {
        val byIndex = mutableMapOf<Int, MutableMap<String, String>>()
        for ((key, value) in properties) {
            val match = KEY_PATTERN.matchEntire(key) ?: continue
            val index = match.groupValues[1].toIntOrNull() ?: continue
            byIndex.getOrPut(index) { mutableMapOf() }[match.groupValues[2]] = value
        }
        return byIndex.entries
            .sortedBy { it.key }
            .mapNotNull { (_, parts) ->
                val at = parts["at"] ?: return@mapNotNull null
                val hint = parts["hint"] ?: return@mapNotNull null
                val instant = try {
                    Instant.parse(at)
                } catch (_: DateTimeParseException) {
                    return@mapNotNull null
                }
                HistoricalHint(instant, hint)
            }
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.vault.HistoryCodecTest"
```

預期：7 tests passed。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/vault app/src/test/java/com/hintvault/vault
git commit -m "feat(vault): Entry 領域模型與 history 編解碼"
```

---

### Task 4: KDF 參數與耗時校準

規格 §4.2。預設 32 MiB / I=2 在 Galaxy S25 上端到端約 2.2 秒；較慢的裝置需要下調。校準的**決策邏輯**要能在不實際跑 Argon2 的情況下測試，因此把量測函式作為參數注入。

**Files:**
- Create: `app/src/main/java/com/hintvault/crypto/KdfParams.kt`
- Test: `app/src/test/java/com/hintvault/crypto/KdfParamsTest.kt`

**Interfaces:**
- Consumes: 無
- Produces:
  - `data class KdfParams(val memoryMib: Long, val iterations: Long, val parallelism: Int)`
  - `KdfParams.DEFAULT` = `KdfParams(32, 2, 2)`
  - `KdfParams.CALIBRATION_LADDER: List<KdfParams>` — 由強到弱
  - `KdfParams.MAX_ACCEPTABLE_MILLIS` = `3000L`
  - `fun calibrate(measure: (KdfParams) -> Long): KdfParams`

- [ ] **Step 1: 寫失敗的測試**

`app/src/test/java/com/hintvault/crypto/KdfParamsTest.kt`：

```kotlin
package com.hintvault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KdfParamsTest {

    @Test
    fun defaultMatchesSpec() {
        assertEquals(32L, KdfParams.DEFAULT.memoryMib)
        assertEquals(2L, KdfParams.DEFAULT.iterations)
        assertEquals(2, KdfParams.DEFAULT.parallelism)
    }

    @Test
    fun ladderStartsAtDefaultAndWeakensMonotonically() {
        val ladder = KdfParams.CALIBRATION_LADDER
        assertEquals(KdfParams.DEFAULT, ladder.first())
        val costs = ladder.map { it.memoryMib * it.iterations }
        assertEquals(costs.sortedDescending(), costs)
    }

    @Test
    fun calibrateKeepsDefaultWhenFastEnough() {
        // 每一級都很快 → 應該用最強的
        val chosen = KdfParams.calibrate { 500L }
        assertEquals(KdfParams.DEFAULT, chosen)
    }

    @Test
    fun calibrateStepsDownWhenDefaultTooSlow() {
        // 只有最弱的一級在預算內
        val weakest = KdfParams.CALIBRATION_LADDER.last()
        val chosen = KdfParams.calibrate { params ->
            if (params == weakest) 1000L else 9000L
        }
        assertEquals(weakest, chosen)
    }

    @Test
    fun calibrateReturnsWeakestWhenEverythingTooSlow() {
        // 極慢的裝置：仍須回傳可用值，不能擲出例外
        val chosen = KdfParams.calibrate { 60_000L }
        assertEquals(KdfParams.CALIBRATION_LADDER.last(), chosen)
    }

    @Test
    fun calibrateStopsMeasuringOnceAcceptable() {
        val measured = mutableListOf<KdfParams>()
        KdfParams.calibrate { params -> measured.add(params); 100L }
        assertEquals(1, measured.size)
    }

    @Test
    fun budgetMatchesSpec() {
        assertTrue(KdfParams.MAX_ACCEPTABLE_MILLIS == 3000L)
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.KdfParamsTest"
```

預期：編譯失敗，找不到 `KdfParams`。

- [ ] **Step 3: 寫實作**

`app/src/main/java/com/hintvault/crypto/KdfParams.kt`：

```kotlin
package com.hintvault.crypto

/**
 * Argon2 的成本參數。
 *
 * 注意這裡是 Argon2**d** 而非 Argon2id——KeePassJava2 的 Argon2 實作寫死
 * Argon2Parameters.ARGON2_d，且註冊於 Argon2d 的 UUID。
 *
 * 實測（Galaxy S25 / Android 16）：32 MiB / I=2 端到端約 2.2 秒。
 * 端到端之所以是原始 KDF 時間的兩倍，是因為 KdbxHeader.getTransformedKeyDigest()
 * 沒有快取，存取路徑各會呼叫它 2-3 次。
 */
data class KdfParams(
    val memoryMib: Long,
    val iterations: Long,
    val parallelism: Int
) {
    val memoryBytes: Long get() = memoryMib * 1024 * 1024

    companion object {
        val DEFAULT = KdfParams(memoryMib = 32, iterations = 2, parallelism = 2)

        /** 建立資料庫時允許的最長耗時。超過就往下一級退——但不會退破 [FLOOR]。 */
        const val MAX_ACCEPTABLE_MILLIS = 3000L

        /**
         * 階梯的最弱一級，也是安全下限。
         *
         * 不再往下退。Argon2 會把記憶體分給各 lane，16 MiB / p=2 等於每 lane 8 MiB；
         * 再低就明顯低於 OWASP 對 Argon2 的最低建議，而 KDF 正是離線暴力破解的
         * 唯一屏障。本 app 存的是提示，提示外洩會大幅縮小密碼搜尋空間，
         * 因此這裡選擇「慢一點」而非「弱一點」。
         */
        val FLOOR = KdfParams(memoryMib = 16, iterations = 2, parallelism = 2)

        /** 由強到弱。第一個在預算內的即為所選。 */
        val CALIBRATION_LADDER = listOf(
            DEFAULT,
            KdfParams(memoryMib = 24, iterations = 2, parallelism = 2),
            FLOOR
        )

        /**
         * 挑出第一個耗時在 [MAX_ACCEPTABLE_MILLIS] 內的參數組合。
         *
         * [measure] 應實際以該組參數跑一次 KDF 並回傳毫秒數。
         *
         * 若連 [FLOOR] 都超過預算（極慢的裝置），仍回傳 [FLOOR]——寧可解鎖慢，
         * 也不能讓使用者無法建立資料庫，更不能為了速度把 KDF 削到不安全。
         * 呼叫端應在此情況下告知使用者解鎖會較慢，用 [exceedsBudget] 判斷。
         */
        fun calibrate(measure: (KdfParams) -> Long): KdfParams {
            for (params in CALIBRATION_LADDER) {
                if (measure(params) <= MAX_ACCEPTABLE_MILLIS) return params
            }
            return FLOOR
        }

        /** 這組參數在本機的實測耗時是否超出預算，供 UI 決定要不要提醒使用者。 */
        fun exceedsBudget(measuredMillis: Long): Boolean =
            measuredMillis > MAX_ACCEPTABLE_MILLIS
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.KdfParamsTest"
```

預期：7 tests passed。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/crypto/KdfParams.kt \
        app/src/test/java/com/hintvault/crypto/KdfParamsTest.kt
git commit -m "feat(crypto): Argon2 參數與耗時校準"
```

---

### Task 5: Vault — KDBX 讀寫

規格 §5.1、§4.3。這是 `vault` 層的核心。關鍵設計：`Vault` 持有底層的 `DomDatabaseWrapper` 不放，讓 KeePassXC 寫入的未知內容在「載入 → 修改 → 存檔」之後仍然保留。

**更正**：本節原本寫「spike 已驗證此性質」，這是錯的。spike 驗證的是「本 app 寫的檔 → KeePassXC 修改 → 本 app 讀回」，以及本 app 自己寫的自訂欄位在重存後仍在。它**從未**驗證「KeePassXC 寫的檔 → 本 app 修改並存檔 → 對方的內容是否倖存」——而那正是這裡宣稱的性質。因為所有測試都從 `Vault.create` 出發，一個「解析成領域物件再重建文件」的改寫會通過全部測試而不被察覺。此性質改由 Task 5 的 `preservesContentThisAppDoesNotModelAcrossLoadModifySave` 測試把關，使用真實的 KeePassXC fixture。

這些測試可在 JVM 上跑，因為 vendor 的 dom 只用到 `javax.xml`，JVM 與 Android 皆有。

**Files:**
- Create: `app/src/main/java/com/hintvault/vault/Vault.kt`
- Test: `app/src/test/java/com/hintvault/vault/VaultTest.kt`

**Interfaces:**
- Consumes: `Entry`、`HistoricalHint`、`HistoryCodec`（Task 3）；`KdfParams`（Task 4）；`CharArray.toUtf8Bytes()`、`ByteArray.useThenWipe`（Task 2）
- Produces:
  - `class Vault`
  - `Vault.create(kdf: KdfParams = KdfParams.DEFAULT): Vault` — 建立空資料庫。主密碼於 `serialize` 時才需要，故不在此處要求。
  - `Vault.open(bytes: ByteArray, masterPassword: CharArray): Vault`
  - `fun entries(): List<Entry>`
  - `fun upsert(entry: Entry)`
  - `fun delete(id: UUID): Boolean`
  - `fun serialize(masterPassword: CharArray): ByteArray`
  - `class WrongPasswordException : Exception`

- [ ] **Step 1: 寫失敗的測試**

`app/src/test/java/com/hintvault/vault/VaultTest.kt`：

```kotlin
package com.hintvault.vault

import com.hintvault.crypto.KdfParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class VaultTest {

    // 單元測試用最小成本，只求功能正確；正式預設值由 KdfParams.DEFAULT 決定
    private val fastKdf = KdfParams(memoryMib = 8, iterations = 2, parallelism = 2)

    private fun pw() = "correct horse".toCharArray()

    private fun sampleEntry(title: String = "github.com") = Entry(
        id = UUID.randomUUID(),
        title = title,
        hint = "提示：貓的名字 + 生日",
        modifiedAt = Instant.parse("2026-08-13T00:00:00Z"),
        history = listOf(
            HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), "舊提示：國中同學的綽號")
        )
    )

    @Test
    fun createProducesEmptyVault() {
        assertTrue(Vault.create(fastKdf).entries().isEmpty())
    }

    @Test
    fun upsertThenSerializeThenOpenRoundTrips() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry()
        vault.upsert(entry)

        val bytes = vault.serialize(pw())
        val reopened = Vault.open(bytes, pw())
        val got = reopened.entries().single()

        assertEquals(entry.id, got.id)
        assertEquals(entry.title, got.title)
        assertEquals(entry.hint, got.hint)
        assertEquals(entry.history, got.history)
    }

    @Test
    fun serializedFileIsKdbx4() {
        val bytes = Vault.create(fastKdf).serialize(pw())
        // KDBX 簽章 9AA2D903 B54BFB67（little-endian 寫入）
        assertEquals(0x03.toByte(), bytes[0])
        assertEquals(0xD9.toByte(), bytes[1])
        val major = (bytes[10].toInt() and 0xFF) or ((bytes[11].toInt() and 0xFF) shl 8)
        assertEquals(4, major)
    }

    @Test
    fun wrongPasswordThrowsWrongPasswordException() {
        val bytes = Vault.create(fastKdf).serialize(pw())
        try {
            Vault.open(bytes, "wrong".toCharArray())
            throw AssertionError("應該擲出 WrongPasswordException")
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun upsertWithExistingIdReplacesRatherThanDuplicates() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry()
        vault.upsert(entry)
        vault.upsert(entry.copy(hint = "改過的提示"))

        assertEquals(1, vault.entries().size)
        assertEquals("改過的提示", vault.entries().single().hint)
    }

    @Test
    fun deleteRemovesEntry() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry()
        vault.upsert(entry)

        assertTrue(vault.delete(entry.id))
        assertTrue(vault.entries().isEmpty())
    }

    @Test
    fun deleteReturnsFalseForUnknownId() {
        assertTrue(!Vault.create(fastKdf).delete(UUID.randomUUID()))
    }

    @Test
    fun hintIsStoredInPasswordFieldNotNotes() {
        // 規格 §5.1：提示要放進受 inner stream 保護的 Password 欄位
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry())
        val reopened = Vault.open(vault.serialize(pw()), pw())
        val raw = reopened.rawEntryProperties(reopened.entries().single().id)

        assertEquals("提示：貓的名字 + 生日", raw["Password"])
        assertTrue(raw["Notes"].isNullOrEmpty())
    }

    @Test
    fun historyIsStoredAsCustomProperties() {
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry())
        val reopened = Vault.open(vault.serialize(pw()), pw())
        val raw = reopened.rawEntryProperties(reopened.entries().single().id)

        assertEquals("2026-01-04T10:00:00Z", raw["hist.1.at"])
        assertEquals("舊提示：國中同學的綽號", raw["hist.1.hint"])
    }

    @Test
    fun multipleEntriesAreAllPreserved() {
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry("github.com"))
        vault.upsert(sampleEntry("gmail.com"))

        val titles = Vault.open(vault.serialize(pw()), pw()).entries().map { it.title }
        assertEquals(setOf("github.com", "gmail.com"), titles.toSet())
    }

    @Test
    fun reducingHistoryRemovesStaleCustomProperties() {
        // 從 2 筆 history 減成 1 筆時，hist.2.* 必須消失，
        // 否則舊欄位會殘留成幽靈資料
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry().copy(
            history = listOf(
                HistoricalHint(Instant.parse("2026-06-20T08:30:00Z"), "較新"),
                HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), "較舊")
            )
        )
        vault.upsert(entry)
        vault.upsert(entry.copy(history = listOf(entry.history.first())))

        val raw = vault.rawEntryProperties(entry.id)
        assertEquals("較新", raw["hist.1.hint"])
        assertNull(raw["hist.2.hint"])
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.vault.VaultTest"
```

預期：編譯失敗，找不到 `Vault`。

- [ ] **Step 3: 寫實作**

`app/src/main/java/com/hintvault/vault/Vault.kt`：

```kotlin
package com.hintvault.vault

import com.hintvault.crypto.KdfParams
import com.hintvault.crypto.toUtf8Bytes
import com.hintvault.crypto.useThenWipe
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxHeader
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.kdbx.dom.DomEntryWrapper
import org.linguafranca.pwdb.security.Argon2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/** 主密碼錯誤，或檔案損毀 / 非 KDBX。 */
class WrongPasswordException(cause: Throwable? = null) :
    Exception("無法以此主密碼開啟資料庫", cause)

/**
 * 一個已開啟的 KDBX 資料庫。
 *
 * 刻意持有底層的 [DomDatabaseWrapper] 不放，而不是解析成領域物件後丟棄：
 * DOM 實作會保留整份 XML，因此 KeePassXC 寫入而本 app 不認識的內容
 * （群組結構、圖示、其他欄位）在「載入 → 修改 → 存檔」後仍然存在。
 * 若改成重建文件，那些資料會被靜默丟棄。
 */
class Vault private constructor(
    private val db: DomDatabaseWrapper,
    private val kdf: KdfParams
) {

    companion object {
        private const val PROP_TITLE = "Title"
        private const val PROP_PASSWORD = "Password"

        /** 主密碼不在此處要求——它只有在 [serialize] 寫出位元組時才用得到。 */
        fun create(kdf: KdfParams = KdfParams.DEFAULT): Vault =
            Vault(DomDatabaseWrapper(), kdf)

        fun open(bytes: ByteArray, masterPassword: CharArray): Vault {
            val creds = masterPassword.toUtf8Bytes().useThenWipe { KdbxCreds(it) }
            val db = try {
                DomDatabaseWrapper.load(creds, ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                // 函式庫對錯誤密碼擲出 IllegalStateException，對損毀檔案擲出各種例外。
                // 一律轉成同一個型別，避免呼叫端得知何者為何——那是給攻擊者的資訊。
                throw WrongPasswordException(e)
            }
            return Vault(db, KdfParams.DEFAULT)
        }
    }

    fun entries(): List<Entry> = db.findEntries { true }.map { it.toDomainEntry() }

    fun upsert(entry: Entry) {
        val existing = findRaw(entry.id)
        val target = existing ?: db.newEntry().also { db.rootGroup.addEntry(it) }

        if (existing == null) {
            // 新 entry：讓 KDBX 的 UUID 與領域模型一致，之後才找得回來
            target.setProperty(PROP_UUID_MARKER, entry.id.toString())
        }
        target.setProperty(PROP_TITLE, entry.title)
        target.setProperty(PROP_PASSWORD, entry.hint)

        // 先清掉所有既有的 hist.* 欄位，再寫入新的。
        // 少了這一步，history 變短時舊欄位會殘留成幽靈資料。
        target.propertyNames.filter { it.startsWith("hist.") }
            .forEach { target.removeProperty(it) }
        HistoryCodec.encode(entry.history).forEach { (k, v) -> target.setProperty(k, v) }
    }

    fun delete(id: UUID): Boolean {
        val raw = findRaw(id) ?: return false
        raw.parent.removeEntry(raw)
        return true
    }

    fun serialize(masterPassword: CharArray): ByteArray {
        val header = KdbxHeader(4).apply {
            keyDerivationFunction = Argon2.getInstance()
            kdfParameters.putULong("M", kdf.memoryBytes)
            kdfParameters.putULong("I", kdf.iterations)
            kdfParameters.putUInt("P", kdf.parallelism)
        }
        val out = ByteArrayOutputStream()
        masterPassword.toUtf8Bytes().useThenWipe { pwBytes ->
            db.save(KdbxStreamFormat(header), KdbxCreds(pwBytes), out)
        }
        return out.toByteArray()
    }

    /** 測試用：檢視某筆記錄底層的原始 KDBX 欄位。 */
    internal fun rawEntryProperties(id: UUID): Map<String, String> {
        val raw = findRaw(id) ?: return emptyMap()
        return raw.propertyNames.associateWith { raw.getProperty(it) ?: "" }
    }

    private fun findRaw(id: UUID): DomEntryWrapper? =
        db.findEntries { it.getProperty(PROP_UUID_MARKER) == id.toString() }.firstOrNull()

    private fun DomEntryWrapper.toDomainEntry(): Entry {
        val props = propertyNames.associateWith { getProperty(it) ?: "" }
        return Entry(
            id = UUID.fromString(props[PROP_UUID_MARKER] ?: uuid.toString()),
            title = props[PROP_TITLE].orEmpty(),
            hint = props[PROP_PASSWORD].orEmpty(),
            modifiedAt = lastModificationTime?.toInstant() ?: Instant.EPOCH,
            history = HistoryCodec.decode(props)
        )
    }
}

/**
 * 領域模型的 UUID 存成自訂欄位而非沿用 KDBX 自己的 UUID。
 * 理由：KeePassJava2 的 newEntry() 不允許指定 UUID，而領域層需要穩定的識別碼。
 */
private const val PROP_UUID_MARKER = "hv.id"
```

- [ ] **Step 4: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.vault.VaultTest"
```

預期：11 tests passed。

若 `lastModificationTime` 或 `removeEntry` 的簽章與此處不符，以 `spikes/android-kdbx/app/src/main/java/org/linguafranca/pwdb/kdbx/dom/DomEntryWrapper.java` 和 `DomGroupWrapper.java` 的實際 API 為準修正。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/vault/Vault.kt \
        app/src/test/java/com/hintvault/vault/VaultTest.kt
git commit -m "feat(vault): KDBX 讀寫與領域模型轉換"
```

---

### Task 6: 檔案指紋與變動偵測

規格 §5.3。純邏輯，先於任何 SAF 程式碼完成，因為衝突判斷的正確性遠比檔案 I/O 難驗證。

**Files:**
- Create: `app/src/main/java/com/hintvault/storage/FileStamp.kt`
- Test: `app/src/test/java/com/hintvault/storage/FileStampTest.kt`

**Interfaces:**
- Consumes: 無
- Produces:
  - `data class FileStamp(val lastModified: Long, val sha256: String)`
  - `FileStamp.of(bytes: ByteArray, lastModified: Long): FileStamp`
  - `fun FileStamp.differsFrom(other: FileStamp): Boolean`

- [ ] **Step 1: 寫失敗的測試**

`app/src/test/java/com/hintvault/storage/FileStampTest.kt`：

```kotlin
package com.hintvault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStampTest {

    @Test
    fun identicalBytesAndTimeAreEqual() {
        val a = FileStamp.of("hello".toByteArray(), 1000L)
        val b = FileStamp.of("hello".toByteArray(), 1000L)
        assertEquals(a, b)
        assertFalse(a.differsFrom(b))
    }

    @Test
    fun differentContentDiffers() {
        val a = FileStamp.of("hello".toByteArray(), 1000L)
        val b = FileStamp.of("world".toByteArray(), 1000L)
        assertTrue(a.differsFrom(b))
    }

    @Test
    fun differentMtimeDiffersEvenWithSameContent() {
        // 同步工具可能重寫出位元組相同但時間不同的檔案。
        // 保守起見視為已變動——寧可多問一次，也不要覆蓋別人的改動。
        val a = FileStamp.of("hello".toByteArray(), 1000L)
        val b = FileStamp.of("hello".toByteArray(), 2000L)
        assertTrue(a.differsFrom(b))
    }

    @Test
    fun sha256IsLowercaseHexOf64Chars() {
        val stamp = FileStamp.of("hello".toByteArray(), 0L)
        assertEquals(64, stamp.sha256.length)
        assertTrue(stamp.sha256.all { it in "0123456789abcdef" })
    }

    @Test
    fun sha256MatchesKnownVector() {
        // echo -n "abc" | shasum -a 256
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileStamp.of("abc".toByteArray(), 0L).sha256
        )
    }

    @Test
    fun emptyContentIsStampable() {
        assertEquals(64, FileStamp.of(ByteArray(0), 0L).sha256.length)
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.storage.FileStampTest"
```

預期：編譯失敗，找不到 `FileStamp`。

- [ ] **Step 3: 寫實作**

`app/src/main/java/com/hintvault/storage/FileStamp.kt`：

```kotlin
package com.hintvault.storage

import java.security.MessageDigest

/**
 * 資料庫檔在某一時刻的身分。
 *
 * 載入時記錄，存檔前重新比對。不一致就代表外部（同步工具、桌面版 KeePassXC）
 * 動過這個檔，此時絕不覆寫。
 */
data class FileStamp(val lastModified: Long, val sha256: String) {

    /**
     * 內容或時間任一不同即視為已變動。
     *
     * 時間不同但內容相同也算變動：同步工具重寫檔案時可能產生這種情況，
     * 而在「絕不覆蓋他人改動」這個目標下，多問一次的代價遠低於漏判。
     */
    fun differsFrom(other: FileStamp): Boolean = this != other

    companion object {
        fun of(bytes: ByteArray, lastModified: Long): FileStamp =
            FileStamp(
                lastModified = lastModified,
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            )
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.storage.FileStampTest"
```

預期：6 tests passed。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/storage/FileStamp.kt \
        app/src/test/java/com/hintvault/storage/FileStampTest.kt
git commit -m "feat(storage): 檔案指紋與變動偵測"
```

---

### Task 7: 存檔策略 — 備份、衝突偵測、絕不覆蓋

規格 §5.3。這是整個 storage 層風險最高的邏輯：判斷錯誤會**靜默丟失資料**。因此把它與 SAF 的檔案 I/O 徹底分開，用假的 `VaultFile` 在 JVM 上窮舉各種情境，不依賴裝置。

**Files:**
- Create: `app/src/main/java/com/hintvault/storage/VaultFile.kt`
- Create: `app/src/main/java/com/hintvault/storage/BackupStore.kt`
- Create: `app/src/main/java/com/hintvault/storage/VaultRepository.kt`
- Test: `app/src/test/java/com/hintvault/storage/VaultRepositoryTest.kt`

**Interfaces:**
- Consumes: `FileStamp`（Task 6）
- Produces:
  - `interface VaultFile { val displayName: String; fun read(): ByteArray; fun write(bytes: ByteArray); fun lastModified(): Long; fun writeSibling(suffix: String, bytes: ByteArray): String }`
  - `interface BackupStore { fun put(bytes: ByteArray); fun get(): ByteArray? }`
  - `class VaultRepository(file: VaultFile, backups: BackupStore)`
  - `fun load(): LoadedVault` 其中 `data class LoadedVault(val bytes: ByteArray, val stamp: FileStamp)`
  - `fun save(bytes: ByteArray, loadedStamp: FileStamp): SaveResult`
  - `sealed interface SaveResult { data class Saved(val newStamp: FileStamp); data class Conflict(val conflictFileName: String) }`

- [ ] **Step 1: 寫失敗的測試**

`app/src/test/java/com/hintvault/storage/VaultRepositoryTest.kt`：

```kotlin
package com.hintvault.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRepositoryTest {

    /** 記憶體中的假檔案，模擬外部同步工具的干擾。 */
    private class FakeVaultFile(
        override val displayName: String = "vault.kdbx",
        var content: ByteArray = ByteArray(0),
        var mtime: Long = 1000L
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

        /** 模擬同步工具在背後換掉了檔案。 */
        fun externallyReplacedWith(bytes: ByteArray) {
            content = bytes
            mtime += 500
        }
    }

    private class FakeBackupStore : BackupStore {
        private var stored: ByteArray? = null
        override fun put(bytes: ByteArray) { stored = bytes }
        override fun get() = stored
    }

    @Test
    fun loadReturnsBytesAndStamp() {
        val file = FakeVaultFile(content = "v1".toByteArray(), mtime = 1234L)
        val loaded = VaultRepository(file, FakeBackupStore()).load()

        assertArrayEquals("v1".toByteArray(), loaded.bytes)
        assertEquals(FileStamp.of("v1".toByteArray(), 1234L), loaded.stamp)
    }

    @Test
    fun saveWritesWhenFileUnchanged() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        val result = repo.save("v2".toByteArray(), loaded.stamp)

        assertTrue(result is SaveResult.Saved)
        assertArrayEquals("v2".toByteArray(), file.content)
    }

    @Test
    fun saveRefusesToOverwriteExternalChange() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        file.externallyReplacedWith("changed-by-desktop".toByteArray())
        val result = repo.save("v2".toByteArray(), loaded.stamp)

        assertTrue(result is SaveResult.Conflict)
        // 外部版本必須完好無損
        assertArrayEquals("changed-by-desktop".toByteArray(), file.content)
    }

    @Test
    fun conflictWritesLocalVersionToSiblingFile() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        file.externallyReplacedWith("changed".toByteArray())
        val result = repo.save("my-local-work".toByteArray(), loaded.stamp) as SaveResult.Conflict

        assertArrayEquals("my-local-work".toByteArray(), file.siblings[result.conflictFileName])
    }

    @Test
    fun conflictFileNameCarriesConflictMarkerAndKdbxExtension() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        file.externallyReplacedWith("changed".toByteArray())
        val result = repo.save("local".toByteArray(), loaded.stamp) as SaveResult.Conflict

        assertTrue(result.conflictFileName.contains(".conflict-"))
        assertTrue(result.conflictFileName.endsWith(".kdbx"))
    }

    @Test
    fun saveBacksUpPreviousContentBeforeOverwriting() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val backups = FakeBackupStore()
        val repo = VaultRepository(file, backups)
        val loaded = repo.load()

        repo.save("v2".toByteArray(), loaded.stamp)

        assertArrayEquals("v1".toByteArray(), backups.get())
    }

    @Test
    fun backupIsTakenBeforeWriteSoFailedWriteIsRecoverable() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val backups = FakeBackupStore()
        val repo = VaultRepository(file, backups)
        val loaded = repo.load()
        file.writeShouldFail = true

        try {
            repo.save("v2".toByteArray(), loaded.stamp)
        } catch (_: java.io.IOException) {
        }

        // 寫入炸掉了，但前一版還救得回來
        assertArrayEquals("v1".toByteArray(), backups.get())
    }

    @Test
    fun savedStampMatchesWhatWasActuallyWritten() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        val result = repo.save("v2".toByteArray(), loaded.stamp) as SaveResult.Saved

        // 回傳的 stamp 必須能直接用於下一次存檔的比對
        assertEquals(FileStamp.of(file.content, file.mtime), result.newStamp)
    }

    @Test
    fun consecutiveSavesSucceedUsingReturnedStamp() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        var stamp = repo.load().stamp

        stamp = (repo.save("v2".toByteArray(), stamp) as SaveResult.Saved).newStamp
        val second = repo.save("v3".toByteArray(), stamp)

        assertTrue(second is SaveResult.Saved)
        assertArrayEquals("v3".toByteArray(), file.content)
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.storage.VaultRepositoryTest"
```

預期：編譯失敗，找不到 `VaultFile`。

- [ ] **Step 3: 寫實作**

`app/src/main/java/com/hintvault/storage/VaultFile.kt`：

```kotlin
package com.hintvault.storage

/**
 * 資料庫檔的位元組進出。
 *
 * 刻意不認識加密內容——這一層只管位元組與「這個檔有沒有被動過」。
 * 抽成介面是為了讓衝突邏輯能在 JVM 上以假實作窮舉測試，
 * 不必依賴 SAF 或實體裝置。
 */
interface VaultFile {
    val displayName: String
    fun read(): ByteArray
    fun write(bytes: ByteArray)
    fun lastModified(): Long

    /** 在同一目錄寫出一個附帶後綴的檔案，回傳實際檔名。用於衝突副本。 */
    fun writeSibling(suffix: String, bytes: ByteArray): String
}
```

`app/src/main/java/com/hintvault/storage/BackupStore.kt`：

```kotlin
package com.hintvault.storage

import java.io.File

/** 覆寫前的前一版密文。SAF 沒有可靠的 temp-then-rename，只能自己留一份。 */
interface BackupStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

/** 存於 app 私有目錄。內容是已加密的密文，因此不需額外保護。 */
class FileBackupStore(private val directory: File) : BackupStore {
    private val backupFile get() = File(directory, "previous.kdbx.bak")

    override fun put(bytes: ByteArray) {
        directory.mkdirs()
        backupFile.writeBytes(bytes)
    }

    override fun get(): ByteArray? =
        backupFile.takeIf { it.exists() }?.readBytes()
}
```

`app/src/main/java/com/hintvault/storage/VaultRepository.kt`：

```kotlin
package com.hintvault.storage

import java.time.Instant

data class LoadedVault(val bytes: ByteArray, val stamp: FileStamp) {
    // ByteArray 在 data class 中是參考相等，需自行實作
    override fun equals(other: Any?): Boolean =
        other is LoadedVault && bytes.contentEquals(other.bytes) && stamp == other.stamp
    override fun hashCode(): Int = 31 * bytes.contentHashCode() + stamp.hashCode()
}

sealed interface SaveResult {
    /** 已寫入。[newStamp] 供下一次存檔比對使用。 */
    data class Saved(val newStamp: FileStamp) : SaveResult
    /** 未寫入，因為檔案被外部改過。本地版本已另存為 [conflictFileName]。 */
    data class Conflict(val conflictFileName: String) : SaveResult
}

/**
 * 存取資料庫檔，並保證一件事：**絕不覆蓋外部的改動**。
 *
 * 不做自動合併。合併錯誤會靜默丟資料，那是此類資料最糟的失敗模式；
 * 交給桌面版 KeePassXC 在大螢幕上處理遠比在手機上自動決定安全。
 */
class VaultRepository(
    private val file: VaultFile,
    private val backups: BackupStore
) {

    fun load(): LoadedVault {
        val bytes = file.read()
        return LoadedVault(bytes, FileStamp.of(bytes, file.lastModified()))
    }

    /**
     * @param loadedStamp 上次 [load]（或上次成功 [save]）時的指紋。
     */
    fun save(bytes: ByteArray, loadedStamp: FileStamp): SaveResult {
        val current = FileStamp.of(file.read(), file.lastModified())
        if (current.differsFrom(loadedStamp)) {
            val name = file.writeSibling(conflictSuffix(), bytes)
            return SaveResult.Conflict(name)
        }

        // 備份必須在寫入之前。寫到一半失敗時，這是唯一的救援路徑。
        backups.put(file.read())
        file.write(bytes)
        return SaveResult.Saved(FileStamp.of(bytes, file.lastModified()))
    }

    private fun conflictSuffix(): String {
        val timestamp = Instant.now().toString()
            .replace(':', '-')
            .substringBefore('.')
        return ".conflict-$timestamp.kdbx"
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.storage.VaultRepositoryTest"
```

預期：9 tests passed。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/storage app/src/test/java/com/hintvault/storage
git commit -m "feat(storage): 存檔策略——備份、衝突偵測、絕不覆蓋"
```

---

### Task 8: SAF 檔案存取

規格 §5.2。這是 Task 7 那個介面的真實實作。風險邏輯都已在 Task 7 測過，這裡只需驗證轉接本身。

**本節已於 Task 8 實作階段修正（2026-08-14）**：原設計用 `ACTION_OPEN_DOCUMENT`（單一檔案），但該授權模式下 `DocumentFile.parentFile` 恆為 `null`——已用 `javap` 反編譯 `androidx.documentfile:documentfile:1.0.1` 位元組碼與真機實測雙重證實，`RawDocumentFile` 與 `SingleDocumentFile` 皆無例外。這代表 `writeSibling`（衝突偵測賴以運作的方法）在真正的 SAF URI 上結構性地不可能實作，不是「可能覆蓋」而是「完全寫不出去」。改為 `ACTION_OPEN_DOCUMENT_TREE` 取得整個資料夾的授權，`DocumentFile.fromTreeUri()` 取得的目錄可正常 `findFile()` / `createFile()`，不依賴 `parentFile`。詳見規格 §5.2 更正段落。

`SafVaultFile` 因此改為接收一個已解析好的目錄 `DocumentFile`，而非自己解析 URI——「怎麼從 URI 拿到目錄」是呼叫端（選檔流程）的責任，`SafVaultFile` 只管在一個已知目錄裡讀寫檔案。這個切分讓測試不需要真的 `content://` tree 授權：`DocumentFile.fromFile(directory)`（真機上一個普通目錄）與 `DocumentFile.fromTreeUri(context, treeUri)`（正式環境的 SAF 樹）都支援 `findFile()` / `createFile()`，測試因此能走完全相同的程式碼路徑而不需使用者互動。

**未涵蓋的部分**：`ACTION_OPEN_DOCUMENT_TREE` 的選檔流程與 `takePersistableUriPermission`，那需要使用者操作，於 Task 11 手動驗證。

**Files:**
- Modify: `app/build.gradle.kts`（加入 `androidx.documentfile`）
- Create: `app/src/main/java/com/hintvault/storage/SafVaultFile.kt`
- Test: `app/src/androidTest/java/com/hintvault/storage/SafVaultFileTest.kt`

**Interfaces:**
- Consumes: `VaultFile`（Task 7）
- Produces:
  - `class SafVaultFile(context: Context, directory: DocumentFile, fileName: String) : VaultFile`
  - `object VaultUriPermissions { fun persist(context: Context, uri: Uri); fun isStillGranted(context: Context, uri: Uri): Boolean }`

- [ ] **Step 1: 加入依賴**

在 `app/build.gradle.kts` 的 `dependencies` 中加入：

```kotlin
    implementation("androidx.documentfile:documentfile:1.0.1")
```

- [ ] **Step 2: 寫失敗的測試**

`app/src/androidTest/java/com/hintvault/storage/SafVaultFileTest.kt`：

```kotlin
package com.hintvault.storage

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SafVaultFileTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var directory: DocumentFile

    @Before
    fun setUp() {
        // 每個測試獨立的目錄，避免上一個測試留下的兄弟檔案互相干擾
        dir = File(context.cacheDir, "saf-test-${System.nanoTime()}").apply { mkdirs() }
        directory = DocumentFile.fromFile(dir)
        File(dir, "saf-test.kdbx").writeBytes("v1".toByteArray())
    }

    private fun subject() = SafVaultFile(context, directory, "saf-test.kdbx")

    @Test
    fun readsBytesThroughContentResolver() {
        assertArrayEquals("v1".toByteArray(), subject().read())
    }

    @Test
    fun writesBytesThroughContentResolver() {
        subject().write("v2-longer".toByteArray())
        assertArrayEquals("v2-longer".toByteArray(), File(dir, "saf-test.kdbx").readBytes())
    }

    @Test
    fun writeTruncatesWhenNewContentIsShorter() {
        // SAF 的 "w" 模式不保證截斷，殘留的尾巴會讓 KDBX 檔損毀
        File(dir, "saf-test.kdbx").writeBytes("a-very-long-previous-version".toByteArray())
        subject().write("short".toByteArray())
        assertArrayEquals("short".toByteArray(), File(dir, "saf-test.kdbx").readBytes())
    }

    @Test
    fun reportsLastModified() {
        assertTrue(subject().lastModified() > 0)
    }

    @Test
    fun writeSiblingCreatesFileNextToOriginal() {
        val name = subject().writeSibling(".conflict-2026.kdbx", "local".toByteArray())
        val sibling = File(dir, name)
        assertTrue(sibling.exists())
        assertArrayEquals("local".toByteArray(), sibling.readBytes())
    }

    @Test
    fun writeSiblingNeverOverwritesAnExistingFile() {
        // Task 7 review 釘住的合約：撞名絕不可覆蓋既有檔案，須回傳實際使用的檔名
        val first = subject().writeSibling(".conflict.kdbx", "first".toByteArray())
        val second = subject().writeSibling(".conflict.kdbx", "second".toByteArray())

        assertNotEquals("撞名時檔名必須不同", first, second)
        assertArrayEquals("first".toByteArray(), File(dir, first).readBytes())
        assertArrayEquals("second".toByteArray(), File(dir, second).readBytes())
    }

    @Test
    fun writeSiblingDoesNotAppendAnUnwantedExtension() {
        // "application/octet-stream" 這個 MIME type 在部分裝置上會被自動加上
        // .bin 副檔名，讓 KeePassXC 認不出這是它的格式
        val name = subject().writeSibling(".conflict-2026.kdbx", "local".toByteArray())
        assertTrue("不該被加上非預期的副檔名，實際檔名是 $name", name.endsWith(".kdbx"))
    }

    @Test
    fun writeSiblingContentIsReadableBackImmediately() {
        // 冒煙測試：writeSibling 寫完的檔案必須真的可讀，不能是空檔案假裝成功
        val name = subject().writeSibling(".conflict-verify.kdbx", "verify-me".toByteArray())
        assertArrayEquals("verify-me".toByteArray(), File(dir, name).readBytes())
    }

    @Test
    fun uniqueNameOnCollisionKeepsTheKdbxExtension() {
        // 撞名時的第二個檔名也必須以 .kdbx 結尾，否則 KeePassXC 認不出來——
        // 這正是 SIBLING_MIME_TYPE 那個修正要保護的性質，不能被去重邏輯自己破壞
        val first = subject().writeSibling(".conflict.kdbx", "first".toByteArray())
        val second = subject().writeSibling(".conflict.kdbx", "second".toByteArray())

        assertTrue("第一個檔名 $first 必須以 .kdbx 結尾", first.endsWith(".kdbx"))
        assertTrue("第二個檔名 $second 也必須以 .kdbx 結尾", second.endsWith(".kdbx"))
    }

    @Test
    fun displayNameIsTheFileName() {
        assertEquals("saf-test.kdbx", subject().displayName)
    }
}
```

- [ ] **Step 3: 執行測試確認失敗**

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hintvault.storage.SafVaultFileTest
```

預期：編譯失敗，找不到 `SafVaultFile`。

- [ ] **Step 4: 寫實作**

`app/src/main/java/com/hintvault/storage/SafVaultFile.kt`：

```kotlin
package com.hintvault.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

/**
 * 透過 SAF 存取資料庫檔，以「已授權的資料夾」為單位操作。
 *
 * 用 SAF 而非 app 私有目錄，是為了讓 Syncthing / Nextcloud 這類同步工具
 * 能碰到同一個檔。不申請 MANAGE_EXTERNAL_STORAGE——權限過大。
 *
 * **為何吃 [directory] 而非自己解析 URI**：`ACTION_OPEN_DOCUMENT`（單一檔案）
 * 建構出的 `DocumentFile.parentFile` 恆為 `null`（`androidx.documentfile:documentfile:1.0.1`
 * 之 `RawDocumentFile` 與 `SingleDocumentFile` 皆然，已用 `javap` 反編譯與真機
 * 雙重證實），因此完全無法建立同目錄的兄弟檔案——[writeSibling] 賴以運作的能力。
 * 呼叫端須改用 `ACTION_OPEN_DOCUMENT_TREE` 取得整個資料夾的授權，用
 * `DocumentFile.fromTreeUri()` 解析成目錄後傳入。此設計也讓測試能用
 * `DocumentFile.fromFile()`（一個普通目錄）走完全相同的程式碼路徑，
 * 不需真的 SAF 授權。
 */
class SafVaultFile(
    private val context: Context,
    private val directory: DocumentFile,
    private val fileName: String
) : VaultFile {

    private val document: DocumentFile
        get() = directory.findFile(fileName)
            ?: throw FileNotFoundException("在資料夾中找不到 $fileName")

    override val displayName: String
        get() = fileName

    override fun read(): ByteArray =
        context.contentResolver.openInputStream(document.uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("無法讀取 $fileName")

    /**
     * 以 "wt" 開啟。純 "w" 在部分 provider 上不會截斷檔案，
     * 新內容較短時會留下舊資料的尾巴，導致 KDBX 檔損毀。
     */
    override fun write(bytes: ByteArray) {
        context.contentResolver.openOutputStream(document.uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("無法寫入 $fileName")
    }

    override fun lastModified(): Long = document.lastModified()

    /**
     * 撞名時的行為不能信任底層 provider——實測 `RawDocumentFile.createFile()`
     * 完全沒有去重邏輯，撞名會直接靜默覆蓋既有內容（`File.createNewFile()` 的
     * 回傳值被忽略）。因此自己在建立前用 [uniqueNameFor] 保證拿到一個沒被
     * 用過的名字，不依賴 provider 的慣例行為。這正是 Task 7 review 釘住的
     * 合約——絕不覆蓋、回傳實際使用的檔名——所以放在這裡自己保證，而非信任
     * 上游。
     */
    override fun writeSibling(suffix: String, bytes: ByteArray): String {
        val name = uniqueNameFor(fileName + suffix)
        val created = directory.createFile(SIBLING_MIME_TYPE, name)
            ?: throw FileNotFoundException("無法在資料夾中建立 $name")
        context.contentResolver.openOutputStream(created.uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("無法寫入 $name")
        return created.name ?: name
    }

    /** 在目錄裡找一個還沒被用過的檔名，必要時在副檔名前插入遞增的數字，
     *  這樣撞名時第二個檔案仍以 .kdbx 結尾，KeePassXC 才認得出來。 */
    private fun uniqueNameFor(base: String): String {
        if (directory.findFile(base) == null) return base

        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""

        var i = 1
        while (directory.findFile("$stem.$i$ext") != null) i++
        return "$stem.$i$ext"
    }

    private companion object {
        /**
         * 用一個 `MimeTypeMap` 查不到對應副檔名的字串。實測
         * `"application/octet-stream"` 在部分裝置上會被自動加上 `.bin`，
         * 讓衝突檔案的檔名不再以 `.kdbx` 結尾，KeePassXC 認不出這是它的格式。
         */
        const val SIBLING_MIME_TYPE = "application/vnd.hintvault.kdbx"
    }
}

/** 持久化 SAF 授權，讓 app 重啟後仍能存取同一個資料夾。 */
object VaultUriPermissions {

    private const val FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun persist(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, FLAGS)
    }

    /** 使用者可能在系統設定裡撤銷授權，或把資料夾刪掉。 */
    fun isStillGranted(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
}
```

- [ ] **Step 5: 執行測試確認通過**

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hintvault.storage.SafVaultFileTest
```

預期：10 tests passed。

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/hintvault/storage/SafVaultFile.kt \
        app/src/androidTest/java/com/hintvault/storage/SafVaultFileTest.kt
git commit -m "feat(storage): SAF 檔案存取與授權持久化"
```

---

### Task 9: Android Keystore 金鑰

規格 §4.4。spike 已在 Galaxy S25 上驗證這組設定可被接受且 StrongBox 可用，這裡把它做成正式程式碼並補齊退回邏輯。

**Files:**
- Create: `app/src/main/java/com/hintvault/crypto/KeystoreKeys.kt`
- Test: `app/src/androidTest/java/com/hintvault/crypto/KeystoreKeysTest.kt`

**Interfaces:**
- Consumes: 無
- Produces:
  - `class KeystoreKeys(alias: String = DEFAULT_ALIAS)`
  - `fun createKey(): KeyCreationResult` 其中 `data class KeyCreationResult(val strongBoxBacked: Boolean)`
  - `fun hasKey(): Boolean`
  - `fun deleteKey()`
  - `fun encryptCipher(): Cipher`
  - `fun decryptCipher(iv: ByteArray): Cipher`
  - `class KeyPermanentlyInvalidException : Exception`

- [ ] **Step 1: 寫失敗的測試**

`app/src/androidTest/java/com/hintvault/crypto/KeystoreKeysTest.kt`：

```kotlin
package com.hintvault.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreKeysTest {

    private val subject = KeystoreKeys(alias = "hv_test_key")

    @Before fun clean() = subject.deleteKey()
    @After fun cleanUp() = subject.deleteKey()

    @Test
    fun createsKey() {
        assertFalse(subject.hasKey())
        subject.createKey()
        assertTrue(subject.hasKey())
    }

    @Test
    fun reportsWhetherStrongBoxWasUsed() {
        // 測試裝置（Galaxy S25）有 StrongBox；無 StrongBox 的機器應回傳 false 而非擲例外
        val result = subject.createKey()
        assertNotNull(result)
        // 不論何者都必須成功建出金鑰
        assertTrue(subject.hasKey())
    }

    @Test
    fun deleteRemovesKey() {
        subject.createKey()
        subject.deleteKey()
        assertFalse(subject.hasKey())
    }

    @Test
    fun deleteIsIdempotent() {
        subject.deleteKey()
        subject.deleteKey()
        assertFalse(subject.hasKey())
    }

    @Test
    fun encryptCipherIsInitialisedWithFreshIv() {
        subject.createKey()
        val first = subject.encryptCipher()
        val second = subject.encryptCipher()

        assertNotNull(first.iv)
        assertEquals(12, first.iv.size)          // GCM 標準 IV 長度
        assertFalse(first.iv.contentEquals(second.iv))   // 絕不可重複使用 IV
    }

    @Test
    fun decryptCipherAcceptsIvFromEncryptCipher() {
        subject.createKey()
        val encrypt = subject.encryptCipher()

        val decrypt = subject.decryptCipher(encrypt.iv)

        assertNotNull(decrypt)
        assertArrayEquals(encrypt.iv, decrypt.iv)
        assertEquals("AES/GCM/NoPadding", decrypt.algorithm)
    }

    @Test
    fun createKeyTwiceReplacesRatherThanAccumulates() {
        subject.createKey()
        subject.createKey()
        assertTrue(subject.hasKey())
    }
}
```

**注意**：`encryptCipher()` / `decryptCipher()` 產生的 Cipher 綁定了生體辨識（`setUserAuthenticationRequired(true)`），實際 `doFinal()` 必須在 `BiometricPrompt` 驗證通過後才會成功。上述測試只驗證 Cipher 能被正確初始化，加解密本身於 Task 10 與 Task 11 驗證。

- [ ] **Step 2: 執行測試確認失敗**

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hintvault.crypto.KeystoreKeysTest
```

預期：編譯失敗，找不到 `KeystoreKeys`。

- [ ] **Step 3: 寫實作**

`app/src/main/java/com/hintvault/crypto/KeystoreKeys.kt`：

```kotlin
package com.hintvault.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore 金鑰已失效（通常是裝置新增了生體特徵），必須回退主密碼。 */
class KeyPermanentlyInvalidException(cause: Throwable) :
    Exception("Keystore 金鑰已失效，需以主密碼重新解鎖", cause)

data class KeyCreationResult(val strongBoxBacked: Boolean)

/**
 * 保護 vault key 的 Keystore 金鑰。
 *
 * 這把金鑰本身不是 vault key——它只用來包裝 vault key（§4.1）。
 * 主密碼永遠不進 Keystore，任何形式皆然。
 */
class KeystoreKeys(private val alias: String = DEFAULT_ALIAS) {

    companion object {
        const val DEFAULT_ALIAS = "hint_vault_wrapping_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = keyStore.containsAlias(alias)

    fun deleteKey() {
        keyStore.takeIf { it.containsAlias(alias) }?.deleteEntry(alias)
    }

    /**
     * 建立生體辨識綁定的金鑰。優先使用 StrongBox，該硬體不支援時退回 TEE。
     */
    fun createKey(): KeyCreationResult {
        deleteKey()
        return try {
            generate(strongBox = true)
            KeyCreationResult(strongBoxBacked = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
            KeyCreationResult(strongBoxBacked = false)
        }
    }

    /**
     * `KeyPermanentlyInvalidatedException` 的實際拋出點是 `Cipher.init()`，
     * 不是 `KeyStore.getKey()`——後者宣告的受檢例外裡沒有 `InvalidKeyException`
     * 這一支，Keystore 只在真正要用這把金鑰做加解密運算時才會檢查它是否已
     * 因生體特徵變更而失效。catch 若包在 `secretKey()` 外面永遠不會觸發，
     * 平台的原始例外會直接往外傳、讓 app 崩潰，而不是優雅退回主密碼。
     */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            try {
                init(Cipher.ENCRYPT_MODE, secretKey())
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw KeyPermanentlyInvalidException(e)
            }
        }

    fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            try {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw KeyPermanentlyInvalidException(e)
            }
        }

    private fun secretKey(): SecretKey = keyStore.getKey(alias, null) as SecretKey

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // 有人在這台裝置新增指紋 → 這把金鑰立刻作廢。
            // 少了這一行，別人註冊自己的生體特徵就能解鎖這個保險庫。
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hintvault.crypto.KeystoreKeysTest
```

預期：7 tests passed。

**前提**：測試裝置必須已設定螢幕鎖定與至少一枚生體特徵，否則 `setUserAuthenticationRequired(true)` 會讓金鑰建立失敗。若測試因此失敗，先在裝置上設好指紋再重跑。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hintvault/crypto/KeystoreKeys.kt \
        app/src/androidTest/java/com/hintvault/crypto/KeystoreKeysTest.kt
git commit -m "feat(crypto): Keystore 金鑰與 StrongBox 退回"
```

---

### Task 10: 包裝的 vault key 與生體辨識解鎖

規格 §4.1、§4.4。首次以主密碼解鎖後，把 vault key 用 Keystore 金鑰包起來存在私有目錄；之後靠生體辨識解包，不必再跑 Argon2。

**Files:**
- Modify: `app/build.gradle.kts`（加入 `androidx.biometric`）
- Create: `app/src/main/java/com/hintvault/crypto/WrappedKeyStore.kt`
- Create: `app/src/main/java/com/hintvault/crypto/BiometricUnlock.kt`
- Test: `app/src/test/java/com/hintvault/crypto/WrappedKeyStoreTest.kt`
- Test: `app/src/androidTest/java/com/hintvault/crypto/WrappedKeyRoundTripTest.kt`

**Interfaces:**
- Consumes: `KeystoreKeys`、`KeyPermanentlyInvalidException`（Task 9）；`ByteArray.wipe()`（Task 2）
- Produces:
  - `data class WrappedKey(val ciphertext: ByteArray, val iv: ByteArray)`
  - `class WrappedKeyStore(directory: File)` 具 `fun put(WrappedKey)`、`fun get(): WrappedKey?`、`fun clear()`、`fun exists(): Boolean`
  - `class BiometricUnlock(activity: FragmentActivity, keys: KeystoreKeys, store: WrappedKeyStore)`
  - `suspend fun enroll(vaultKey: ByteArray): Boolean`
  - `suspend fun unlock(): ByteArray?`
  - `fun isAvailable(): Boolean`

- [ ] **Step 1: 加入依賴**

在 `app/build.gradle.kts` 的 `dependencies` 中加入：

```kotlin
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
```

- [ ] **Step 2: 寫 WrappedKeyStore 的失敗測試**

`app/src/test/java/com/hintvault/crypto/WrappedKeyStoreTest.kt`：

```kotlin
package com.hintvault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WrappedKeyStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun subject() = WrappedKeyStore(folder.root)

    @Test
    fun startsEmpty() {
        assertFalse(subject().exists())
        assertNull(subject().get())
    }

    @Test
    fun roundTripsCiphertextAndIv() {
        val key = WrappedKey(ciphertext = byteArrayOf(1, 2, 3, 4), iv = ByteArray(12) { it.toByte() })
        subject().put(key)

        val got = subject().get()!!
        assertArrayEquals(key.ciphertext, got.ciphertext)
        assertArrayEquals(key.iv, got.iv)
    }

    @Test
    fun existsBecomesTrueAfterPut() {
        subject().put(WrappedKey(byteArrayOf(9), ByteArray(12)))
        assertTrue(subject().exists())
    }

    @Test
    fun clearRemovesStoredKey() {
        val store = subject()
        store.put(WrappedKey(byteArrayOf(9), ByteArray(12)))
        store.clear()

        assertFalse(store.exists())
        assertNull(store.get())
    }

    @Test
    fun clearIsIdempotent() {
        subject().clear()
        subject().clear()
        assertFalse(subject().exists())
    }

    @Test
    fun putOverwritesPreviousKey() {
        val store = subject()
        store.put(WrappedKey(byteArrayOf(1), ByteArray(12) { 1 }))
        store.put(WrappedKey(byteArrayOf(2), ByteArray(12) { 2 }))

        assertArrayEquals(byteArrayOf(2), store.get()!!.ciphertext)
    }

    @Test
    fun getReturnsNullOnTruncatedFile() {
        // 檔案損毀時必須回傳 null 而非擲例外——呼叫端會回退主密碼
        folder.newFile("wrapped.key").writeBytes(byteArrayOf(1, 2))
        assertNull(WrappedKeyStore(folder.root).get())
    }
}
```

- [ ] **Step 3: 執行測試確認失敗**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.WrappedKeyStoreTest"
```

預期：編譯失敗，找不到 `WrappedKeyStore`。

- [ ] **Step 4: 寫 WrappedKeyStore 實作**

`app/src/main/java/com/hintvault/crypto/WrappedKeyStore.kt`：

```kotlin
package com.hintvault.crypto

import java.io.File

/** 被 Keystore 金鑰包起來的 vault key。[iv] 是解包時必須的 GCM IV。 */
class WrappedKey(val ciphertext: ByteArray, val iv: ByteArray)

/**
 * 存放被包裝的 vault key。
 *
 * 檔案格式：1 byte IV 長度 ‖ IV ‖ ciphertext
 *
 * 內容已被 Keystore 金鑰加密，且該金鑰綁定生體辨識，因此即使檔案外流，
 * 沒有通過裝置上的生體驗證也解不開。
 */
class WrappedKeyStore(private val directory: File) {

    private val file get() = File(directory, FILE_NAME)

    fun exists(): Boolean = file.exists()

    fun put(key: WrappedKey) {
        directory.mkdirs()
        file.writeBytes(
            byteArrayOf(key.iv.size.toByte()) + key.iv + key.ciphertext
        )
    }

    /** 檔案不存在或損毀時回傳 null，呼叫端應回退主密碼。 */
    fun get(): WrappedKey? {
        if (!file.exists()) return null
        val raw = file.readBytes()
        if (raw.isEmpty()) return null

        val ivSize = raw[0].toInt()
        if (ivSize <= 0 || raw.size <= 1 + ivSize) return null

        return WrappedKey(
            iv = raw.copyOfRange(1, 1 + ivSize),
            ciphertext = raw.copyOfRange(1 + ivSize, raw.size)
        )
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "wrapped.key"
    }
}
```

- [ ] **Step 5: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.WrappedKeyStoreTest"
```

預期：7 tests passed。

- [ ] **Step 6: 寫 BiometricUnlock 實作**

`app/src/main/java/com/hintvault/crypto/BiometricUnlock.kt`：

```kotlin
package com.hintvault.crypto

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * 生體辨識快速解鎖。
 *
 * Cipher 透過 CryptoObject 交給 BiometricPrompt，因此金鑰的「使用」與該次
 * 驗證綁定，而不是「驗證通過後就能自由取用金鑰」。這個差別很重要：
 * 後者在驗證與使用之間留下了可乘之機。
 */
class BiometricUnlock(
    private val activity: FragmentActivity,
    private val keys: KeystoreKeys = KeystoreKeys(),
    private val store: WrappedKeyStore
) {

    private val executor: Executor get() = androidx.core.content.ContextCompat.getMainExecutor(activity)

    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun isAvailable(): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * 首次以主密碼解鎖成功後呼叫，把 vault key 包起來存下。
     *
     * 先清掉舊的包裝檔，再呼叫 [KeystoreKeys.createKey]——後者內部會刪除舊的
     * Keystore 金鑰。若不先清store，一旦使用者在驗證那一步取消，磁碟上會留下
     * 一個用「已經不存在的金鑰」加密出來的檔案：格式完好、`store.get()` 讀
     * 得到，下一次 [unlock] 卻會在使用者按完指紋後才炸出 `AEADBadTagException`。
     * 先清空是安全的方向——最壞情況只是使用者被迫多輸入一次主密碼。
     */
    suspend fun enroll(vaultKey: ByteArray): Boolean {
        store.clear()
        keys.createKey()
        val cipher = keys.encryptCipher()
        val authenticated = authenticate(cipher, "啟用生體辨識解鎖") ?: return false
        store.put(WrappedKey(ciphertext = authenticated.doFinal(vaultKey), iv = authenticated.iv))
        return true
    }

    /**
     * @return 解包後的 vault key；使用者取消、驗證失敗、或密文本身已損毀時為 null。
     * @throws KeyPermanentlyInvalidException 金鑰已因生體特徵變更而作廢，須回退主密碼。
     */
    suspend fun unlock(): ByteArray? {
        val wrapped = store.get() ?: return null
        val cipher = keys.decryptCipher(wrapped.iv)
        val authenticated = authenticate(cipher, "解鎖保險庫") ?: return null
        return try {
            authenticated.doFinal(wrapped.ciphertext)
        } catch (_: AEADBadTagException) {
            // 密文本身壞了（不是檔案格式壞了，那個 WrappedKeyStore.get() 已經擋掉）。
            // 清掉這個註定失敗的檔案，下次直接走主密碼，而不是每次都在使用者
            // 按完指紋、以為要成功了之後才失敗。
            store.clear()
            null
        }
    }

    /**
     * 生體辨識的 UI（BiometricPrompt）只能在主執行緒操作，強制切過去，
     * 呼叫端不需要自己記得。
     */
    private suspend fun authenticate(cipher: Cipher, title: String): Cipher? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val prompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                        }
                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
                // coroutine 被取消（例如 Activity 正在結束）時，畫面上的生體辨識
                // 提示不會自己消失——沒有這行，prompt 會留在螢幕上但沒有人接得住
                // 它的結果。
                continuation.invokeOnCancellation { prompt.cancelAuthentication() }
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setNegativeButtonText("改用主密碼")
                        .setAllowedAuthenticators(authenticators)
                        .build(),
                    BiometricPrompt.CryptoObject(cipher)
                )
            }
        }
}
```

- [ ] **Step 7: 寫金鑰包裝來回的 instrumented 測試**

`app/src/androidTest/java/com/hintvault/crypto/WrappedKeyRoundTripTest.kt`：

```kotlin
package com.hintvault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 驗證「包裝 → 存檔 → 讀回 → 解包」這條路徑本身是正確的。
 *
 * 這裡刻意使用**不綁定生體辨識**的 Keystore 金鑰，因為 instrumented 測試
 * 無法通過 BiometricPrompt。生體辨識綁定的完整流程於 Task 12 手動驗證。
 */
@RunWith(AndroidJUnit4::class)
class WrappedKeyRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "hv_test_unbound_key"

    private fun unboundKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    @Test
    fun vaultKeySurvivesWrapStoreReadUnwrap() {
        val vaultKey = ByteArray(32) { it.toByte() }
        val store = WrappedKeyStore(File(context.filesDir, "roundtrip-test"))
        store.clear()

        val key = unboundKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        store.put(WrappedKey(ciphertext = encrypt.doFinal(vaultKey), iv = encrypt.iv))

        val wrapped = store.get()!!
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.iv))
        }

        assertArrayEquals(vaultKey, decrypt.doFinal(wrapped.ciphertext))
        store.clear()
    }

    @Test
    fun tamperedCiphertextIsRejectedByGcmTag() {
        val store = WrappedKeyStore(File(context.filesDir, "tamper-test"))
        store.clear()

        val key = unboundKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = encrypt.doFinal(ByteArray(32))
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()
        store.put(WrappedKey(ciphertext, encrypt.iv))

        val wrapped = store.get()!!
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.iv))
        }

        var rejected = false
        try {
            decrypt.doFinal(wrapped.ciphertext)
        } catch (_: javax.crypto.AEADBadTagException) {
            rejected = true
        }
        assertTrue("GCM 必須拒絕被竄改的密文", rejected)
        store.clear()
    }
}
```

- [ ] **Step 8: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.crypto.WrappedKeyStoreTest"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hintvault.crypto.WrappedKeyRoundTripTest
```

預期：單元測試 7 passed，instrumented 2 passed。

- [ ] **Step 9: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/hintvault/crypto \
        app/src/test/java/com/hintvault/crypto app/src/androidTest/java/com/hintvault/crypto
git commit -m "feat(crypto): 包裝的 vault key 與生體辨識解鎖"
```

---

### Task 11: 解鎖狀態與自動上鎖

規格 §4.5、§6。這一層負責「金鑰什麼時候該從記憶體消失」。時間相關的邏輯以注入的時鐘測試，不使用真實延遲。

**Files:**
- Create: `app/src/main/java/com/hintvault/session/VaultSession.kt`
- Create: `app/src/main/java/com/hintvault/session/AutoLock.kt`
- Test: `app/src/test/java/com/hintvault/session/VaultSessionTest.kt`
- Test: `app/src/test/java/com/hintvault/session/AutoLockTest.kt`

**Interfaces:**
- Consumes: `ByteArray.wipe()`（Task 2）
- Produces:
  - `sealed interface LockState { object Locked; object Unlocked }`
  - `class VaultSession` 具 `val state: StateFlow<LockState>`、`fun unlock(vaultKey: ByteArray)`、`fun withKey(block: (ByteArray) -> R): R`、`fun lock()`
  - `data class AutoLockPolicy(val lockOnBackground: Boolean = true, val backgroundGraceMillis: Long = 0, val idleTimeoutMillis: Long = 5 * 60 * 1000)`
  - `class AutoLock(policy: AutoLockPolicy, now: () -> Long, onLock: () -> Unit)` 具 `fun onUserInteraction()`、`fun onEnterBackground()`、`fun onReturnToForeground()`、`fun onScreenOff()`、`fun tick()`

- [ ] **Step 1: 寫 VaultSession 的失敗測試**

`app/src/test/java/com/hintvault/session/VaultSessionTest.kt`：

```kotlin
package com.hintvault.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionTest {

    @Test
    fun startsLocked() {
        assertEquals(LockState.Locked, VaultSession().state.value)
    }

    @Test
    fun unlockMovesToUnlocked() {
        val session = VaultSession()
        session.unlock(ByteArray(32) { 7 })
        assertEquals(LockState.Unlocked, session.state.value)
    }

    @Test
    fun withKeyExposesTheKeyWhileUnlocked() {
        val session = VaultSession()
        val key = ByteArray(32) { 7 }
        session.unlock(key.copyOf())

        val seen = session.withKey { it.copyOf() }
        assertArrayEquals(key, seen)
    }

    @Test
    fun lockZeroesTheKeyInMemory() {
        val session = VaultSession()
        val key = ByteArray(32) { 7 }
        session.unlock(key)   // session 接管這個陣列

        session.lock()

        // 規格 §4.5：上鎖時清除記憶體中的 vault key
        assertArrayEquals(ByteArray(32), key)
    }

    @Test
    fun lockMovesBackToLocked() {
        val session = VaultSession()
        session.unlock(ByteArray(32))
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }

    @Test
    fun withKeyThrowsWhenLocked() {
        var threw = false
        try {
            VaultSession().withKey { it.size }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun unlockingTwiceZeroesTheOldKey() {
        val session = VaultSession()
        val first = ByteArray(32) { 1 }
        session.unlock(first)
        session.unlock(ByteArray(32) { 2 })

        assertArrayEquals(ByteArray(32), first)
    }

    @Test
    fun lockIsIdempotent() {
        val session = VaultSession()
        session.unlock(ByteArray(32))
        session.lock()
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }
}
```

- [ ] **Step 2: 寫 AutoLock 的失敗測試**

`app/src/test/java/com/hintvault/session/AutoLockTest.kt`：

```kotlin
package com.hintvault.session

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoLockTest {

    private class Clock(var now: Long = 0L) { fun read() = now }

    private fun subject(
        policy: AutoLockPolicy = AutoLockPolicy(),
        clock: Clock = Clock()
    ): Triple<AutoLock, Clock, () -> Int> {
        var lockCount = 0
        val autoLock = AutoLock(policy, clock::read) { lockCount++ }
        return Triple(autoLock, clock) { lockCount }
    }

    @Test
    fun locksImmediatelyOnBackgroundByDefault() {
        val (autoLock, _, locks) = subject()
        autoLock.onEnterBackground()
        assertEquals(1, locks())
    }

    @Test
    fun locksOnScreenOff() {
        val (autoLock, _, locks) = subject()
        autoLock.onScreenOff()
        assertEquals(1, locks())
    }

    @Test
    fun graceperiodDelaysBackgroundLock() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(backgroundGraceMillis = 30_000))
        autoLock.onEnterBackground()
        assertEquals(0, locks())

        clock.now += 29_000
        autoLock.tick()
        assertEquals(0, locks())

        clock.now += 2_000
        autoLock.tick()
        assertEquals(1, locks())
    }

    @Test
    fun returningToForegroundWithinGraceCancelsLock() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(backgroundGraceMillis = 30_000))
        autoLock.onEnterBackground()
        clock.now += 10_000
        autoLock.onReturnToForeground()

        clock.now += 60_000
        autoLock.tick()
        assertEquals(0, locks())
    }

    @Test
    fun locksAfterIdleTimeout() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(idleTimeoutMillis = 5 * 60 * 1000))
        autoLock.onUserInteraction()

        clock.now += 5 * 60 * 1000 + 1
        autoLock.tick()
        assertEquals(1, locks())
    }

    @Test
    fun interactionResetsIdleTimer() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(idleTimeoutMillis = 60_000))
        autoLock.onUserInteraction()

        clock.now += 50_000
        autoLock.onUserInteraction()
        clock.now += 50_000
        autoLock.tick()

        assertEquals(0, locks())
    }

    @Test
    fun doesNotLockTwiceForTheSameIdlePeriod() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(idleTimeoutMillis = 60_000))
        autoLock.onUserInteraction()

        clock.now += 120_000
        autoLock.tick()
        autoLock.tick()

        assertEquals(1, locks())
    }

    @Test
    fun defaultPolicyMatchesSpec() {
        val policy = AutoLockPolicy()
        assertEquals(true, policy.lockOnBackground)
        assertEquals(0L, policy.backgroundGraceMillis)
        assertEquals(5 * 60 * 1000L, policy.idleTimeoutMillis)
    }
}
```

- [ ] **Step 3: 執行測試確認失敗**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.session.*"
```

預期：編譯失敗，找不到 `VaultSession`。

- [ ] **Step 4: 寫 VaultSession 實作**

`app/src/main/java/com/hintvault/session/VaultSession.kt`：

```kotlin
package com.hintvault.session

import com.hintvault.crypto.wipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LockState {
    data object Locked : LockState
    data object Unlocked : LockState
}

/**
 * 記憶體中 vault key 的生命週期。
 *
 * [unlock] 會**接管**傳入的陣列——呼叫端不應再持有或重複使用它。
 * 上鎖時陣列會被歸零（規格 §4.5）。
 *
 * **僅限單一執行緒（主執行緒）呼叫。** 內部沒有任何同步機制——[vaultKey]
 * 是普通的 `var`，[lock] 會直接把陣列內容歸零。若另一執行緒當下正在
 * [withKey] 的區塊內使用同一個陣列，[lock] 會在它背後把內容清成 0，
 * 呼叫端不會收到任何訊號，會安靜地開始對著一把已清空的金鑰操作。
 * 之後任何要接上背景計時器（例如 `AutoLock.tick()`）觸發 [lock] 的地方，
 * 都必須先切回主執行緒再呼叫，不能直接從背景執行緒呼叫。
 */
class VaultSession {

    private val _state = MutableStateFlow<LockState>(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var vaultKey: ByteArray? = null

    fun unlock(vaultKey: ByteArray) {
        this.vaultKey?.wipe()     // 舊金鑰絕不留在堆積上
        this.vaultKey = vaultKey
        _state.value = LockState.Unlocked
    }

    /**
     * 在已解鎖狀態下取用金鑰。刻意不提供 getter，
     * 避免呼叫端把金鑰複製出去後我們失去對它生命週期的掌握。
     */
    fun <R> withKey(block: (ByteArray) -> R): R {
        val key = vaultKey ?: throw IllegalStateException("保險庫已上鎖")
        return block(key)
    }

    fun lock() {
        vaultKey?.wipe()
        vaultKey = null
        _state.value = LockState.Locked
    }
}
```

- [ ] **Step 5: 寫 AutoLock 實作**

`app/src/main/java/com/hintvault/session/AutoLock.kt`：

```kotlin
package com.hintvault.session

/** 規格 §6 的上鎖觸發條件。 */
data class AutoLockPolicy(
    val lockOnBackground: Boolean = true,
    /** 進入背景後的寬限期。0 代表立即上鎖。 */
    val backgroundGraceMillis: Long = 0,
    val idleTimeoutMillis: Long = 5 * 60 * 1000
)

/**
 * 判斷何時該上鎖。
 *
 * 時鐘以參數注入，讓時間相關的行為可用假時鐘窮舉測試，
 * 不必在測試裡真的等五分鐘。
 */
class AutoLock(
    private val policy: AutoLockPolicy,
    private val now: () -> Long,
    private val onLock: () -> Unit
) {

    private var lastInteractionAt: Long = now()
    private var backgroundedAt: Long? = null
    private var alreadyLocked = false

    fun onUserInteraction() {
        lastInteractionAt = now()
        alreadyLocked = false
    }

    fun onEnterBackground() {
        if (!policy.lockOnBackground) return
        if (policy.backgroundGraceMillis <= 0) {
            fire()
        } else {
            backgroundedAt = now()
        }
    }

    fun onReturnToForeground() {
        backgroundedAt = null
        onUserInteraction()
    }

    /** 螢幕關閉一律立即上鎖，不套用寬限期。 */
    fun onScreenOff() = fire()

    /** 由前景的計時器定期呼叫，用於檢查逾時。 */
    fun tick() {
        val current = now()
        backgroundedAt?.let {
            if (current - it > policy.backgroundGraceMillis) { fire(); return }
        }
        if (current - lastInteractionAt > policy.idleTimeoutMillis) fire()
    }

    private fun fire() {
        if (alreadyLocked) return
        alreadyLocked = true
        onLock()
    }
}
```

- [ ] **Step 6: 執行測試確認通過**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hintvault.session.*"
```

預期：VaultSessionTest 8 passed，AutoLockTest 8 passed。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hintvault/session app/src/test/java/com/hintvault/session
git commit -m "feat(session): 解鎖狀態與自動上鎖策略"
```

---

### Task 12: 端到端驗證與 KeePassXC 互通驗收

規格 §8「跨工具驗收測試」。這是整份計畫的收尾：把所有層串起來，在真機上跑完整流程，並用桌面版 KeePassXC 驗證產出的檔案。**若這一項不通過，KDBX 相容性的整個立論即不成立。**

**Files:**
- Create: `app/src/androidTest/java/com/hintvault/EndToEndTest.kt`
- Create: `docs/manual-verification.md`

**Interfaces:**
- Consumes: 前述所有任務的產出
- Produces: 可重複執行的端到端驗證，以及需要人工操作的項目清單

- [ ] **Step 1: 寫端到端測試**

`app/src/androidTest/java/com/hintvault/EndToEndTest.kt`：

```kotlin
package com.hintvault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hintvault.crypto.KdfParams
import com.hintvault.session.LockState
import com.hintvault.session.VaultSession
import com.hintvault.storage.FileBackupStore
import com.hintvault.storage.SafVaultFile
import com.hintvault.storage.SaveResult
import com.hintvault.storage.VaultRepository
import com.hintvault.vault.Entry
import com.hintvault.vault.HistoricalHint
import com.hintvault.vault.Vault
import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertArrayEquals
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

        // SafVaultFile 吃的是已解析好的目錄 DocumentFile，不是單一檔案的 Uri
        // ——那是 Task 8 review 修掉的設計（ACTION_OPEN_DOCUMENT 的單一檔案
        // 授權下 parentFile 恆為 null，writeSibling 完全無法運作）。
        val directory = DocumentFile.fromFile(dbFile.parentFile!!)
        val repo = VaultRepository(
            SafVaultFile(context, directory, dbFile.name),
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
        val key = ByteArray(32) { 5 }
        session.unlock(key)
        assertEquals(LockState.Unlocked, session.state.value)
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
        assertArrayEquals(ByteArray(32), key)
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
}
```

- [ ] **Step 2: 執行端到端測試**

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hintvault.EndToEndTest
```

預期：2 tests passed。

- [ ] **Step 3: 把資料庫拉到電腦上**

```bash
adb pull /sdcard/Android/data/com.hintvault/files/for-keepassxc.kdbx /tmp/for-keepassxc.kdbx
```

- [ ] **Step 4: 用 KeePassXC 驗收（方向一：本 app → 桌面）**

```bash
echo "correct horse battery staple" | keepassxc-cli db-info /tmp/for-keepassxc.kdbx
echo "correct horse battery staple" | keepassxc-cli show --all -s /tmp/for-keepassxc.kdbx "github.com"
```

必須全部成立：

- `db-info` 顯示 `加密法：AES 256-bit` 與 `KDF：Argon2d`
- `show --all` 顯示 `Password: 提示：貓的名字 + 生日`
- `show --all` 顯示 `hist.1.at` 與 `hist.1.hint`

任一項不成立就是互通性失敗，必須停下來查明原因，不可繼續往 UI 開發推進。

- [ ] **Step 5: 用 KeePassXC 驗收（方向二：桌面 → 本 app）**

```bash
rm -f /tmp/from-keepassxc.kdbx
printf 'desktop-pass\ndesktop-pass\n' | keepassxc-cli db-create --set-password /tmp/from-keepassxc.kdbx
printf 'desktop-pass\nentry-pass\nentry-pass\n' | \
  keepassxc-cli add /tmp/from-keepassxc.kdbx -u someone --password-prompt "made-on-desktop"
adb push /tmp/from-keepassxc.kdbx /sdcard/Android/data/com.hintvault/files/from-keepassxc.kdbx
```

**更正（2026-08-15，於 Task 12 實作中發現）**：原本這裡寫 `adb push` 到 `/sdcard/Download/`，但在 targetSdk 36 的 scoped storage 下，`File` 讀取這種非 app 專屬目錄一律 `EACCES`，需要 `MANAGE_EXTERNAL_STORAGE`——而那正是 `SafVaultFile.kt` 的設計說明裡明講「不申請，權限過大」的東西。改推到 app 私有的外部檔案目錄 `/sdcard/Android/data/com.hintvault/files/`，讀取端對應改用 `context.getExternalFilesDir(null)`。驗證的實質內容不變（仍是真機上、由真正 `keepassxc-cli` 產生、經 `adb push` 送達的位元組，被 `Vault.open()` 開啟），只是測試 fixture 存放位置改變。

然後把下列測試加進 `EndToEndTest.kt`，確認本 app 讀得了桌面端建立的檔案：

```kotlin
    /**
     * 方向二：KeePassXC 建立的資料庫（KDBX 3.1 / AES-KDF）必須能被本 app 開啟。
     *
     * 執行前需先在電腦上跑這三行，再用 adb push 送到裝置：
     *
     *   rm -f /tmp/from-keepassxc.kdbx
     *   printf 'desktop-pass\ndesktop-pass\n' | keepassxc-cli db-create --set-password /tmp/from-keepassxc.kdbx
     *   printf 'desktop-pass\nentry-pass\nentry-pass\n' | \
     *     keepassxc-cli add /tmp/from-keepassxc.kdbx -u someone --password-prompt "made-on-desktop"
     *   adb push /tmp/from-keepassxc.kdbx /sdcard/Android/data/com.hintvault/files/from-keepassxc.kdbx
     *
     * 注意目的地是 app 私有的外部檔案目錄，不是 /sdcard/Download——後者在
     * targetSdk 36 下對一般 File 讀取一律 EACCES，且本專案刻意不申請
     * MANAGE_EXTERNAL_STORAGE 去繞過它（見 SafVaultFile.kt 的設計說明）。
     * 推到錯的路徑不會讓測試失敗，只會讓它被 Assume 跳過——那樣就沒有
     * 真的驗證到方向二的互通性，卻看起來像是全部測試都通過了。
     */
    @Test
    fun opensDatabaseCreatedByKeePassXc() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pushed = File(context.getExternalFilesDir(null), "from-keepassxc.kdbx")
        org.junit.Assume.assumeTrue(
            "需先執行上方 KDoc 列出的 keepassxc-cli + adb push 指令", pushed.exists()
        )

        val vault = Vault.open(pushed.readBytes(), "desktop-pass".toCharArray())
        val titles = vault.entries().map { it.title }

        assertTrue("應讀到桌面端建立的 entry", titles.contains("made-on-desktop"))
    }
```

以 `Assume` 而非 `assert` 是刻意的：沒有推送檔案時這項測試應被跳過，而不是讓整個測試回合失敗。

執行：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hintvault.EndToEndTest
```

預期：3 tests passed。

- [ ] **Step 6: 寫下需人工驗證的項目**

`docs/manual-verification.md`：

```markdown
# 需人工驗證的項目

這些無法自動化，每次動到相關程式碼後都要重跑一次。

## 生體辨識解鎖（規格 §4.4）

1. 以主密碼解鎖，於設定中啟用生體辨識
2. 關閉 app 再開啟 → 應出現生體辨識提示，通過後直接進入，不需輸入主密碼
3. 於提示畫面選「改用主密碼」→ 應可正常回退

## 生體特徵變更後金鑰失效（規格 §4.4）

**破壞性操作，需使用者同意後進行。**

1. 啟用生體辨識解鎖
2. 到系統設定新增一枚指紋
3. 回到 app → 必須要求主密碼，且不可再以生體辨識解鎖

若此項失敗，代表 `setInvalidatedByBiometricEnrollment(true)` 沒有生效，
別人註冊自己的指紋就能打開這個保險庫。這是不可放行的缺陷。

## SAF 選檔與授權持久化（規格 §5.2）

1. 首次啟動 → 選擇資料庫位置，指定同步工具監看的資料夾
2. 完全關閉 app 後重開 → 應可直接存取同一個檔，不需重選
3. 到系統設定撤銷該 app 的檔案存取權 → app 應給出明確訊息並請使用者重選

## 外部同步衝突（規格 §5.3）

1. 手機與電腦同時開啟同一個資料庫
2. 兩邊各改一筆
3. 讓同步工具把電腦版同步到手機
4. 手機上存檔 → 必須拒絕覆寫，並產生 `*.conflict-*.kdbx`
5. 確認電腦上的改動完好無損

## 螢幕防護（規格 §4.6）

1. 於任一畫面按截圖 → 應被系統拒絕
2. 切到最近工作畫面 → app 預覽圖應為空白，不得顯示提示內容
```

- [ ] **Step 7: Commit**

```bash
git add app/src/androidTest/java/com/hintvault/EndToEndTest.kt docs/manual-verification.md
git commit -m "test: 端到端驗證與 KeePassXC 互通驗收"
```

---

## 完成後的狀態

執行完 Task 1–12 後會有：

- 一個可建置的 Android app module，KDBX 層在真機上驗證可讀可寫
- `crypto` / `vault` / `storage` / `session` 四層各自獨立且有測試涵蓋
- 約 60 項自動化測試，其中純邏輯部分可在 JVM 上秒級執行
- 與 KeePassXC 雙向互通的驗收證據
- 一份人工驗證清單，涵蓋無法自動化的生體辨識與同步情境

**尚未有任何使用者介面。** 規格 §7 的六個畫面另立計畫，於本計畫完成、`Vault` 與 `VaultSession` 的 API 經實作驗證穩定後撰寫。
