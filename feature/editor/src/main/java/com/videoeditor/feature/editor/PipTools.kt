package com.videoeditor.feature.editor

import com.videoeditor.feature.editor.timeline.TimelineCallbacks
import com.videoeditor.feature.editor.timeline.timelineLaneInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.feature.editor.EditorColor as VEColor
import com.videoeditor.core.model.OverlayClip
import kotlin.math.min

/** Each lane shares the editor viewport and fixed playhead, including during playback. */
@Composable
internal fun PipTimelineLane(viewModel: EditorViewModel, compact: Boolean = false, navigation: TimelineCallbacks? = null, onEdit: () -> Unit) {
    val state by viewModel.screenState.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value)
    val liveState = viewModel.uiState.collectAsStateWithLifecycle()
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val badge = androidx.compose.ui.res.painterResource(R.drawable.ic_editor_picture_in_picture)
    val layers = state.project?.timeline?.overlayClips.orEmpty().sortedByDescending { it.zIndex }
    if (layers.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 112.dp).verticalScroll(rememberScrollState())
        .background(VEColor.DarkBackground).testTag("pip-timeline")) {
        layers.forEach { layer ->
            Canvas(Modifier.fillMaxWidth().height(if (compact) 48.dp else 52.dp).testTag("pip-lane-${layer.id}")
                .semantics { contentDescription = "Overlay ${state.project?.mediaRef(layer.mediaRefId)?.displayName ?: "layer"}" }
                .timelineLaneInput(navigation, { viewModel.uiState.value }) { x ->
                    val live = viewModel.uiState.value
                    val time = live.viewport()?.timeAtScreenX(live.scrollXPx, x)
                    if (time != null && time in layer.timelineStartMicros until layer.timelineEndMicros) {
                        viewModel.selectOverlay(layer.id); onEdit()
                    } else viewModel.clearSelection()
                }) {
                val current = liveState.value
                val viewport = current.viewport() ?: return@Canvas
                val x = viewport.screenXAtTime(current.scrollXPx, layer.timelineStartMicros)
                val end = viewport.screenXAtTime(current.scrollXPx, layer.timelineEndMicros)
                drawRoundRect(if (state.selectedOverlayId == layer.id) Color(0xFF19C4AD) else Color(0xFF11B5A3),
                    Offset(x, 3.dp.toPx()), Size((end - x).coerceAtLeast(1f), size.height - 6.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                if (state.selectedOverlayId == layer.id) drawRoundRect(VEColor.AccentCyan,
                    Offset(x, 3.dp.toPx()), Size((end - x).coerceAtLeast(1f), size.height - 6.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                val sourceClip = state.project?.timeline?.videoClips?.firstOrNull { it.mediaRefId == layer.mediaRefId }
                val thumbnail = sourceClip?.let { assets.strips[it.id]?.images?.firstOrNull { image -> image != null } }
                val thumbSize = (size.height - 18.dp.toPx()).toInt().coerceAtLeast(1)
                if (thumbnail != null && x >= 34.dp.toPx() && end - x > 100.dp.toPx()) {
                    val side = minOf(thumbnail.width, thumbnail.height)
                    drawImage(thumbnail,
                        srcOffset = androidx.compose.ui.unit.IntOffset((thumbnail.width-side)/2, (thumbnail.height-side)/2),
                        srcSize = androidx.compose.ui.unit.IntSize(side, side),
                        dstOffset = androidx.compose.ui.unit.IntOffset((x + 6.dp.toPx()).toInt(), 9.dp.toPx().toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(thumbSize, thumbSize))
                }
                val labelInset = if (thumbnail != null && x >= 34.dp.toPx() && end-x > 100.dp.toPx()) thumbSize + 12.dp.toPx() else 8.dp.toPx()
                if (end > 0 && x < size.width) clipRect(left = x.coerceAtLeast(0f), right = end.coerceAtMost(size.width).coerceAtLeast(x.coerceAtLeast(0f))) {
                    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE; textSize = 12.sp.toPx()
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }
                    val labelX = x.coerceAtLeast(0f) + labelInset
                    val label = android.text.TextUtils.ellipsize("PiP Overlay", paint,
                        (minOf(end, size.width) - labelX - 8.dp.toPx()).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END)
                    drawContext.canvas.nativeCanvas.drawText(label.toString(), labelX, size.height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
                }
                drawRoundRect(VEColor.DarkSecondarySurface, Offset(3.dp.toPx(), 3.dp.toPx()),
                    Size(28.dp.toPx(), size.height - 6.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                drawRoundRect(VEColor.DarkBorder, Offset(3.dp.toPx(), 3.dp.toPx()),
                    Size(28.dp.toPx(), size.height - 6.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                translate(9.dp.toPx(), size.height / 2 - 8.dp.toPx()) {
                    with(badge) { draw(Size(16.dp.toPx(), 16.dp.toPx()), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(VEColor.DarkSecondaryText)) }
                }
                drawLine(Color.White, Offset(current.playheadXpx, 0f), Offset(current.playheadXpx, size.height), 1.25.dp.toPx())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PipToolsSheet(viewModel: EditorViewModel, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val project = state.project ?: return
    val layers = project.timeline.overlayClips.sortedByDescending { it.zIndex }
    val layer = layers.firstOrNull { it.id == state.selectedOverlayId }
    var section by remember { mutableStateOf("Position") }
    var precision by remember { mutableStateOf<String?>(null) }
    EditorToolPanel("Picture in Picture", onDismiss, null, onDismiss, cancelLabel = "Close", applyLabel = "Done") {
        EditorSegments(listOf("Position", "Crop", "Opacity", "Audio", "Blend", "Mask", "Chroma", "Effects", "Layers"), section, { section = it })
        if (layer == null || section == "Layers") {
            TextButton(onClick = onAdd, enabled = layers.size < (if (project.timeline.transitions.isEmpty()) 7 else 5) && project.timeline.videoClips.isNotEmpty()) { Text("Add video or photo overlay") }
            layers.forEach { item ->
                TextButton(onClick = { viewModel.selectOverlay(item.id); section = "Position" }, modifier = Modifier.testTag("select-pip-${item.id}")) {
                    Text((if (item.id == layer?.id) "✓ " else "") + (project.mediaRef(item.mediaRefId)?.displayName ?: "Overlay"))
                }
            }
        }
        if (layer != null) {
            fun update(value: OverlayClip) = viewModel.updateOverlay(value)
            @Composable fun control(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
                EditorValueSlider(label, value.coerceIn(range.start, range.endInclusive), range,
                    if (label == "Rotation") "${value.toInt()}°" else "${(value * 100).toInt()}%", change, Modifier.testTag("pip-$label"))
            }
            when(section) {
                "Position" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(com.videoeditor.core.model.ScaleMode.Fit, com.videoeditor.core.model.ScaleMode.Fill).forEach { mode ->
                            EditorChoice(if (mode == com.videoeditor.core.model.ScaleMode.Fit) "Fit" else "Fill",
                                (layer.scaleMode == com.videoeditor.core.model.ScaleMode.Fit) == (mode == com.videoeditor.core.model.ScaleMode.Fit), { update(layer.copy(scaleMode = mode)) },
                                Modifier.testTag("pip-scale-mode-${mode.name}"))
                        }
                    }
                    control("Scale", layer.transform.scale, .05f..2f) { update(layer.copy(transform = layer.transform.copy(scale = it))) }
                    control("Opacity", layer.evaluateOpacityAt(state.playheadMicros), 0f..1f) { value ->
                        val local = (state.playheadMicros - layer.timelineStartMicros).coerceAtLeast(0L)
                        val track = layer.keyframeTracks["opacity"]
                        val updatedTrack = track?.takeIf { t -> t.keyframes.any { it.timeMicros == local } }?.let { t ->
                            t.copy(keyframes = t.keyframes.map { if (it.timeMicros == local) it.copy(value = value) else it }) }
                        update(if (updatedTrack == null) layer.copy(opacity = value) else layer.copy(
                            keyframeTracks = layer.keyframeTracks + ("opacity" to updatedTrack)))
                    }
                    if (LocalEditorPanelExpansion.current?.value != true) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        EditorChoice("X ${(layer.transform.offsetX * 100).toInt()}%", precision == "X", { precision = if (precision == "X") null else "X" }, Modifier.weight(1f))
                        EditorChoice("Y ${(layer.transform.offsetY * 100).toInt()}%", precision == "Y", { precision = if (precision == "Y") null else "Y" }, Modifier.weight(1f))
                        EditorChoice("${layer.transform.rotationDegrees}°", precision == "Rotation", { precision = if (precision == "Rotation") null else "Rotation" }, Modifier.weight(1f))
                    }
                    if (LocalEditorPanelExpansion.current?.value == true) {
                        control("X position", layer.transform.offsetX, -1f..1f) { update(layer.copy(transform = layer.transform.copy(offsetX = it))) }
                        control("Y position", layer.transform.offsetY, -1f..1f) { update(layer.copy(transform = layer.transform.copy(offsetY = it))) }
                        control("Rotation", layer.transform.rotationDegrees.toFloat(), -180f..180f) { update(layer.copy(transform = layer.transform.copy(rotationDegrees = it.toInt()))) }
                    } else when(precision) {
                        "X" -> control("Horizontal position", layer.transform.offsetX, -1f..1f) { update(layer.copy(transform = layer.transform.copy(offsetX = it))) }
                        "Y" -> control("Vertical position", layer.transform.offsetY, -1f..1f) { update(layer.copy(transform = layer.transform.copy(offsetY = it))) }
                        "Rotation" -> control("Rotation", layer.transform.rotationDegrees.toFloat(), -180f..180f) { update(layer.copy(transform = layer.transform.copy(rotationDegrees = it.toInt()))) }
                    }

                }
                "Opacity" -> {
                    control("Opacity", layer.evaluateOpacityAt(state.playheadMicros), 0f..1f) { value ->
                        val local = (state.playheadMicros - layer.timelineStartMicros).coerceAtLeast(0L)
                        val track = layer.keyframeTracks["opacity"]
                        val updatedTrack = track?.takeIf { t -> t.keyframes.any { it.timeMicros == local } }?.let { t ->
                            t.copy(keyframes = t.keyframes.map { if (it.timeMicros == local) it.copy(value = value) else it }) }
                        update(if (updatedTrack == null) layer.copy(opacity = value) else layer.copy(
                            keyframeTracks = layer.keyframeTracks + ("opacity" to updatedTrack)))
                    }
                }
                "Crop" -> {
                    val maxStart = (project.timeline.renderedDurationMicros - layer.timelineDurationMicros).coerceAtLeast(0)
                    if (maxStart > 0) {
                        Text("Timeline start", color = VEColor.DarkSecondaryText)
                        PremiumSlider(layer.timelineStartMicros / 1_000_000f, { update(layer.copy(timelineStartMicros = (it * 1_000_000).toLong())) }, valueRange = 0f..(maxStart / 1_000_000f))
                    }
                    if (layer.isPhoto) {
                        val maxDuration = (project.timeline.renderedDurationMicros - layer.timelineStartMicros) / 1_000_000f
                        if (maxDuration > .1f) {
                            Text("Duration", color = VEColor.DarkSecondaryText)
                            PremiumSlider(layer.timelineDurationMicros / 1_000_000f, { update(layer.copy(timelineDurationMicros = (it * 1_000_000).toLong())) }, valueRange = .1f..maxDuration)
                        }
                    } else {
                        val maxEnd = minOf(project.mediaRef(layer.mediaRefId)?.durationMicros ?: layer.sourceEndMicros,
                            layer.sourceStartMicros + ((project.timeline.renderedDurationMicros - layer.timelineStartMicros) * layer.playbackSpeed).toLong())
                        if (layer.sourceEndMicros - layer.sourceStartMicros > 100_000) {
                            Text("Source start", color = VEColor.DarkSecondaryText)
                            PremiumSlider(layer.sourceStartMicros / 1_000_000f, { value ->
                                val start = (value * 1_000_000).toLong()
                                viewModel.trimOverlaySource(layer.id, start, layer.sourceEndMicros)
                            }, valueRange = 0f..((layer.sourceEndMicros - 100_000) / 1_000_000f))
                        }
                        if (maxEnd - layer.sourceStartMicros > 100_000) {
                            Text("Source end", color = VEColor.DarkSecondaryText)
                            PremiumSlider(layer.sourceEndMicros / 1_000_000f, { value ->
                                val end = (value * 1_000_000).toLong()
                                viewModel.trimOverlaySource(layer.id, layer.sourceStartMicros, end)
                            }, valueRange = (layer.sourceStartMicros + 100_000) / 1_000_000f..maxEnd / 1_000_000f)
                        }
                    }
                }
                "Audio" -> if (!layer.isPhoto) {
                    control("Volume", layer.volume, 0f..2f) { update(layer.copy(volume = it)) }
                    Row { Text("Mute overlay"); Switch(layer.muted, { update(layer.copy(muted = it)) }) }
                } else Text("Photos have no audio.", color = VEColor.DarkSecondaryText)
                "Blend" -> {
                    Text("Blend Mode", color = VEColor.DarkMutedText, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.videoeditor.core.model.BlendMode.values().forEach { mode ->
                            EditorChoice(mode.displayName, layer.blendMode == mode, {
                                viewModel.setOverlayBlendMode(layer.id, mode)
                            }, Modifier.testTag("pip-blend-${mode.name}"))
                        }
                    }
                    control("Opacity", layer.evaluateOpacityAt(state.playheadMicros), 0f..1f) { value ->
                        val local = (state.playheadMicros - layer.timelineStartMicros).coerceAtLeast(0L)
                        val track = layer.keyframeTracks["opacity"]
                        val updatedTrack = track?.takeIf { t -> t.keyframes.any { it.timeMicros == local } }?.let { t ->
                            t.copy(keyframes = t.keyframes.map { if (it.timeMicros == local) it.copy(value = value) else it }) }
                        update(if (updatedTrack == null) layer.copy(opacity = value) else layer.copy(
                            keyframeTracks = layer.keyframeTracks + ("opacity" to updatedTrack)))
                    }
                    val currentPlayhead = (viewModel.uiState.value.playheadMicros - layer.timelineStartMicros).coerceAtLeast(0L)
                    val opacityTrack = layer.keyframeTracks["opacity"]
                    val hasOpacityKf = opacityTrack?.keyframes?.any { it.timeMicros == currentPlayhead } == true
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                val currentList = opacityTrack?.keyframes.orEmpty()
                                val nextList = if (hasOpacityKf) {
                                    currentList.filterNot { it.timeMicros == currentPlayhead }
                                } else {
                                    (currentList + com.videoeditor.core.model.VisualKeyframe(
                                        id = java.util.UUID.randomUUID().toString(),
                                        timeMicros = currentPlayhead,
                                        value = layer.opacity
                                    )).sortedBy { it.timeMicros }
                                }
                                val newTracks = layer.keyframeTracks + ("opacity" to com.videoeditor.core.model.KeyframeTrack("opacity", nextList))
                                update(layer.copy(keyframeTracks = newTracks))
                            },
                            modifier = Modifier.testTag("pip-opacity-keyframe-toggle")
                        ) {
                            Text(if (hasOpacityKf) "Opacity Keyframed" else "+ Opacity Keyframe", color = if (hasOpacityKf) VEColor.AccentCyan else VEColor.DarkSecondaryText)
                        }
                    }
                    opacityTrack?.let { track ->
                        KeyframeNavigation(track, currentPlayhead, layer.timelineStartMicros, viewModel::seekTo) { updated ->
                            update(layer.copy(keyframeTracks = layer.keyframeTracks + ("opacity" to updated)))
                        }
                    }
                }
                "Mask" -> {
                    val currentMask = layer.mask
                    Text("Mask Shape", color = VEColor.DarkMutedText, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditorChoice("None", currentMask == null || !currentMask.enabled, {
                            viewModel.setOverlayMask(layer.id, null)
                        }, Modifier.testTag("pip-mask-none"))
                        com.videoeditor.core.model.MaskType.values().forEach { type ->
                            EditorChoice(type.displayName, currentMask?.enabled == true && currentMask.type == type, {
                                viewModel.setOverlayMask(layer.id, (currentMask ?: com.videoeditor.core.model.LayerMask()).copy(type = type, enabled = true))
                            }, Modifier.testTag("pip-mask-${type.name}"))
                        }
                    }
                    if (currentMask != null && currentMask.enabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Invert", color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Switch(currentMask.invert, { viewModel.setOverlayMask(layer.id, currentMask.copy(invert = it)) })
                        }
                        control("Center X", currentMask.centerX, -1f..1f) { viewModel.setOverlayMask(layer.id, currentMask.copy(centerX = it)) }
                        control("Center Y", currentMask.centerY, -1f..1f) { viewModel.setOverlayMask(layer.id, currentMask.copy(centerY = it)) }
                        control("Feather", currentMask.feather, 0f..0.5f) { viewModel.setOverlayMask(layer.id, currentMask.copy(feather = it)) }
                        control("Scale", currentMask.scaleX, 0.1f..3f) { viewModel.setOverlayMask(layer.id, currentMask.copy(scaleX = it, scaleY = it)) }
                        if (currentMask.type == com.videoeditor.core.model.MaskType.RoundedRectangle) {
                            control("Corner Radius", currentMask.cornerRadius, 0f..0.5f) { viewModel.setOverlayMask(layer.id, currentMask.copy(cornerRadius = it)) }
                        }
                        if (currentMask.type == com.videoeditor.core.model.MaskType.Polygon) {
                            control("Sides", currentMask.polygonSides.toFloat(), 3f..8f) { viewModel.setOverlayMask(layer.id, currentMask.copy(polygonSides = it.toInt())) }
                        }
                    }
                }
                "Chroma" -> {
                    val chroma = layer.chromaKey ?: com.videoeditor.core.model.ChromaKeySettings()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable Chroma Key", color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Switch(chroma.enabled, { viewModel.setOverlayChromaKey(layer.id, chroma.copy(enabled = it)) }, modifier = Modifier.testTag("pip-chroma-toggle"))
                    }
                    if (chroma.enabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EditorChoice("Green", chroma.keyColor == com.videoeditor.core.model.ChromaKeySettings.PRESET_GREEN, {
                                viewModel.setOverlayChromaKey(layer.id, chroma.copy(keyColor = com.videoeditor.core.model.ChromaKeySettings.PRESET_GREEN))
                            })
                            EditorChoice("Blue", chroma.keyColor == com.videoeditor.core.model.ChromaKeySettings.PRESET_BLUE, {
                                viewModel.setOverlayChromaKey(layer.id, chroma.copy(keyColor = com.videoeditor.core.model.ChromaKeySettings.PRESET_BLUE))
                            })
                        }
                        control("Tolerance", chroma.tolerance, 0.05f..0.8f) { viewModel.setOverlayChromaKey(layer.id, chroma.copy(tolerance = it)) }
                        control("Softness", chroma.softness, 0.01f..0.5f) { viewModel.setOverlayChromaKey(layer.id, chroma.copy(softness = it)) }
                        control("Spill Suppression", chroma.spillSuppression, 0f..1f) { viewModel.setOverlayChromaKey(layer.id, chroma.copy(spillSuppression = it)) }
                    }
                }
                "Effects" -> {
                    Text("OVERLAY EFFECTS (${layer.effects.size})", color = VEColor.DarkMutedText, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            com.videoeditor.core.model.VisualEffectType.GaussianBlur,
                            com.videoeditor.core.model.VisualEffectType.Sharpen,
                            com.videoeditor.core.model.VisualEffectType.Vignette,
                            com.videoeditor.core.model.VisualEffectType.FilmGrain,
                            com.videoeditor.core.model.VisualEffectType.PresetCinematic,
                        ).forEach { type ->
                            EditorChoice(type.displayName, layer.effects.any { it.type == type }, {
                                if (layer.effects.any { it.type == type }) {
                                    val idToRemove = layer.effects.first { it.type == type }.id
                                    viewModel.removeOverlayEffect(layer.id, idToRemove)
                                } else {
                                    viewModel.addOverlayEffect(layer.id, com.videoeditor.core.model.VisualEffect.createDefault(type))
                                }
                            })
                        }
                    }
                    layer.effects.forEach { effect ->
                        control(effect.type.displayName, effect.intensity, 0f..1f) {
                            viewModel.updateOverlayEffect(layer.id, effect.copy(intensity = it))
                        }
                    }
                }
                "Layers" -> {
                    Row {
                        TextButton(onClick = { update(layer.copy(zIndex = (layers.maxOfOrNull { it.zIndex } ?: 0) + 1)) }) { Text("Bring front") }
                        TextButton(onClick = { update(layer.copy(zIndex = (layers.minOfOrNull { it.zIndex } ?: 0) - 1)) }) { Text("Send back") }
                    }
                    Row {
                        TextButton(onClick = { viewModel.duplicateOverlay(layer.id) }) { Text("Duplicate") }
                        TextButton(onClick = { viewModel.deleteOverlay(layer.id) }) { Text("Delete", color = VEColor.Error) }
                    }
                }
            }
        }
        Text("Edits apply immediately · Undo restores the previous value", color = VEColor.DarkMutedText, style = MaterialTheme.typography.labelSmall)
    }
}
