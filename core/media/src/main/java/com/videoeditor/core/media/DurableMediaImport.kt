package com.videoeditor.core.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/** Called on IO. A durable grant or completed private copy is required before adding a source. */
object DurableMediaImport {
    fun retain(context: Context, uri: Uri): Uri? {
        if (uri.scheme == "file") return uri
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri
        } catch (_: SecurityException) {
            // Some pickers provide temporary grants only; copy while that grant is still alive.
        } catch (_: IllegalArgumentException) {
            // Providers without persistable grants use the same private-copy path.
        }
        var partial: File? = null
        return try {
            val directory = File(context.filesDir, "imported").apply { check(isDirectory || mkdirs()) }
            val extension = context.contentResolver.getType(uri)?.let {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
            } ?: "media"
            val destination = File(directory, "import_${UUID.randomUUID()}.$extension")
            val pending = File(directory, "${destination.name}.partial")
            partial = pending
            val input = context.contentResolver.openInputStream(uri) ?: error("The provider returned no source stream")
            input.use { source -> pending.outputStream().use { output -> source.copyTo(output); output.fd.sync() } }
            check(pending.length() > 0L && pending.renameTo(destination)) { "Could not retain imported media" }
            Uri.fromFile(destination)
        } catch (failure: Exception) {
            Log.w("DurableMediaImport", "Source could not be retained", failure)
            // Only this invocation's incomplete private copy is removed; originals are never written.
            partial?.delete()
            null
        }
    }
}
