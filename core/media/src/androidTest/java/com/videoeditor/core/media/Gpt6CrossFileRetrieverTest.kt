package com.videoeditor.core.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Temporary cross-over diagnostic.
 *
 * The same exported video is probed with [MediaMetadataRetriever] on both devices. The
 * file is pushed into the shared Downloads collection beforehand and is located here via
 * MediaStore, so each device can be asked to decode a bitstream produced by the other.
 *
 * This separates a defect in the produced bitstream from a defect in the device's own
 * frame retrieval / decode path.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Gpt6CrossFileRetrieverTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun findDownload(displayName: String): Uri? {
        val file = java.io.File(
            context.getExternalFilesDir(null),
            "crossover/$displayName"
        )
        return if (file.exists()) Uri.fromFile(file) else null
    }

    private fun sampledLuma(bitmap: Bitmap): Double {
        var sum = 0L
        var count = 0
        for (y in 50 until bitmap.height - 50 step 20) {
            for (x in 50 until bitmap.width - 50 step 20) {
                val px = bitmap.getPixel(x, y)
                sum += ((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / 3
                count++
            }
        }
        return sum.toDouble() / count
    }

    @Test
    fun diagnostic_cross_file_retriever() {
        val files = listOf("crossover_emu.mp4", "crossover_ne2211.mp4")
        val queries = listOf(
            500_000L, 999_000L, 1_000_000L, 1_001_000L, 1_500_000L,
            1_999_000L, 2_000_000L, 2_001_000L, 2_500_000L, 2_999_000L, 3_000_000L
        )

        val report = StringBuilder()
        report.append("device=").append(android.os.Build.MODEL).append('\n')

        for (name in files) {
            val uri = findDownload(name)
            report.append("== ").append(name).append(" uri=").append(uri).append('\n')
            if (uri == null) {
                report.append("   MISSING\n")
                continue
            }
            for ((label, option) in listOf(
                "CLOSEST" to MediaMetadataRetriever.OPTION_CLOSEST,
                "CLOSEST_SYNC" to MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                "PREVIOUS_SYNC" to MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
            )) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(uri.path!!)
                for (t in queries) {
                    val bmp = retriever.getFrameAtTime(t, option)
                    if (bmp == null) {
                        report.append("   ").append(label).append(" t=").append(t).append(" -> null\n")
                    } else {
                        report.append("   ").append(label).append(" t=").append(t)
                            .append(" luma=").append(sampledLuma(bmp))
                            .append(" size=").append(bmp.width).append('x').append(bmp.height)
                            .append('\n')
                        bmp.recycle()
                    }
                }
                retriever.release()
            }
        }

        val text = report.toString()
        android.util.Log.i("Gpt6Cross", text)
        try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "gpt6_crossover_${android.os.Build.MODEL.replace(' ', '_')}.txt")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (error: Exception) {
            android.util.Log.e("Gpt6Cross", "evidence write failed: ${error.message}")
        }
        assertTrue("report must be produced", text.isNotEmpty())
    }
}
