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

    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
