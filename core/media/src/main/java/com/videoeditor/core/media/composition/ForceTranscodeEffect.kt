package com.videoeditor.core.media.composition

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.RgbMatrix

/**
 * An identity pixel passthrough whose only purpose is to defeat Media3's
 * transmux shortcut.
 *
 * Media3 1.5.1's `TransformerUtil.shouldTranscodeVideo` transmuxes — copies the
 * source H.264 bitstream straight into the output container — whenever a
 * single-item composition's effects are all provably no-ops. The app's canvas
 * Presentation is a no-op when source and export dimensions match, so a bare
 * resolution/quality export would otherwise never create an encoder and the
 * requested bitrate would be silently ignored (verified on device: three
 * quality presets produced byte-identical transmuxed files). Because this
 * effect is never a no-op, its presence forces the decode → effects → encode
 * pass every quality/resolution setting is documented to produce.
 *
 * The pixels pass through unchanged: the identity RGB matrix draws 1:1.
 */
@UnstableApi
internal class ForceTranscodeEffect : GlEffect {
    private val identity = object : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
            FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        identity.toGlShaderProgram(context, useHdr)

    // Never a no-op: that is the entire contract. Media3 skips no-op-only chains.
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}
