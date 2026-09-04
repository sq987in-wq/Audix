package com.candela.platform

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.candela.protocol.Bytes
import com.candela.protocol.Crypto
import java.io.File
import java.io.FileOutputStream

object VerifiedFileWriter {
    /**
     * Writes only after SHA-256 matches. Never persists a partial payload.
     */
    fun save(
        context: Context,
        fileName: String,
        mime: String,
        data: ByteArray,
        expectedHash: ByteArray,
    ): String {
        val actual = Crypto.sha256(data)
        if (!Bytes.eq(actual, expectedHash)) {
            throw IllegalStateException("SHA-256 mismatch — file discarded, no partial write")
        }
        val safeName = fileName.ifBlank { "candela-file.bin" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val resolvedMime = mime.ifBlank { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IllegalStateException("MediaStore open failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return uri.toString()
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, safeName)
        FileOutputStream(file).use { it.write(data) }
        return file.absolutePath
    }
}
