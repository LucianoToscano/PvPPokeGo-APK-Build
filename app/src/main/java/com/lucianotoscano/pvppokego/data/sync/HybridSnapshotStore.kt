package com.lucianotoscano.pvppokego.data.sync

import java.io.File
import java.security.MessageDigest

/**
 * Stages verified snapshots without changing the currently active battle dataset.
 * The caller must supply a private app directory and download files outside battles.
 * Activation is deliberately explicit and must happen only between sessions.
 */
class HybridSnapshotStore(private val root: File) {
    data class Entry(val name: String, val sha256: String)
    private val safeName = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}")

    fun stage(version: String, files: Map<String, ByteArray>, expected: List<Entry>): Boolean {
        require(version.matches(safeName) && version != "." && version != "..")
        require(expected.isNotEmpty() && expected.map { it.name }.toSet().size == expected.size)
        require(expected.all { it.name.matches(safeName) && it.name != "." && it.name != ".." })
        require(files.keys == expected.map { it.name }.toSet())
        if (!expected.all { entry ->
                entry.sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
                    digest(files.getValue(entry.name)).equals(entry.sha256, ignoreCase = true)
            }) return false
        val staged = File(root, "staged-$version")
        if (staged.exists()) return false
        if (!staged.mkdirs()) return false
        try {
            for ((name, bytes) in files) File(staged, name).writeBytes(bytes)
            for (entry in expected) {
                if (digest(File(staged, entry.name).readBytes()) != entry.sha256.lowercase()) {
                    staged.deleteRecursively()
                    return false
                }
            }
            File(staged, "READY").writeText(version)
            return true
        } catch (error: Exception) {
            staged.deleteRecursively()
            throw error
        }
    }

    /** Returns the validated directory to be loaded for the NEXT battle only. */
    fun activateBetweenBattles(version: String, battleActive: Boolean): File? {
        if (battleActive || !version.matches(safeName) || version == "." || version == "..") return null
        val staged = File(root, "staged-$version")
        if (!staged.isDirectory || File(staged, "READY").readTextOrNull() != version) return null
        // The previous active snapshot is kept for rollback. Never overwrite it in-place.
        File(root, "active-version").apply {
            parentFile?.mkdirs()
            val temp = File(root, "active-version.tmp")
            temp.writeText(version)
            try {
                java.nio.file.Files.move(
                    temp.toPath(), toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(), toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
        return staged
    }

    fun activeVersion(): String? = File(root, "active-version").readTextOrNull()
        ?.takeIf { snapshot(it) != null }

    /** Roll back to the bundled assets if a snapshot cannot be loaded. */
    fun disableActiveSnapshot() {
        File(root, "active-version").delete()
    }
    fun snapshot(version: String): File? {
        if (!version.matches(safeName) || version == "." || version == "..") return null
        return File(root, "staged-$version").takeIf {
            it.isDirectory && File(it, "READY").readTextOrNull() == version
        }
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText() else null

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
