package com.videoeditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.core.model.*

/** Recipes are ordinary TextOverlay values consumed by the same caption renderer for preview/export. */
private data class CaptionRecipe(val label: String, val update: (TextOverlay) -> TextOverlay)
private val captionRecipes = listOf(
    CaptionRecipe("Clean") { it.copy(fontId = "default", colorArgb = -1, hasOutline = false, hasShadow = false, hasBackground = false) },
    CaptionRecipe("Subtitle") { it.copy(fontId = "default", colorArgb = -1, hasOutline = true, outlineColorArgb = 0xFF000000.toInt(), outlineWidthPx = 3f, hasShadow = false, hasBackground = false) },
    CaptionRecipe("Highlight") { it.copy(colorArgb = 0xFFFFD166.toInt(), hasOutline = false, hasShadow = true, hasBackground = false) },
    CaptionRecipe("Label") { it.copy(colorArgb = -1, hasBackground = true, backgroundColorArgb = 0xDD24262B.toInt(), hasOutline = false, hasShadow = false) },
    CaptionRecipe("Editorial") { it.copy(fontId = "serif", colorArgb = -1, hasOutline = false, hasBackground = false, hasShadow = true) },
    CaptionRecipe("Terminal") { it.copy(fontId = "mono", colorArgb = 0xFF79E2D3.toInt(), hasBackground = true, backgroundColorArgb = 0xD9101113.toInt(), hasShadow = false, hasOutline = false) },
)

