package com.videoeditor.core.model

/** Android system families available offline since the minimum supported API. */
object TextFonts {
    data class Font(val id: String, val label: String, val family: String)
    val choices = listOf(
        Font("default", "Default", "sans-serif"), Font("serif", "Serif", "serif"),
        Font("mono", "Mono", "monospace"), Font("condensed", "Condensed", "sans-serif-condensed"),
        Font("serifmono", "Serif Mono", "serif-monospace"), Font("smallcaps", "Small Caps", "sans-serif-smallcaps"),
        Font("cursive", "Cursive", "cursive"), Font("casual", "Casual", "casual"),
    )
    fun family(id: String) = choices.firstOrNull { it.id == id }?.family ?: "sans-serif"
}
