plugins {
    id("com.android.application") version "9.3.1"
}

android {
    namespace = "com.example.kdbxspike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.kdbxspike"
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Container + crypto only, unmodified. The `dom` module is vendored into
    // src/main/java with a one-line Android fix instead of being depended on,
    // which keeps a single definition of each class on the dex path.
    implementation("org.linguafranca.pwdb:KeePassJava2-kdbx:2.2.4") {
        exclude(group = "ch.qos.logback")
        exclude(group = "junit")
    }
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
