package infernics.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class VaultFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAt: Long
)

/**
 * Independent encrypted file area. Each file has its own authenticated content stream
 * and its own encrypted metadata file. A damaged metadata record cannot hide other files.
 */
class FileVaultRepository(
    private val context: Context,
    private val crypto: Crypto
) {
    private val root = File(context.filesDir, "file_vault").apply { mkdirs() }

    init {
        // Remove leftovers from an interrupted import/export; cacheDir and filesDir are private.
        root.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
        context.cacheDir.listFiles { file ->
            file.name.startsWith("secnote-export-") || file.name.startsWith("secnote-thumbnail-")
        }?.forEach { it.delete() }
        cleanupOrphans()
    }

    fun loadAll(): List<VaultFile> = root.listFiles { file -> file.name.endsWith(".data.enc") }
        .orEmpty()
        .mapNotNull { dataFile ->
            val id = dataFile.name.removeSuffix(".data.enc")
            runCatching { readMetadata(id) }.getOrNull()
        }
        .sortedByDescending { it.addedAt }

    fun import(uri: Uri): VaultFile {
        val displayName = queryDisplayName(uri).ifBlank { "Файл_${System.currentTimeMillis()}" }
        val name = displayName.substringAfterLast('/').substringAfterLast('\\').take(180)
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val dataTemp = File(root, "$id.data.tmp")
        val metaTemp = File(root, "$id.meta.tmp")
        val dataTarget = dataFile(id)
        val metaTarget = metadataFile(id)

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Не удалось открыть выбранный файл")
            val size = input.use { source ->
                dataTemp.outputStream().use { encryptedOutput ->
                    crypto.encryptStream(source, encryptedOutput)
                }
            }
            val item = VaultFile(
                id = id,
                name = name,
                mimeType = mime,
                sizeBytes = size,
                addedAt = System.currentTimeMillis()
            )
            metaTemp.writeBytes(crypto.encrypt(metadataJson(item)))
            check(dataTemp.renameTo(dataTarget)) { "Не удалось сохранить зашифрованный файл" }
            check(metaTemp.renameTo(metaTarget)) { "Не удалось сохранить метаданные файла" }
            return item
        } catch (error: Exception) {
            dataTemp.delete()
            metaTemp.delete()
            dataTarget.delete()
            metaTarget.delete()
            throw error
        }
    }

    /**
     * Decrypt an image into private cache, decode a downsampled bitmap, then always
     * remove the temporary plaintext file. Only the bounded bitmap remains in memory.
     */
    fun loadThumbnail(id: String, maxDimension: Int = 420): Bitmap? {
        require(maxDimension in 64..2048) { "Invalid thumbnail dimension" }
        val temporaryPlaintext = File.createTempFile("secnote-thumbnail-", ".tmp", context.cacheDir)
        return try {
            readMetadata(id) // Verify membership in the encrypted vault.
            dataFile(id).inputStream().use { encryptedInput ->
                temporaryPlaintext.outputStream().use { output ->
                    crypto.decryptStream(encryptedInput, output)
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporaryPlaintext.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(temporaryPlaintext.absolutePath, options)
        } catch (_: Exception) {
            null
        } finally {
            temporaryPlaintext.delete()
        }
    }

    fun export(id: String, destination: Uri) {
        readMetadata(id) // Verify that the file is a member of this vault.
        // Authenticate every chunk and the footer into a private temporary file first.
        // No plaintext is copied to the user-selected destination until the whole file passes.
        val verifiedPlaintext = File.createTempFile("secnote-export-", ".tmp", context.cacheDir)
        try {
            dataFile(id).inputStream().use { encryptedInput ->
                verifiedPlaintext.outputStream().use { temporaryOutput ->
                    crypto.decryptStream(encryptedInput, temporaryOutput)
                }
            }
            val destinationOutput = context.contentResolver.openOutputStream(destination)
                ?: error("Не удалось создать файл для экспорта")
            verifiedPlaintext.inputStream().use { source ->
                destinationOutput.use { target -> source.copyTo(target) }
            }
        } finally {
            verifiedPlaintext.delete()
        }
    }

    fun delete(id: String) {
        dataFile(id).delete()
        metadataFile(id).delete()
    }

    private fun readMetadata(id: String): VaultFile {
        val json = JSONObject(crypto.decryptToText(metadataFile(id).readBytes()))
        return VaultFile(
            id = id,
            name = json.getString("name"),
            mimeType = json.optString("mimeType", "application/octet-stream"),
            sizeBytes = json.optLong("sizeBytes", 0L),
            addedAt = json.optLong("addedAt", 0L)
        )
    }

    private fun metadataJson(item: VaultFile): String = JSONObject()
        .put("name", item.name)
        .put("mimeType", item.mimeType)
        .put("sizeBytes", item.sizeBytes)
        .put("addedAt", item.addedAt)
        .toString()

    private fun dataFile(id: String) = File(root, "$id.data.enc")
    private fun metadataFile(id: String) = File(root, "$id.meta.enc")

    private fun queryDisplayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index).orEmpty() else ""
                } else ""
            }.orEmpty()
    }.getOrDefault("")

    private fun cleanupOrphans() {
        val files = root.listFiles().orEmpty()
        val dataIds = files.filter { it.name.endsWith(".data.enc") }
            .map { it.name.removeSuffix(".data.enc") }.toSet()
        val metadataIds = files.filter { it.name.endsWith(".meta.enc") }
            .map { it.name.removeSuffix(".meta.enc") }.toSet()
        (dataIds - metadataIds).forEach { dataFile(it).delete() }
        (metadataIds - dataIds).forEach { metadataFile(it).delete() }
    }
}
