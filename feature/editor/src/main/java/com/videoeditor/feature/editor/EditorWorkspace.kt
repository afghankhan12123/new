package com.videoeditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** Editor-only palette: other application destinations retain their established theme. */
internal object EditorColor {
    val DarkBackground = Color(0xFF101113)
    val DarkPrimarySurface = Color(0xFF191A1E)
    val DarkSecondarySurface = Color(0xFF25262B)
    val DarkElevatedSurface = Color(0xFF303138)
    val DarkPrimaryText = Color(0xFFF7FAFF)
    val DarkSecondaryText = Color(0xFFABADB7)
    val DarkMutedText = Color(0xFF737680)
    val DarkBorder = Color(0xFF35363E)
    val PrimaryBlue = Color(0xFF516DF0)
    val AccentCyan = Color(0xFF79E2D3)
    val AccentPurple = Color(0xFF9065F8)
    val Error = Color(0xFFF04462)
    val Success = Color(0xFF26C98B)
}

/** Editor hierarchy stays independent of typography on the rest of the app. */
@Composable
internal fun editorTypography() = MaterialTheme.typography.copy(
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
)

internal val LocalEditorPanelExpansion = compositionLocalOf<MutableState<Boolean>?> { null }

/** Bounded scrolling body and pinned actions keep confirmation reachable on compact phones. */
@Composable
internal fun EditorToolPanel(
    title: String,
    onCancel: () -> Unit,
    onReset: (() -> Unit)?,
    onApply: () -> Unit,
    applyEnabled: Boolean = true,
    cancelLabel: String = "Cancel",
    applyLabel: String = "Apply",
    tagPrefix: String = "tool",
    modifier: Modifier = Modifier,
    headerAction: (@Composable () -> Unit)? = null,
    rowAlignedBody: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val localExpansion = rememberSaveable(title) { mutableStateOf(false) }
    val expansion = LocalEditorPanelExpansion.current ?: localExpansion
    val bodyScroll = rememberScrollState()
    Surface(color = EditorColor.DarkPrimarySurface, modifier = modifier.fillMaxWidth()
        .fillMaxHeight().testTag("editor-panel")) {
        Column {
            // Actions stay beside the title, independent of the body's scroll and IME.
            Row(Modifier.fillMaxWidth().heightIn(min = EditorMetrics.TouchTarget)
                .padding(horizontal = 4.dp).testTag("editor-footer"), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel, modifier = Modifier.testTag("$tagPrefix-cancel").semantics {
                    text = androidx.compose.ui.text.AnnotatedString(cancelLabel)
                }) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.Close, cancelLabel,
                        tint = EditorColor.DarkSecondaryText, modifier = Modifier.size(EditorMetrics.Icon))
                }
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = EditorColor.DarkPrimaryText, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                headerAction?.invoke()
                if (onReset != null) IconButton(onClick = onReset) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.RestartAlt, "Reset",
                        tint = EditorColor.DarkSecondaryText, modifier = Modifier.size(EditorMetrics.Icon))
                }
                IconButton(onClick = { expansion.value = !expansion.value }, modifier = Modifier.testTag("panel-expand")) {
                    Icon(if (expansion.value) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        if (expansion.value) "Compact controls" else "Expand controls",
                        tint = EditorColor.DarkSecondaryText, modifier = Modifier.size(EditorMetrics.Icon))
                }
                IconButton(onClick = onApply, enabled = applyEnabled, modifier = Modifier.testTag("$tagPrefix-apply").semantics {
                    text = androidx.compose.ui.text.AnnotatedString(applyLabel)
                }) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.Check, applyLabel,
                        tint = if (applyEnabled) EditorColor.AccentCyan else EditorColor.DarkMutedText,
                        modifier = Modifier.size(24.dp))
                }
            }
            HorizontalDivider(color = EditorColor.DarkBorder.copy(alpha = .5f))
            Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().verticalScroll(bodyScroll)
                .padding(horizontal = EditorMetrics.PanelPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@Composable
internal fun EditorChoice(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, activeFill: Boolean = false, fontFamily: androidx.compose.ui.text.font.FontFamily? = null) {
    Box(modifier.width(IntrinsicSize.Max).widthIn(min = 48.dp).heightIn(min = 48.dp).semantics { selected = isSelected }
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clipChoice(isSelected, activeFill)
            .padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (!enabled) EditorColor.DarkMutedText else Color.White,
                style = MaterialTheme.typography.labelMedium, fontFamily = fontFamily, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

private fun Modifier.clipChoice(selected: Boolean, activeFill: Boolean): Modifier = this
    .background(if (selected && activeFill) EditorColor.PrimaryBlue else if (selected) EditorColor.PrimaryBlue.copy(alpha = .16f) else EditorColor.DarkSecondarySurface, RoundedCornerShape(8.dp))
    .border(1.dp, if (selected) EditorColor.AccentCyan else EditorColor.DarkBorder, RoundedCornerShape(8.dp))

@Composable
internal fun editorColorScheme() = darkColorScheme(
    primary = EditorColor.AccentCyan, onPrimary = Color.White,
    primaryContainer = EditorColor.DarkElevatedSurface, onPrimaryContainer = EditorColor.AccentCyan,
    secondary = EditorColor.AccentCyan, onSecondary = EditorColor.DarkBackground,
    secondaryContainer = EditorColor.DarkElevatedSurface, onSecondaryContainer = EditorColor.AccentCyan,
    background = EditorColor.DarkBackground, onBackground = EditorColor.DarkPrimaryText,
    surface = EditorColor.DarkPrimarySurface, onSurface = EditorColor.DarkPrimaryText,
    surfaceVariant = EditorColor.DarkSecondarySurface, onSurfaceVariant = EditorColor.DarkSecondaryText,
    outline = EditorColor.DarkBorder, error = EditorColor.Error,
)

/** While a panel owns a draft, timeline navigation remains available without committing other edits. */
internal fun com.videoeditor.feature.editor.timeline.TimelineCallbacks.navigationOnly() =
    com.videoeditor.feature.editor.timeline.TimelineCallbacks(
        onViewportSize = onViewportSize, onTap = {},
        onScrubBegin = onScrubBegin, onScrubBy = onScrubBy, onScrollEnd = onScrollEnd,
        onFling = onFling,
        onZoomBegin = onZoomBegin, onZoomBy = onZoomBy, onZoomEnd = onZoomEnd,
        onTrimBegin = { _, _, _ -> false }, onTrimBy = { _, _ -> }, onTrimEnd = { _, _ -> },
        onReorderBegin = { false }, onReorderTo = {}, onReorderEnd = {},
        onFreeMoveBegin = { _, _ -> false }, onFreeMoveBy = {}, onFreeMoveEnd = {},
    )

/** Tenths are derived from integer project time, with no independent UI clock. */
internal fun formatEditorTimecode(micros: Long): String {
    val tenths = micros.coerceAtLeast(0) / 100_000
    val seconds = tenths / 10
    return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}.${tenths % 10}"
}


/** Reference-style inline numeric readout; slider keeps its full accessible touch area. */
@Composable
internal fun EditorValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    display: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) EditorColor.DarkSecondaryText else EditorColor.DarkSecondaryText.copy(alpha = 0.4f), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(64.dp))
        PremiumSlider(value, onChange, modifier = modifier.weight(1f), valueRange = range, enabled = enabled)
        Text(display, color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Search uses a compact visual field within a 48dp editing target. */
@Composable
internal fun EditorSearchField(value: String, onChange: (String) -> Unit, hint: String, showSearchIcon: Boolean = true) {
    androidx.compose.foundation.text.BasicTextField(value, onChange,
        singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = EditorColor.DarkPrimaryText),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(EditorColor.AccentCyan),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(EditorColor.DarkBackground, RoundedCornerShape(8.dp))
            .border(1.dp, EditorColor.DarkBorder, RoundedCornerShape(8.dp)).padding(12.dp),
        decorationBox = { field -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (showSearchIcon) {
                EditorIcon(androidx.compose.material.icons.Icons.Outlined.Search, null,
                    tint = EditorColor.DarkMutedText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = EditorColor.DarkMutedText, style = MaterialTheme.typography.bodySmall)
                field()
            }
        } })
}

