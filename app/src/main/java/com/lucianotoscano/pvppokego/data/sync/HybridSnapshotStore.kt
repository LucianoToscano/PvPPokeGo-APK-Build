package com.lucianotoscano.pvppokego.data.sync

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Versioned files are staged and verified, then activated outside a running battle.
 * The previous snapshot remains on disk for rollback.
 */
class HybridSnapshotStore(private val root: File) {
    data class Entry(val name: String, val sha256: String)
    private val safeName = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}")
    private val validSha = Regex("[0-9a-fA-F]{64}")

    private fun isSafe(name: String) =
        name.matches(safeName) && name != "." && name != ".."

    fun stage(version: String, files: Map<String, ByteArray>, expected: List<Entry>): Boolean =
        runCatching { stageChecked(version, files, expected) }.getOrDefault(false)

    private fun stageChecked(version: String, files: Map<String, ByteArray>, expected: List<Entry>): Boolean {
        require(isSafe(version))
        require(expected.isNotEmpty() && expected.map { it.name }.toSet().size == expected.size)
        require(expected.all { isSafe(it.name) && it.sha256.matches(validSha) })
        require(files.keys == expected.map { it.name }.toSet())
        if (!expected.all {
                digest(files.getValue(it.name)).equals(it.sha256, ignoreCase = true)
            }) return false

        val staged = File(root, "staged-" + version)
        if (staged.exists()) {
            if (snapshot(version) != null &&
                expected.all {
                    digest(File(staged, it.name)).equals(it.sha256, true)
                }) return true
            // An interrupted prior stage must never block subsequent safe attempts.
            require(staged.deleteRecursively())
        }
        if (!staged.mkdirs()) return false
        try {
            for ((name, bytes) in files) File(staged, name).writeBytes(bytes)
            for (entry in expected) {
                if (!digest(File(staged, entry.name))
                        .equals(entry.sha256, ignoreCase = true)) {
                    staged.deleteRecursively()
                    return false
                }
            }
            val checksums = expected.sortedBy { it.name }.joinToString("\n") {
                it.name + "\t" + it.sha256.lowercase()
            } + "\n"
            File(staged, "CHECKSUMS").writeText(checksums)
            File(staged, "READY").writeText(version) // READY is the final operation.
            return true
        } catch (error: Exception) {
            staged.deleteRecursively()
            throw error
        }
    }

    fun activateBetweenBattles(version: String, battleActive: Boolean): File? =
        runCatching { activateChecked(version, battleActive) }.getOrNull()

    private fun activateChecked(version: String, battleActive: Boolean): File? {
        if (battleActive || !isSafe(version)) return null
        val staged = snapshot(version) ?: return null
        root.mkdirs()
        val temp = File(root, "active-version.tmp")
        temp.writeText(version)
        try {
            try {
                Files.move(temp.toPath(), File(root, "active-version").toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), File(root, "active-version").toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // Atomic replace preserves the old marker if the move fails.
            runCatching { temp.delete() }
        }
        return staged
    }

    /** Display only: cheap marker read. Never use this to trust data; activeVersion() verifies SHA. */
    fun activeVersionHint(): String? = File(root, "active-version").readTextOrNull()
        ?.takeIf(::isSafe)

    fun activeVersion(): String? = File(root, "active-version").readTextOrNull()
        ?.takeIf { snapshot(it) != null }

    fun disableActiveSnapshot() {
        File(root, "active-version").delete()
    }

    /** Re-check all persisted SHA-256 hashes before trusting an active snapshot. */
    fun snapshot(version: String): File? = runCatching { snapshotChecked(version) }.getOrNull()

    private fun snapshotChecked(version: String): File? {
        if (!isSafe(version)) return null
        val folder = File(root, "staged-" + version)
        if (!folder.isDirectory ||
            File(folder, "READY").readTextOrNull() != version) return null
        val lines = File(folder, "CHECKSUMS").readTextOrNull()
            ?.lineSequence()?.filter { it.isNotBlank() }?.toList() ?: return null
        if (lines.isEmpty()) return null
        val names = mutableSetOf<String>()
        for (line in lines) {
            val pair = line.split('\t')
            if (pair.size != 2 || !isSafe(pair[0]) ||
                !names.add(pair[0]) || !pair[1].matches(Regex("[0-9a-f]{64}"))) return null
            val file = File(folder, pair[0])
            if (!file.isFile ||
                !runCatching { digest(file) == pair[1] }.getOrDefault(false)) return null
        }
        return folder
    }

    private fun File.readTextOrNull(): String? = runCatching { if (isFile) readText() else null }.getOrNull()

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // Stream persisted files: a staged dataset can contain several large JSON assets.
    // Reading them with readBytes() repeatedly created unnecessary heap spikes.
    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
