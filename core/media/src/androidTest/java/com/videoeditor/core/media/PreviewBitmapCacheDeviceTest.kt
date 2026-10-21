package com.videoeditor.core.media

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.videoeditor.core.media.preview.PreviewBitmapCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewBitmapCacheDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private class CountingLoader(val delegate: BitmapLoader) : BitmapLoader by delegate {
        var decodes = 0
        var loads = 0
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> { decodes++; return delegate.decodeBitmap(data) }
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> { loads++; return delegate.loadBitmap(uri) }
    }
    @Test fun same_bytes_reuse_but_same_uri_size_and_time_with_new_content_redecode() {
        val file = File(context.filesDir, "photo-cache-${System.nanoTime()}.png")
        val loader = CountingLoader(DataSourceBitmapLoader(context))
        val cache = PreviewBitmapCache(context.filesDir)
        try {
            val red = png(Color.RED); val blue = png(Color.BLUE); val length = maxOf(red.size, blue.size)
            file.writeBytes(red.copyOf(length)); val stamp = file.lastModified(); val uri = Uri.fromFile(file)
            val first = cache.load(loader, uri).get(5, TimeUnit.SECONDS)
            assertEquals(Color.RED, first.getPixel(32, 32))
            val second = cache.load(loader, uri).get(5, TimeUnit.SECONDS)
            assertNotSame("Media3 owns and recycles each delivered bitmap", first, second)
            assertEquals(Color.RED, second.getPixel(32, 32)); assertEquals(1, loader.decodes)
            first.recycle(); second.recycle()
            val afterConsumerRecycle = cache.load(loader, uri).get(5, TimeUnit.SECONDS)
            assertFalse(afterConsumerRecycle.isRecycled); assertEquals(Color.RED, afterConsumerRecycle.getPixel(32, 32))
            assertEquals(1, loader.decodes)
            file.writeBytes(blue.copyOf(length)); assertTrue(file.setLastModified(stamp))
            assertEquals(length.toLong(), file.length()); assertEquals(stamp, file.lastModified())
            val changed = cache.load(loader, uri).get(5, TimeUnit.SECONDS)
            assertEquals(Color.BLUE, changed.getPixel(32, 32)); assertEquals(2, loader.decodes)
            cache.clear(); assertFalse("Active graph bitmap must not be recycled", changed.isRecycled)
            cache.load(loader, uri).get(5, TimeUnit.SECONDS); assertEquals(3, loader.decodes)
        } finally { cache.clear(); file.delete() }
    }
    @Test fun late_completion_after_clear_and_decode_failure_cannot_populate_cache() {
        val file = File(context.filesDir, "photo-cache-epoch-${System.nanoTime()}.png").apply { writeBytes(png(Color.RED)) }
        val pending = ArrayDeque<SettableFuture<Bitmap>>()
        var decodes = 0
        val loader = object : BitmapLoader {
            override fun supportsMimeType(mimeType: String) = true
            override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = error("Expected owned snapshot")
            override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = SettableFuture.create<Bitmap>().also { decodes++; pending.addLast(it) }
        }
        val cache = PreviewBitmapCache(context.filesDir)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            cache.load(loader, Uri.fromFile(file)); cache.clear(); pending.removeFirst().set(bitmap)
            cache.load(loader, Uri.fromFile(file)); assertEquals(2, decodes)
            pending.removeFirst().setException(java.io.IOException("injected decode failure"))
            cache.load(loader, Uri.fromFile(file)); assertEquals(3, decodes)
            pending.removeFirst().set(bitmap)
            assertNotSame(bitmap, cache.load(loader, Uri.fromFile(file)).get()); assertEquals(3, decodes)
        } finally { cache.clear(); bitmap.recycle(); file.delete() }
    }
    @Test fun oversized_sources_and_files_outside_owned_directory_bypass_cache() {
        val loader = CountingLoader(DataSourceBitmapLoader(context)); val cache = PreviewBitmapCache(context.filesDir)
        val outside = File(context.cacheDir, "outside-${System.nanoTime()}.png").apply { writeBytes(png(Color.GREEN)) }
        val large = File(context.filesDir, "large-${System.nanoTime()}.png").apply { writeBytes(png(Color.GREEN).copyOf(1_048_577)) }
        try {
            for (file in listOf(outside, large)) repeat(2) { cache.load(loader, Uri.fromFile(file)).get(5, TimeUnit.SECONDS) }
            assertEquals(4, loader.loads); assertEquals(0, loader.decodes)
        } finally { cache.clear(); outside.delete(); large.delete() }
    }
    @Test fun large_decoded_bitmap_is_not_retained() {
        val file = File(context.filesDir, "large-decoded-${System.nanoTime()}.png").apply { writeBytes(png(Color.RED)) }
        val bitmap = Bitmap.createBitmap(2049, 2049, Bitmap.Config.ARGB_8888)
        var decodes = 0
        val loader = object : BitmapLoader {
            override fun supportsMimeType(mimeType: String) = true
            override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = error("Expected snapshot")
            override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> { decodes++; return Futures.immediateFuture(bitmap) }
        }
        val cache = PreviewBitmapCache(context.filesDir)
        try { repeat(2) { cache.load(loader, Uri.fromFile(file)).get() }; assertEquals(2, decodes) }
        finally { cache.clear(); bitmap.recycle(); file.delete() }
    }
}
