package com.videoeditor.core.media.composition

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import com.videoeditor.core.model.ScaleMode

/** Both matrices have the same output size, so Fit/Fill is a uniform update in the live graph. */
@UnstableApi
internal class CanvasPresentationEffect(width: Int, height: Int, private val mode: () -> ScaleMode) : MatrixTransformation {
    private val fit = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
    private val fill = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val size = fit.configure(inputWidth, inputHeight)
        check(size == fill.configure(inputWidth, inputHeight))
        return size
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix =
        (if (mode() == ScaleMode.Fit) fit else fill).getMatrix(presentationTimeUs)

    override fun isNoOp(inputWidth: Int, inputHeight: Int) = false
}
