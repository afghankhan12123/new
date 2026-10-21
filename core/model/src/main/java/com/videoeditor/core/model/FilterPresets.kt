package com.videoeditor.core.model

/** Shared affine RGB presets: shader uniforms and thumbnail color matrices use the same values. */
object FilterPresets {
    val names = listOf(null to "None", "vivid" to "Vivid", "film" to "Film", "warm" to "Warm", "cool" to "Cool",
        "bw" to "Black & White", "vintage" to "Vintage")
    data class Grade(val matrix: FloatArray, val offset: FloatArray)
    fun grade(id: String?): Grade {
        val rows = when(id) {
            "warm" -> arrayOf(floatArrayOf(1.12f,0f,0f),floatArrayOf(0f,1.02f,0f),floatArrayOf(0f,0f,0.88f))
            "cool" -> arrayOf(floatArrayOf(0.88f,0f,0f),floatArrayOf(0f,1.02f,0f),floatArrayOf(0f,0f,1.12f))
            "vintage" -> arrayOf(floatArrayOf(.393f,.769f,.189f),floatArrayOf(.349f,.686f,.168f),floatArrayOf(.272f,.534f,.131f))
            else -> {
                val saturation = when(id) { "bw" -> 0f; "vivid" -> 1.35f; "film" -> .78f; else -> 1f }
                val contrast = when(id) { "vivid" -> 1.12f; "film" -> 1.1f; else -> 1f }
                val luma = floatArrayOf(.2126f,.7152f,.0722f)
                Array(3) { row -> FloatArray(3) { column -> ((1f - saturation) * luma[column] + if (row == column) saturation else 0f) * contrast } }
            }
        }
        val matrix = FloatArray(16).apply {
            for (row in 0..2) for (column in 0..2) this[column * 4 + row] = rows[row][column]
            this[15] = 1f
        }
        val offset = if (id in listOf("vivid", "film")) -.02f else 0f
        return Grade(matrix, floatArrayOf(offset, offset, offset))
    }
}
