package com.videoeditor.feature.editor

import androidx.compose.runtime.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Mutually exclusive navigation; panel drafts remain separate from the selected project object. */
internal enum class EditorTool {
    Main, Trim, Split, Speed, Volume, Automation, Equalizer, Balance, Ducking, Fade,
    Transform, Effects, Mask, Chroma, Blend, PhotoDuration, Canvas, Audio, Pip, Sticker,
    StickerLibrary, EffectLibrary, Filters, Adjust, Text, Transition,
}

@Stable
internal class EditorToolState(initial: EditorTool = EditorTool.Main) {
    var active by mutableStateOf(initial)
    fun close(tool: EditorTool) { if (active == tool) active = EditorTool.Main }
    fun flag(tool: EditorTool): MutableState<Boolean> = object : MutableState<Boolean> {
        override var value: Boolean
            get() = active == tool
            set(value) { if (value) active = tool else close(tool) }
        override fun component1() = value
        override fun component2(): (Boolean) -> Unit = { value = it }
    }
    fun labels(routes: Map<String, EditorTool>): MutableState<String?> = object : MutableState<String?> {
        override var value: String?
            get() = routes.entries.firstOrNull { it.value == active }?.key
            set(value) {
                if (value != null) active = requireNotNull(routes[value])
                else if (active in routes.values) active = EditorTool.Main
            }
        override fun component1() = value
        override fun component2(): (String?) -> Unit = { value = it }
    }
    fun <T> draft(tool: EditorTool, storage: MutableState<T?>): MutableState<T?> = object : MutableState<T?> {
        override var value: T?
            get() = if (active == tool) storage.value else null
            set(value) { storage.value = value; if (value != null) active = tool else close(tool) }
        override fun component1() = value
        override fun component2(): (T?) -> Unit = { value = it }
    }

}
