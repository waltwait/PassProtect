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
                // 一律轉成同一個型別；cause 保留在裡面供除錯用，只是不讓呼叫端
                // 依錯誤「種類」做判斷分支。
                throw WrongPasswordException(e)
            }
            return Vault(db, db.kdfParamsOrDefault())
        }

        /**
         * 沿用檔案自己的 Argon2 成本參數。
         *
         * 不這樣做的話，在慢機器上校準到 FLOOR 的資料庫，會在下次存檔時被寫回
         * DEFAULT，之後每次解鎖都超出 Task 4 要守的預算——等於把校準結果靜默作廢。
         * 檔案若不是用 Argon2（例如 KeePassXC 的 AES-KDF 3.1 檔），沒有可沿用的
         * 參數，才退回 DEFAULT。
         */
        private fun DomDatabaseWrapper.kdfParamsOrDefault(): KdfParams {
            val header = (streamFormat as? KdbxStreamFormat)?.streamConfiguration ?: return KdfParams.DEFAULT
            val kdf = header.kdfParameters ?: return KdfParams.DEFAULT
            return try {
                KdfParams(
                    memoryMib = kdf.mustGet("M").asLong() / (1024 * 1024),
                    iterations = kdf.mustGet("I").asLong(),
                    parallelism = kdf.mustGet("P").asInteger()
                )
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
        target.setProperty(PROP_PASSWORD, entry.hint)

        // 先清掉所有既有的 hist.* 欄位，再寫入新的。
        // 少了這一步，history 變短時舊欄位會殘留成幽靈資料。
        target.propertyNames.filter { it.startsWith("hist.") }
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

    private fun findRaw(id: UUID): DomEntryWrapper? {
        val target = id.toString()
        return db.findEntries { it.getProperty(PROP_UUID_MARKER) == target }.firstOrNull()
            // KeePassXC 建立的 entry 沒有 hv.id，其領域 id 來自 KDBX 自己的 UUID。
            // 讀路徑（toDomainEntry）已經這樣退回，寫路徑也必須，否則會變成
            // 「找不到 → 新建一筆」，把使用者的 entry 複製成兩份且原本那份永遠刪不掉。
            ?: db.findEntries { it.uuid == id }.firstOrNull()
    }

    private fun DomEntryWrapper.toDomainEntry(): Entry {
        val props = propertyNames.associateWith { getProperty(it) ?: "" }
        return Entry(
            // hv.id 壞掉或被使用者清空時退回 KDBX 自己的 UUID。
            // 一筆壞資料不該讓整個清單開不出來——Task 3 的 HistoryCodec 也是這個原則。
            id = props[PROP_UUID_MARKER]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: uuid,
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