@Composable
internal fun TextEditorSheet(
    overlay: TextOverlay, isNew: Boolean,
    onTextChange: (String) -> Unit, onFontSize: (Float) -> Unit,
    onColor: (Int) -> Unit, onAlignment: (TextAlignment) -> Unit,
    onOpacity: (Float) -> Unit, onBackground: (Boolean) -> Unit,
    onStyle: ((TextOverlay) -> TextOverlay) -> Unit,
    onDone: () -> Unit, onCancel: () -> Unit,
) {
    var category by rememberSaveable(overlay.id) { mutableStateOf("Templates") }
    val focus = remember(overlay.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val hostView = LocalView.current
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            // Focus may arrive before IME measurement. After Compose has resized
            // its workspace, retire the system's earlier pan-to-caret offset so
            // the compact preview/header remain in the visible window.
            androidx.compose.runtime.withFrameNanos { }
            hostView.requestRectangleOnScreen(android.graphics.Rect(0, 0, hostView.width, 1), true)
        }
    }
    var field by rememberSaveable(overlay.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(overlay.text, if (isNew) TextRange(0, overlay.text.length) else TextRange(overlay.text.length)))
    }
    LaunchedEffect(overlay.id) { focus.requestFocus(); keyboard?.show() }
    LaunchedEffect(overlay.text) { if (overlay.text != field.text) field = field.copy(text = overlay.text, selection = TextRange(overlay.text.length)) }
    fun close(commit: Boolean) {
        focusManager.clearFocus(); keyboard?.hide()
        if (commit) onDone() else onCancel()
    }
    EditorToolPanel(if (isNew) "Add text" else "Edit text", { close(false) }, null,
        { close(true) }, applyEnabled = overlay.text.isNotBlank(), applyLabel = "Apply", tagPrefix = "text") {
        BasicTextField(field, { field = it; onTextChange(it.text) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 92.dp)
                .background(EditorColor.DarkSecondarySurface, RoundedCornerShape(EditorMetrics.Corner))
                .border(1.dp, EditorColor.DarkBorder, RoundedCornerShape(EditorMetrics.Corner))
                .padding(12.dp).focusRequester(focus).testTag("text-entry"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(EditorColor.AccentCyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); keyboard?.hide() }),
            decorationBox = { inner -> Box { if (field.text.isEmpty()) Text("Type your text", color = EditorColor.DarkMutedText); inner() } })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Templates", "Fonts", "Styles", "Effects", "Animations", "Position").forEach { label ->
                Column(Modifier.height(48.dp).widthIn(min = 48.dp).semantics { selected = category == label }
                    .clickable { focusManager.clearFocus(); keyboard?.hide(); category = label },
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = if (category == label) Color.White else EditorColor.DarkSecondaryText)
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(18.dp).height(2.dp).background(if (category == label) EditorColor.AccentCyan else Color.Transparent))
                }
            }
        }
        // While typing, keep input and confirmation close to the keyboard. Category tap opens details.
        if (!imeVisible) when (category) {
            "Templates" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                captionRecipes.forEach { recipe ->
                    val example = recipe.update(overlay)
                    Column(Modifier.width(88.dp).clickable { onStyle(recipe.update) }.testTag("text-template-${recipe.label}"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(88.dp, 70.dp).background(EditorColor.DarkSecondarySurface, RoundedCornerShape(10.dp))
                            .border(1.dp, if (example == overlay) EditorColor.AccentCyan else Color.Transparent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text("Aa", color = Color(example.colorArgb), fontSize = 26.sp,
                                fontFamily = FontFamily(android.graphics.Typeface.create(TextFonts.choices.firstOrNull { it.id == example.fontId }?.family, 0)),
                                style = MaterialTheme.typography.titleLarge.copy(shadow = if (example.hasShadow || example.hasOutline) androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f) else null),
                                modifier = Modifier.background(if (example.hasBackground) Color(example.backgroundColorArgb) else Color.Transparent, RoundedCornerShape(4.dp)).padding(4.dp))
                        }
                        Text(recipe.label, color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            "Fonts" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorChoice("Bold", overlay.bold, { onStyle { it.copy(bold = !it.bold) } })
                    EditorChoice("Italic", overlay.italic, { onStyle { it.copy(italic = !it.italic) } })
                }
                EditorGalleryRow(columns = 4) { tileWidth -> TextFonts.choices.forEach { font ->
                    Column(Modifier.width(tileWidth).height(72.dp).background(EditorColor.DarkSecondarySurface, RoundedCornerShape(8.dp))
                        .border(1.dp, if (overlay.fontId == font.id) EditorColor.AccentCyan else Color.Transparent, RoundedCornerShape(8.dp))
                        .semantics { selected = overlay.fontId == font.id }.clickable { onStyle { it.copy(fontId = font.id) } },
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Aa", fontFamily = FontFamily(android.graphics.Typeface.create(font.family, 0)), fontSize = 24.sp)
                        Text(font.label, style = MaterialTheme.typography.labelSmall)
                    }
                } }
                EditorValueSlider("Size", overlay.fontSizeSp.coerceIn(8f, 200f), 8f..200f, "${overlay.fontSizeSp.toInt()}", onFontSize, Modifier.testTag("text-size-slider"))
            }
            "Styles" -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFFFFFFFF, 0xFF101113, 0xFFFFD166, 0xFFEF7385, 0xFF79E2D3, 0xFF9BA9FF).forEach { argb ->
                        Box(Modifier.size(48.dp).border(if (argb.toInt() == overlay.colorArgb) 2.dp else 0.dp, EditorColor.AccentCyan, CircleShape)
                            .padding(6.dp).clip(CircleShape).background(Color(argb.toInt()))
                            .semantics { contentDescription = "Text color ${argb.toString(16)}"; selected = argb.toInt() == overlay.colorArgb }.clickable { onColor(argb.toInt()) })
                    }
                }
                Row(Modifier.fillMaxWidth()) { TextAlignment.entries.forEach { alignment -> EditorChoice(alignment.name, overlay.alignment == alignment, { onAlignment(alignment) }, Modifier.weight(1f)) } }
                EditorValueSlider("Opacity", overlay.opacity, 0f..1f, "${(overlay.opacity * 100).toInt()}%", onOpacity)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Background", Modifier.weight(1f)); Switch(overlay.hasBackground, onBackground) }
            }
            "Effects" -> {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Shadow", Modifier.weight(1f)); Switch(overlay.hasShadow, { enabled -> onStyle { it.copy(hasShadow = enabled) } }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Outline", Modifier.weight(1f)); Switch(overlay.hasOutline, { enabled -> onStyle { it.copy(hasOutline = enabled) } }) }
                if (overlay.hasOutline) EditorValueSlider("Stroke", overlay.outlineWidthPx.coerceIn(1f, 12f), 1f..12f, "${overlay.outlineWidthPx.toInt()}", { width -> onStyle { it.copy(outlineWidthPx = width) } })
            }
            "Animations" -> {
                Text("In", style = MaterialTheme.typography.labelSmall, color = EditorColor.DarkSecondaryText)
                Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(TextAnimation.None, TextAnimation.FadeIn, TextAnimation.SlideIn, TextAnimation.ScaleIn).forEach { value ->
                    EditorChoice(value.name, overlay.enterAnimation == value, { onStyle { it.copy(enterAnimation = value) } })
                } }
                Text("Out", style = MaterialTheme.typography.labelSmall, color = EditorColor.DarkSecondaryText)
                Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(TextAnimation.None, TextAnimation.FadeOut, TextAnimation.SlideOut, TextAnimation.ScaleOut).forEach { value ->
                    EditorChoice(value.name, overlay.exitAnimation == value, { onStyle { it.copy(exitAnimation = value) } })
                } }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wrap to canvas", Modifier.weight(1f))
                    Switch(overlay.maxWidthFraction != null, { enabled -> onStyle { it.copy(maxWidthFraction = if (enabled) .9f else null) } })
                }
                overlay.maxWidthFraction?.let { width -> EditorValueSlider("Text width", width.coerceIn(.1f, 1f), .1f..1f,
                    "${(width * 100).toInt()}%", { v -> onStyle { it.copy(maxWidthFraction = v) } }) }
                Text("Amber bounds indicate text outside the canvas.", style = MaterialTheme.typography.labelSmall, color = EditorColor.DarkSecondaryText)
                EditorValueSlider("X", overlay.positionX, 0f..1f, "${(overlay.positionX * 100).toInt()}%", { v -> onStyle { it.copy(positionX = v) } })
                EditorValueSlider("Y", overlay.positionY, 0f..1f, "${(overlay.positionY * 100).toInt()}%", { v -> onStyle { it.copy(positionY = v) } })
                EditorValueSlider("Scale", overlay.scale.coerceIn(.1f, 4f), .1f..4f, "${"%.1f".format(overlay.scale)}×", { v -> onStyle { it.copy(scale = v) } })
                EditorValueSlider("Rotation", overlay.rotationDegrees.coerceIn(-180f, 180f), -180f..180f, "${overlay.rotationDegrees.toInt()}°", { v -> onStyle { it.copy(rotationDegrees = v) } })
                EditorValueSlider("Duration", (overlay.durationMicros / 1_000_000f).coerceIn(.1f, 60f), .1f..60f, "${"%.1f".format(overlay.durationMicros / 1_000_000f)} s", { v -> onStyle { it.copy(durationMicros = (v * 1_000_000).toLong()) } })
            }
        }
    }
}
