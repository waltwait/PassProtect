package com.passprotect.vault

import com.passprotect.crypto.KdfParams
import com.passprotect.crypto.toUtf8Bytes
import com.passprotect.crypto.useThenWipe
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

/** 檔案格式本身不受支援（例如 KeePassXC 用 Argon2id 建立的資料庫）。 */
class UnsupportedVaultFormatException(cause: Throwable) :
    Exception("此資料庫使用本 app 不支援的格式或演算法", cause)

/**
 * 函式庫解析標頭時若 KDF（或 cipher）UUID 未註冊，會擲出
 * `IllegalArgumentException("Unknown Cipher UUID")`——見
 * `Encryption.KeyDerivationFunction.getKdf(UUID)`，由
 * `KdbxHeader.setKdfParameters()` 呼叫，而後者是在解析「標頭明文」時執行，
 * 早於任何需要密碼的解密步驟。因此這個例外與密碼對不對無關：本函式庫只
 * 註冊了 AES-KDF 與 Argon2d，KeePassXC 用 Argon2id 建的檔案一定會落在這裡。
 * 可以放心與「密碼錯誤」分開分類，不必用猜的。
 *
 * 沒有更精確的例外型別可用（IllegalArgumentException 是泛用型別），
 * 因此比對訊息字串是必要手段，而非偷懶。
 */
