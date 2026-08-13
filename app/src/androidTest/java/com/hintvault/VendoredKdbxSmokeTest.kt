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
