package com.lucianotoscano.pvppokego.data.sync

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class HybridSnapshotStoreTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `staging hashes and atomic active version defend rollback`() {
        val temp = Files.createTempDirectory("pvppokego-hybrid-test").toFile()
        try {
            val store = HybridSnapshotStore(temp)
            val first = "valid".toByteArray()
            val entry = HybridSnapshotStore.Entry("gamemaster.json", hash(first))
            assertFalse(store.stage("v1", mapOf("gamemaster.json" to "bad".toByteArray()), listOf(entry)))
            assertTrue(store.stage("v1", mapOf("gamemaster.json" to first), listOf(entry)))
            assertNull(store.activateBetweenBattles("v1", battleActive = true))
            assertNull(store.activeVersion())
            assertNotNull(store.activateBetweenBattles("v1", battleActive = false))
            assertEquals("v1", store.activeVersion())
            temp.resolve("staged-v1/gamemaster.json").writeText("tampered")
            assertNull(store.snapshot("v1"))
            assertNull(store.activeVersion())
            store.disableActiveSnapshot()
            assertNull(store.activeVersionHint())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun `rollback leaves previous staged data available`() {
        val root = Files.createTempDirectory("hybrid-rollback").toFile()
        try {
            val store = HybridSnapshotStore(root)
            val bytes = "valid".toByteArray()
            assertTrue(store.stage("v2", mapOf("data.json" to bytes),
                listOf(HybridSnapshotStore.Entry("data.json", hash(bytes)))))
            assertNotNull(store.activateBetweenBattles("v2", false))
            assertEquals("v2", store.activeVersion())
            store.disableActiveSnapshot()
            assertNull(store.activeVersion())
            assertNotNull(store.snapshot("v2"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `staged corruption can be recovered while missing checksums invalidates`() {
        val root = Files.createTempDirectory("hybrid-checksums").toFile()
        try {
            val store = HybridSnapshotStore(root)
            val valid = "trusted-data".toByteArray()
            val files = mapOf("gamemaster.json" to valid)
            val hashes = listOf(HybridSnapshotStore.Entry("gamemaster.json", hash(valid)))
            assertTrue(store.stage("v3", files, hashes))
            val stage = store.snapshot("v3")!!
            stage.resolve("gamemaster.json").writeText("tampered")
            assertNull(store.snapshot("v3"))
            assertNull(store.activateBetweenBattles("v3", false))
            assertTrue(store.stage("v3", files, hashes))
            assertNotNull(store.activateBetweenBattles("v3", false))
            stage.resolve("CHECKSUMS").delete()
            assertNull(store.activeVersion())
        } finally { root.deleteRecursively() }
    }

    @Test fun `rejects traversal duplicate names and incomplete manifests`() {
        val root = Files.createTempDirectory("hybrid-adversarial").toFile()
        try {
            val store = HybridSnapshotStore(root)
            val bytes = "data".toByteArray()
            val entry = HybridSnapshotStore.Entry("a", hash(bytes))
            try {
                store.stage("v1", mapOf("../bad" to bytes),
                    listOf(HybridSnapshotStore.Entry("../bad", hash(bytes))))
                fail("Traversal must be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                store.stage("v1", emptyMap(), listOf(entry))
                fail("Missing files must be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                store.stage("v1", mapOf("a" to bytes), listOf(entry, entry))
                fail("Duplicate names must be rejected")
            } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }

}
