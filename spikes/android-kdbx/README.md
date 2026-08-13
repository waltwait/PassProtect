# Android KDBX spike

驗證 KeePassJava2 能否在 Android 上支撐本專案的 KDBX 需求。結論見
`docs/superpowers/specs/2026-08-13-password-hint-vault-design.md` §9。

## 這裡有什麼

- `app/src/main/java/org/linguafranca/pwdb/kdbx/dom/` — 自上游 vendor 的 dom 模組
  （Apache-2.0）。**只改了 `DomSerializableDatabase.save()` 中的一行**：Android 無法
  `cloneNode` 一個 Document 節點，改以 `importNode` 深拷貝。修補處有註解標示。
- `app/src/androidTest/` — 實機測試：KDBX4 來回、讀取 KeePassXC 產生的檔、
  Argon2 成本量測、Keystore/StrongBox 設定驗證、DOM 拷貝行為對照。

## 跑法

需要一台以 adb 連線的 Android 裝置。

    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    gradle :app:connectedDebugAndroidTest
    adb logcat -d -s KDBXSPIKE

量測數據透過 logcat 的 `KDBXSPIKE` tag 輸出，不在 JUnit 報告裡。
