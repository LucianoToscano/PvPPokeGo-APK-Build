package com.lucianotoscano.pvppokego.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Imports the user-selected image-cache ZIP through Android Storage Access Framework.
 * No broad storage permission, traversal, arbitrary file extension, or UI thread IO.
 */
internal object PokemonImageImporter {
    data class Result(val imported: Int, val skipped: Int)
    private const val MAX_WEBP_BYTES = 2 * 1024 * 1024
    private const val MAX_FILES = 1500
    private val allowedNames = PokemonImageCatalog.allImages()
        .map { it.fileName }.toSet()

    suspend fun importZip(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val root = File(context.applicationContext.filesDir, "pokemon_images")
        check(root.isDirectory || root.mkdirs()) { "Não foi possível criar pasta de imagens" }

        var imported = 0
        var skipped = 0
        var processed = 0
        val source = context.contentResolver.openInputStream(uri)
            ?: error("Arquivo ZIP não pôde ser aberto")
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                processed++
                require(processed <= MAX_FILES) { "Arquivo com entradas demais" }
                val name = entry.name
                if (entry.isDirectory || name !in allowedNames) {
                    skipped++
                    zip.closeEntry()
                    continue
                }
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count == -1) break
                    require(bytes.size() + count <= MAX_WEBP_BYTES) {
                        "Imagem muito grande: $name"
                    }
                    bytes.write(buffer, 0, count)
                }
                val content = bytes.toByteArray()
                val validHeader = content.size > 200 &&
                    content.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                    content.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (validHeader) BitmapFactory.decodeByteArray(content, 0, content.size, options)
                if (!validHeader || options.outWidth !in 16..256 ||
                    options.outHeight !in 16..256) {
                    skipped++
                    zip.closeEntry()
                    continue
                }
                val destination = File(root, name)
                val temp = File(root, name + ".import")
                try {
                    temp.outputStream().use { it.write(content) }
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    imported++
                } finally {
                    temp.delete()
                }
                zip.closeEntry()
            }
        }
        require(imported > 0) { "Nenhum retrato WebP compatível encontrado no ZIP" }
        Result(imported, skipped)
    }
}
