package app.candela.platform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.candela.protocol.Crypto
import app.candela.protocol.ExportGate
import java.io.File
import java.io.FileOutputStream

/**
 * Writes a verified file to the user's Downloads (audit section 7: "no partial write").
 *
 * The staging protocol is the point of this class. A naive implementation opens
 * a MediaStore stream and writes as it goes, which means any failure — a thermal
 * abort, a crash, a full disk — leaves a truncated file in Downloads that looks
 * exactly like a successful transfer. For a tool whose entire value proposition
 * is integrity over an unreliable channel, that is the worst possible failure.
 *
 * So:
 *   1. [ExportGate] must have returned Publish. This class refuses to write
 *      anything otherwise, and re-verifies rather than trusting the caller.
 *   2. Bytes go to a private cache file first.
 *   3. The staged file is re-hashed AFTER writing, to catch a truncated or
 *      corrupted write to storage itself.
 *   4. Only then is it copied into MediaStore, and on API 29+ it stays
 *      IS_PENDING=1 until the copy completes, so it is invisible to other apps
 *      until it is whole.
 *   5. Any failure at any step deletes everything and reports. Downloads is left
 *      exactly as it was.
 */
object MediaStoreExporter {

    sealed interface Result {
        data class Success(val uri: Uri, val displayName: String, val sha256Hex: String) : Result
        data class Failure(val reason: String, val cause: Throwable? = null) : Result
    }

    /**
     * @param decision must be [ExportGate.Decision.Publish]; anything else is a
     *   programming error and is refused loudly rather than written.
     * @param bytes the reassembled payload, already verified by the gate
     */
    fun export(
        context: Context,
        decision: ExportGate.Decision,
        bytes: ByteArray,
    ): Result {
        if (decision !is ExportGate.Decision.Publish) {
            val why = (decision as? ExportGate.Decision.Refuse)?.reason ?: "not approved"
            return Result.Failure("Refusing to export: $why. Nothing was written.")
        }

        // Re-verify instead of trusting the caller. Cheap (~1-2 ms on 500 KB) and
        // it makes "we only ever write verified bytes" true by construction at
        // the one place that actually touches storage.
        val hash = Crypto.sha256(bytes)
        if (app.candela.protocol.Bytes.toHex(hash) != decision.sha256Hex) {
            return Result.Failure(
                "Integrity re-check failed at export. Nothing was written.",
            )
        }

        val staged = try {
            stage(context, bytes, decision)
        } catch (e: Exception) {
            return Result.Failure("Could not stage the file for writing.", e)
        }

        return try {
            publish(context, staged, decision)
        } catch (e: Exception) {
            Result.Failure("Could not publish to Downloads. Nothing was written.", e)
        } finally {
            staged.delete()
        }
    }

    /** Step 2 + 3: write to private cache, then re-hash what actually landed. */
    private fun stage(
        context: Context,
        bytes: ByteArray,
        decision: ExportGate.Decision.Publish,
    ): File {
        val dir = File(context.cacheDir, "candela-staging").apply { mkdirs() }
        val file = File(dir, "stage-${System.nanoTime()}.bin")
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync() // survive a crash between write and verify
        }
        val readBack = file.readBytes()
        if (app.candela.protocol.Bytes.toHex(Crypto.sha256(readBack)) != decision.sha256Hex) {
            file.delete()
            throw IllegalStateException("Staged file failed re-hash; storage may be failing")
        }
        return file
    }

    /** Step 4: atomic-ish publish. IS_PENDING hides it until complete. */
    private fun publish(
        context: Context,
        staged: File,
        decision: ExportGate.Decision.Publish,
    ): Result {
        val resolver = context.contentResolver
        val name = uniqueName(context, decision.fileName)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, decision.mimeType)
            put(MediaStore.Downloads.SIZE, decision.sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }

        val uri = resolver.insert(collection, values)
            ?: return Result.Failure("MediaStore rejected the insert. Nothing was written.")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                staged.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: throw IllegalStateException("Could not open the destination stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null,
                )
            }
            return Result.Success(uri, name, decision.sha256Hex)
        } catch (e: Exception) {
            // Roll back: an unpublished pending row must not linger.
            runCatching { resolver.delete(uri, null, null) }
            return Result.Failure("Write to Downloads failed. Nothing was written.", e)
        }
    }

    /** Never silently overwrite an existing file. */
    private fun uniqueName(context: Context, desired: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore de-duplicates automatically on Q+.
            return desired
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!File(dir, desired).exists()) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var i = 1
        while (File(dir, "$stem ($i)$ext").exists() && i < 1000) i++
        return "$stem ($i)$ext"
    }
}
