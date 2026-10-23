# release build 的 R8 保留規則。
#
# 原則：只寫 R8 自己追不到的東西。R8 是從程式碼裡「真的有被呼叫到」的類別
# 一路往下追，直接 new / 直接呼叫的類別一定會留下來；會被它誤刪的只有
# 「用字串名稱、反射、ServiceLoader 才找得到」的類別。下面每一條都說明它屬於
# 哪一種情況，沒有理由的規則不要加——多保留一個套件就是 APK 多大一截。

# --- KDBX 函式庫（KeePassJava2 + 本專案 vendor 進來的 dom 模組）---------------
# 這是開檔／存檔的核心路徑：主密碼 → Argon2 → 解密 → 解析 XML，壞掉就是整個
# 資料庫打不開，而且只有實機才測得出來。掃過 bytecode，這幾個套件本身沒有用
# 反射或 ServiceLoader（加密演算法是依 KDBX 檔頭的 UUID 查表，直接 new 出來），
# 理論上不寫規則也不會壞；但整個套件加起來才一百多個類別、幾百 KB，用這點
# 體積換「這條路徑完全不被 R8 改寫」很划算。
#
# 只保留 org.linguafranca.pwdb，不是整個 org.linguafranca：util-2.2.4.jar 裡
# 還夾帶了一個給 Maven 跑測試用的 JUnit listener（org.linguafranca.test.maven），
# 連它一起強制保留的話 R8 會因為找不到 JUnit 而中止建置。
-keep class org.linguafranca.pwdb.** { *; }

# --- BouncyCastle：刻意「不」整包保留 -------------------------------------------
# KeePassJava2 用的是 BouncyCastle 的輕量 API（AESEngine、ChaCha7539Engine、
# Salsa20Engine、Argon2BytesGenerator…）直接 new 出來，不是透過 JCA 的
# Cipher.getInstance("…", "BC") 用字串名稱查 provider——後者才需要整包保留
# org.bouncycastle.jcajce.provider.**。掃過 bcprov 的 crypto／util 套件也沒有
# 任何 Class.forName／ServiceLoader，R8 自己就追得到用到的那些類別。
# 整包保留 bcprov 會多出好幾 MB，所以這裡不加 -keep。
#
# 其餘 JCA 用法（AndroidKeyStore 的 AES/GCM、SHA-256、HmacSHA256、KDBX 的
# AES/GCM）走的都是 Android 系統內建的 provider，不在 APK 裡，R8 碰不到。
