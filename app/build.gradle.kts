import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application") version "9.3.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

// release 簽章的憑證/密碼：本機放在專案根目錄的 keystore.properties（.gitignore 排除，
// 不進版控）；CI（.github/workflows/release.yml）沒有這個檔案，改從 PASSPROTECT_RELEASE_*
// 環境變數拿——密碼只存在 GitHub secrets 跟 runner 的環境變數裡，不必為了打包再寫一份
// 進檔案。兩邊都沒有時（還沒建過 release 的環境）debug build 照常能建，release 會是
// 沒簽章的 APK，不能拿去發佈。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

fun releaseSigning(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: providers.environmentVariable(env).orNull

val releaseStoreFile = releaseSigning("storeFile", "PASSPROTECT_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigning("storePassword", "PASSPROTECT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigning("keyAlias", "PASSPROTECT_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigning("keyPassword", "PASSPROTECT_RELEASE_KEY_PASSWORD")
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).none { it.isNullOrBlank() } &&
        rootProject.file(releaseStoreFile!!).exists()

android {
    namespace = "com.passprotect"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.passprotect"
        minSdk = 29
        targetSdk = 36
        versionCode = 23
        versionName = "1.0.22"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8：沒開的時候 release APK 有 57 MB（material-icons-extended、Guava、
            // BouncyCastle 整包都原封不動塞進去），Compose 的程式碼也沒經過最佳化，
            // 動畫在 release 版一樣會頓。保留規則跟為什麼這樣寫，見 proguard-rules.pro。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 容器與加密，完全不修改。dom 模組改為 vendor 進 src/main/java，
    // 因此這裡不能依賴 KeePassJava2-dom。
    //
    // 但 Maven 座標版的 KeePassJava2-kdbx-2.2.4.jar 本身其實已經內建打包了一份
    // org.linguafranca.pwdb.kdbx.dom.* class（不是另一個獨立的 -dom 座標才有），
    // 跟本專案 vendor 進 src/main/java 的那份是同名同套件的重複類別。debug build
    // 的 dex 合併沒抓到（可能是增量/寬鬆），但 release build 的 mergeDexRelease
    // 會直接失敗：「Type ... DomHelper$ValueCreator is defined multiple times」。
    // Gradle 沒有「依賴座標 + 排除其中特定 class」這種細粒度機制，因此改用
    // app/libs/ 下這份本機處理過的 jar：內容跟官方 2.2.4 完全一樣，只是先用
    // `zip -d` 把 org/linguafranca/pwdb/kdbx/dom/*.class 這幾個檔案刪掉，讓
    // vendor 進來的版本變成唯一一份。下面重新列出的幾個 transitive 依賴，是
    // `./gradlew :app:dependencies` 對照官方座標版本印出來、原本會自動帶入的那些，
    // 改用本機 jar 後 Gradle 不會再自動解析，必須手動補回同一份清單。
    implementation(files("libs/KeePassJava2-kdbx-2.2.4-no-dom.jar"))
    implementation("org.linguafranca.pwdb:database:2.2.4") {
        exclude(group = "ch.qos.logback")
        exclude(group = "junit")
    }
    implementation("org.linguafranca.pwdb:util:2.2.4") {
        exclude(group = "ch.qos.logback")
        exclude(group = "junit")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcutil-jdk18on:1.78.1")
    implementation("commons-codec:commons-codec:1.17.0")
    implementation("org.slf4j:slf4j-api:2.0.16")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
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