@Composable
internal fun EditorSegments(labels: List<String>, selected: String, onSelect: (String) -> Unit, disabledLabels: Set<String> = emptySet()) {
    Row(Modifier.fillMaxWidth().background(EditorColor.DarkBackground, RoundedCornerShape(10.dp))
        .border(1.dp, EditorColor.DarkBorder, RoundedCornerShape(10.dp)).padding(horizontal = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEach { label ->
            Box(Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = selected == label }
                .clickable(enabled = label !in disabledLabels) { onSelect(label) }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(if (selected == label) EditorColor.PrimaryBlue else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 3.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (label in disabledLabels) EditorColor.DarkMutedText else if (selected == label) Color.White else EditorColor.DarkSecondaryText,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun EditorNumberField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: String? = null, readOnly: Boolean = false, selected: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Column(modifier) {
        if (label != null) Text(label, color = EditorColor.DarkSecondaryText,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !readOnly) { focus.requestFocus(); keyboard?.show() }.padding(vertical = 6.dp)
            .background(EditorColor.DarkBackground, RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) EditorColor.AccentCyan else EditorColor.DarkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.text.BasicTextField(value, onChange, readOnly = readOnly, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(EditorColor.AccentCyan),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", color = if (selected) EditorColor.AccentCyan else EditorColor.DarkPrimaryText,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        }
    }
}

/** Size artwork from the panel viewport, including the narrower landscape pane. */
@Composable
internal fun EditorGalleryRow(columns: Int = 5, spacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable RowScope.(androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tileWidth = ((maxWidth - spacing * (columns - 1) - 8.dp) / columns).coerceAtLeast(48.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            content(tileWidth)
        }
    }
}