private fun Throwable.isUnknownKdfOrCipherUuid(): Boolean =
    generateSequence(this) { it.cause }
        .any { it is IllegalArgumentException && it.message == "Unknown Cipher UUID" }

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
        private const val PROP_USERNAME = "UserName"
        private const val PROP_PASSWORD = "Password"

        /**
         * 主密碼不在此處要求——它只有在 [serialize] 寫出位元組時才用得到。
         *
         * [kdf] 一律夾到不低於 [KdfParams.FLOOR]，理由與 [kdfParamsOrDefault] 相同：
         * 這裡是唯一真的會被拿去建立新資料庫的路徑，任何呼叫端（包含之後的 UI）
         * 傳進來的參數都不該讓資料庫用低於安全下限的 KDF 存檔。
         */
        fun create(kdf: KdfParams = KdfParams.DEFAULT): Vault =
            Vault(DomDatabaseWrapper(), kdf.clampedToFloor())

        /**
         * [masterPassword] 的所有權留在呼叫端——本函式只清空自己內部由它
         * 衍生出的 UTF-8 位元組拷貝（經 [useThenWipe]），不會動呼叫端傳進來
         * 的這個陣列。呼叫端用完後必須自己呼叫 `masterPassword.wipe()`。
         */
        fun open(bytes: ByteArray, masterPassword: CharArray): Vault {
            val creds = masterPassword.toUtf8Bytes().useThenWipe { KdbxCreds(it) }
            val db = try {
                DomDatabaseWrapper.load(creds, ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                if (e.isUnknownKdfOrCipherUuid()) throw UnsupportedVaultFormatException(e)
                // 函式庫對錯誤密碼擲出 IllegalStateException，對損毀檔案擲出各種例外。
                // 一律轉成同一個型別；cause 保留在裡面供除錯用，只是不讓呼叫端
                // 依錯誤「種類」做判斷分支。
                throw WrongPasswordException(e)
            }
            return Vault(db, db.kdfParamsOrDefault())
        }

        /**
         * 沿用檔案自己的 Argon2 成本參數，但**絕不低於 [KdfParams.FLOOR]**。
         *
         * 沿用的理由：在慢機器上校準到 FLOOR 的資料庫，若每次存檔都寫回 DEFAULT，
         * 之後每次解鎖都會超出預算——等於把校準結果靜默作廢。
         *
         * 夾住下限的理由：檔案可能來自別的工具、被手動編輯、或根本是惡意構造的。
         * 低於 1 MiB 會整數截斷成 0，讓 BouncyCastle 在存檔時擲例外而使資料庫
         * 永久存不了；1–15 MiB 則低於本 app 的安全下限（見 53006cb：「慢一點，
         * 不能弱一點」）。兩者都不接受。
         *
         * 不夾上限：使用者若在桌面端刻意選了更強的參數，那是他的決定，
         * 我們沒有理由替他調弱。解鎖較慢由 UI 以 [KdfParams.exceedsBudget] 提示。
         */
        private fun DomDatabaseWrapper.kdfParamsOrDefault(): KdfParams {
            val header = (streamFormat as? KdbxStreamFormat)?.streamConfiguration ?: return KdfParams.DEFAULT
            val kdf = header.kdfParameters ?: return KdfParams.DEFAULT
            return try {
                KdfParams(
                    memoryMib = kdf.mustGet("M").asLong() / (1024 * 1024),
                    iterations = kdf.mustGet("I").asLong(),
                    parallelism = kdf.mustGet("P").asInteger()
                ).clampedToFloor()
            } catch (_: Exception) {
                KdfParams.DEFAULT
            }
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
        target.setProperty(PROP_USERNAME, entry.account)
        target.setProperty(PROP_PASSWORD, entry.hint)

        // 先清掉所有既有的 hist.<n>.(at|hint) 欄位，再寫入新的。
        // 少了這一步，history 變短時舊欄位會殘留成幽靈資料。用 HistoryCodec
        // 的嚴格判斷而非鬆散字首比對，否則使用者手動加在 KeePassXC 上、
        // 湊巧以 "hist." 開頭但格式不同的屬性會被當垃圾清掉。
        target.propertyNames.filter { HistoryCodec.isHistoryKey(it) }
            .forEach { target.removeProperty(it) }
        HistoryCodec.encode(entry.history).forEach { (k, v) -> target.setProperty(k, v) }
    }

    fun delete(id: UUID): Boolean {
        val raw = findRaw(id) ?: return false
        // Entry.getParent() 在函式庫介面標註 @Nullable（見 Entry.class 的
        // RuntimeInvisibleAnnotations），Kotlin 因此把繼承自介面、未覆寫註解的
        // DomEntryWrapper.getParent() 也視為可為 null。這裡用 !! 是安全的：
        // raw 只會經由 findRaw → findEntries 取得，其實作是用 XPath 從某個
        // group 元素底下找 entry 元素、再包成 wrapper，parent 因此必然存在，
        // 與這筆 entry 是掛在 rootGroup 還是使用者自訂的子群組無關。
        raw.parent!!.removeEntry(raw)
        return true
    }

    /**
     * [masterPassword] 的所有權留在呼叫端——本函式只清空自己內部由它
     * 衍生出的 UTF-8 位元組拷貝（經 [useThenWipe]），不會動呼叫端傳進來
     * 的這個陣列。呼叫端用完後必須自己呼叫 `masterPassword.wipe()`。
     */
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

    /** 測試用：把某筆記錄的 hv.id 改成指定值，模擬外部工具寫壞這個欄位。 */
    internal fun corruptIdMarkerForTest(id: UUID, value: String) {
        findRaw(id)?.setProperty(PROP_UUID_MARKER, value)
    }

    /** 測試用：直接在某筆記錄底層設一個任意屬性，模擬外部工具寫入本 app 不認識的欄位。 */
    internal fun setRawPropertyForTest(id: UUID, key: String, value: String) {
        findRaw(id)?.setProperty(key, value)
    }

    /** 測試用：檢視本 vault 存檔時會使用的 KDF 參數。 */
    internal fun kdfParamsForTest(): KdfParams = kdf

    private fun findRaw(id: UUID): DomEntryWrapper? =
        db.findEntries { it.domainId() == id }.firstOrNull()

    private fun DomEntryWrapper.toDomainEntry(): Entry {
        val props = propertyNames.associateWith { getProperty(it) ?: "" }
        return Entry(
            id = domainId(),
            title = props[PROP_TITLE].orEmpty(),
            account = props[PROP_USERNAME].orEmpty(),
            hint = props[PROP_PASSWORD].orEmpty(),
            modifiedAt = lastModificationTime?.toInstant() ?: Instant.EPOCH,
            history = HistoryCodec.decode(props)
        )
    }

    /**
     * 領域 id 的單一權威來源，讀路徑（[toDomainEntry]）與寫路徑（[findRaw]）
     * 都必須經過這裡，否則兩邊會各自用不同邏輯判斷「這是同一筆」，一旦不一致
     * 就會出現「明明列得出來，一改就變兩筆、原本那筆刪不掉」的錯誤——
     * 不論是因為 hv.id 遺失（KeePassXC 建立的 entry）、格式不正規（大寫、
     * 缺 hyphen），還是整個壞掉。
     *
     * `hv.id` 存在且能解析成 UUID 時優先採用；否則退回 KDBX 自己的
     * `<UUID>` 元素——`Helpers.uuidFromBase64` 在該元素缺失或截斷時會擲
     * NPE / BufferUnderflowException，用 runCatching 吞掉，避免一筆壞資料
     * 讓 findEntries{...} 對每一筆都跑一次此函式時整個查詢失敗。
     * 兩者都拿不到時退回全零 UUID，只求不拋例外，不保證能被找回。
     */
    // 定義在函式庫的 Entry 介面上（用全限定名，避免撞到本檔案的領域 Entry 型別），
    // 而非 DomEntryWrapper：db.findEntries { ... } 傳進 lambda 的 it 是
    // Entry.Matcher.matches(Entry) 這個 SAM 介面的參數，靜態型別是（未參數化的）
    // 介面本身，不是 DomEntryWrapper，寫在 DomEntryWrapper 上 findRaw 裡會解析不到。
    private fun org.linguafranca.pwdb.Entry<*, *, *, *>.domainId(): UUID =
        getProperty(PROP_UUID_MARKER)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: runCatching { uuid }.getOrNull()
            ?: UUID(0L, 0L)
}

/**
 * 領域模型的 UUID 存成自訂欄位而非沿用 KDBX 自己的 UUID。
 * 理由：KeePassJava2 的 newEntry() 不允許指定 UUID，而領域層需要穩定的識別碼。
 */
private const val PROP_UUID_MARKER = "hv.id"
