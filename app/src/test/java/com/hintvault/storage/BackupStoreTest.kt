package com.hintvault.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun subject() = FileBackupStore(folder.root)

    @Test
    fun startsEmpty() {
        assertNull(subject().get())
    }

    @Test
    fun putThenGetRoundTrips() {
        val store = subject()
        store.put("v1".toByteArray())
        assertArrayEquals("v1".toByteArray(), store.get())
    }

    @Test
    fun putOverwritesPreviousBackup() {
        val store = subject()
        store.put("v1".toByteArray())
        store.put("v2".toByteArray())
        assertArrayEquals("v2".toByteArray(), store.get())
    }

    @Test
    fun createsDirectoryIfMissing() {
        val nested = java.io.File(folder.root, "nested/dir")
        val store = FileBackupStore(nested)
        store.put("v1".toByteArray())
        assertArrayEquals("v1".toByteArray(), store.get())
    }
}
