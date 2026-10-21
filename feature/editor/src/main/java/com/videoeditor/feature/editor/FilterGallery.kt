package com.videoeditor.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.FilterPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses the same affine matrices as the production SDR filter shader. */
@Composable
internal fun FilterGallery(source: Bitmap?, selectedId: String?, onSelect: (String?) -> Unit) {
    val swatches by produceState<Map<String?, Bitmap>>(emptyMap(), source) {
        val input = source ?: return@produceState
        value = withContext(Dispatchers.Default) {
            FilterPresets.names.associate { (id, _) ->
                val grade = FilterPresets.grade(id)
                val matrix = FloatArray(20)
                for (r in 0..2) {
                    for (c in 0..2) matrix[r * 5 + c] = grade.matrix[c * 4 + r]
                    matrix[r * 5 + 4] = grade.offset[r] * 255f
                }
                matrix[18] = 1f
                val target = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(target).drawBitmap(input, 0f, 0f, android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                })
                id to target
            }
        }
    }
    FilterPresets.names.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (id, label) ->
                Column(Modifier.weight(1f).semantics { selected = selectedId == id }.clickable { onSelect(id) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.12f).clip(RoundedCornerShape(10.dp))
                        .background(EditorColor.DarkSecondarySurface)
                        .border(if (selectedId == id) 2.dp else 1.dp, if (selectedId == id) EditorColor.AccentCyan else EditorColor.DarkBorder, RoundedCornerShape(10.dp))) {
                        swatches[id]?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Text(label, color = if (selectedId == id) EditorColor.AccentCyan else Color.White,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
