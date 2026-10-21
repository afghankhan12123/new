package com.videoeditor.core.media.composition

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import com.videoeditor.core.model.VisualEffect
import com.videoeditor.core.model.VisualEffectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Small, isolated offscreen render of the actual production shader; never borrows preview GL objects. */
@UnstableApi
object EffectThumbnailRenderer {
    suspend fun render(context: Context, source: Bitmap, types: List<VisualEffectType>): Map<VisualEffectType, Bitmap> =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            val edge = 144
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No thumbnail EGL display" }
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "Cannot initialize thumbnail EGL" }
            var eglContext = EGL14.EGL_NO_CONTEXT
            var surface = EGL14.EGL_NO_SURFACE
            val textures = IntArray(2)
            val fbos = IntArray(1)
            val results = linkedMapOf<VisualEffectType, Bitmap>()
            try {
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE),
                    0, configs, 0, 1, count, 0) && count[0] > 0) { "No thumbnail EGL configuration" }
                eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                check(eglContext != EGL14.EGL_NO_CONTEXT) { "Cannot create thumbnail context" }
                surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, edge, EGL14.EGL_HEIGHT, edge, EGL14.EGL_NONE), 0)
                check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "Cannot bind thumbnail context" }
                GLES20.glGenTextures(2, textures, 0)
                textures.forEach { id ->
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                }
                val input = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                try {
                    val side = minOf(source.width, source.height)
                    android.graphics.Canvas(input).drawBitmap(source,
                        android.graphics.Rect((source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2),
                        android.graphics.Rect(0, 0, edge, edge), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, input, 0)
                } finally { input.recycle() }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, edge, edge, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glGenFramebuffers(1, fbos, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[1], 0)
                check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
                for (type in types) {
                    coroutine.ensureActive()
                    val program = VisualEffectsGlEffect(effects = listOf(VisualEffect.createDefault(type))).toGlShaderProgram(context, false) as androidx.media3.effect.BaseGlShaderProgram
                    try {
                        program.configure(edge, edge)
                        GlUtil.focusFramebufferUsingCurrentContext(fbos[0], edge, edge)
                        program.drawFrame(textures[0], 1_000_000L)
                        val pixels = ByteBuffer.allocateDirect(edge * edge * 4)
                        GLES20.glReadPixels(0, 0, edge, edge, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                        GlUtil.checkGlError()
                        val argb = IntArray(edge * edge)
                        for (i in argb.indices) {
                            val r = pixels.get(i * 4).toInt() and 255
                            val g = pixels.get(i * 4 + 1).toInt() and 255
                            val b = pixels.get(i * 4 + 2).toInt() and 255
                            val a = pixels.get(i * 4 + 3).toInt() and 255
                            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        results[type] = Bitmap.createBitmap(argb, edge, edge, Bitmap.Config.ARGB_8888)
                    } finally { program.release() }
                }
                coroutine.ensureActive()
                results
            } catch (failure: Throwable) {
                results.values.forEach { it.recycle() }
                throw failure
            } finally {
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    GLES20.glDeleteFramebuffers(1, fbos, 0)
                    GLES20.glDeleteTextures(2, textures, 0)
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                // eglTerminate is deliberately avoided: Android's display is shared with active playback.
                EGL14.eglReleaseThread()
            }
        }
}
