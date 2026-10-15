package com.lucianotoscano.pvppokego.data.sync

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class HybridSnapshotStoreTest {
    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun verifiesChecksumAndActivatesOnlyBetweenBattles() {
        val root = Files.createTempDirectory("hybrid-snapshot").toFile()
        try {
            val store = HybridSnapshotStore(root)
            val bytes = """{"pokemon":[]}""".toByteArray()
            assertFalse(store.stage("v1", mapOf("gamemaster.json" to bytes),
                listOf(HybridSnapshotStore.Entry("gamemaster.json", "0".repeat(64)))))
            assertTrue(store.stage("v1", mapOf("gamemaster.json" to bytes),
                listOf(HybridSnapshotStore.Entry("gamemaster.json", sha(bytes)))))
            assertNull(store.activateBetweenBattles("v1", battleActive = true))
            assertNull(store.activeVersion())
            assertNotNull(store.activateBetweenBattles("v1", battleActive = false))
            assertEquals("v1", store.activeVersion())
            assertArrayEquals(bytes, store.snapshot("v1")!!.resolve("gamemaster.json").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun rollbackRestoresBundledDataSelection() {
        val root = Files.createTempDirectory("hybrid-rollback").toFile()
        try {
            val store = HybridSnapshotStore(root)
            val bytes = "valid".toByteArray()
            assertTrue(store.stage("v2", mapOf("data.json" to bytes),
                listOf(HybridSnapshotStore.Entry("data.json", sha(bytes)))))
            assertNotNull(store.activateBetweenBattles("v2", false))
            assertEquals("v2", store.activeVersion())
            store.disableActiveSnapshot()
            assertNull(store.activeVersion())
            assertNotNull(store.snapshot("v2"))
        } finally { root.deleteRecursively() }
    }


    @Test fun rejectsCorruptedOrDeletedStagedDataAndRecovers() {
        val root = Files.createTempDirectory("hybrid-checksums").toFile()
        try {
            val store = HybridSnapshotStore(root)
            val good = "trusted-data".toByteArray()
            val name = "gamemaster.json"
            val expected = listOf(HybridSnapshotStore.Entry(name, sha(good)))
            assertTrue(store.stage("v3", mapOf(name to good), expected))
            val staged = store.snapshot("v3")!!
            staged.resolve(name).writeText("tampered")
            assertNull("Tampered bytes must reject whole snapshot", store.snapshot("v3"))
            assertNull(store.activateBetweenBattles("v3", false))
            assertTrue("Re-stage must replace corrupt partial state",
                store.stage("v3", mapOf(name to good), expected))
            assertNotNull(store.activateBetweenBattles("v3", false))
            staged.resolve("CHECKSUMS").delete()
            assertNull("Missing ledger must invalidate active pointer", store.activeVersion())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsPathTraversalAndIncompleteSets() {
        val root = Files.createTempDirectory("hybrid-snapshot").toFile()
        try {
            val store = HybridSnapshotStore(root)
            val bytes = "data".toByteArray()
            try {
                store.stage("v1", mapOf("../bad" to bytes),
                    listOf(HybridSnapshotStore.Entry("../bad", sha(bytes))))
                fail("Traversal must be rejected")
            } catch (_: IllegalArgumentException) {}
            try {
                store.stage("v1", emptyMap(), listOf(HybridSnapshotStore.Entry("a", sha(bytes))))
                fail("Missing files must be rejected")
            } catch (_: IllegalArgumentException) {}
        } finally { root.deleteRecursively() }
    }
}
