package com.videoeditor.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.core.model.*
import com.videoeditor.feature.editor.EditorColor as VEColor

/**
 * Stage 9 Visual Effects Panel.
 * Supports adding, parameter tweaking, reordering, keyframing, and toggling
 * real non-AI GPU visual effects and creative presets on video clips and overlays.
 */
@Composable
internal fun VisualEffectsPanel(
    viewModel: EditorViewModel,
    targetClipId: String,
    isOverlay: Boolean = false,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val project = state.project ?: return

    val currentEffects = remember(project, targetClipId, isOverlay) {
        if (isOverlay) {
            project.timeline.overlayClips.firstOrNull { it.id == targetClipId }?.effects.orEmpty()
        } else {
            project.timeline.videoClips.firstOrNull { it.id == targetClipId }?.effects.orEmpty()
        }
    }

    var effectDrafts by remember(targetClipId) { mutableStateOf(currentEffects) }
    var selectedEffectId by remember(targetClipId) {
        mutableStateOf(effectDrafts.firstOrNull()?.id)
    }
    var selectedCategory by remember { mutableStateOf("Color") }

    val activeEffect = effectDrafts.firstOrNull { it.id == selectedEffectId }
    var comparing by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    val assets by viewModel.assets.collectAsState()
    val loadedSource by produceState<android.graphics.Bitmap?>(null, targetClipId, isOverlay) {
        value = viewModel.effectThumbnailSource(targetClipId, isOverlay)
    }
    val source = loadedSource ?: if (!isOverlay) assets.strips[targetClipId]?.frames?.firstOrNull { it != null } else null
    val context = LocalContext.current
    val thumbnails = remember(source) { mutableStateMapOf<VisualEffectType, android.graphics.Bitmap>() }
    var thumbnailError by remember(source) { mutableStateOf(false) }
    val catalogEffects = remember(selectedCategory) { VisualEffectType.entries.filter { it.category == selectedCategory } }
    LaunchedEffect(source, selectedCategory) {
        val bitmap = source ?: return@LaunchedEffect
        val missing = catalogEffects.filterNot { it in thumbnails }
        if (missing.isNotEmpty()) try {
            thumbnails.putAll(com.videoeditor.core.media.composition.EffectThumbnailRenderer.render(context, bitmap, missing))
            thumbnailError = false
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { android.util.Log.w("EffectThumbnails", "Cannot render gallery swatches", failure); thumbnailError = true }
    }

    val targetStart = if (isOverlay) project.timeline.overlayClips.firstOrNull { it.id == targetClipId }?.timelineStartMicros ?: 0L
        else project.timeline.videoClips.firstOrNull { it.id == targetClipId }?.timelineStartMicros ?: 0L
    val localPlayhead = (state.playheadMicros - targetStart).coerceAtLeast(0L)
    fun preview(values: List<VisualEffect>) = viewModel.previewEffects(targetClipId, if (comparing) emptyList() else values, isOverlay)
    fun updateDraft(updated: VisualEffect) {
        effectDrafts = effectDrafts.map { if (it.id == updated.id) updated else it }
        preview(effectDrafts)
    }
    DisposableEffect(targetClipId) { onDispose { viewModel.restoreToolPreview() } }

    EditorToolPanel(
        title = "Effects",
        onCancel = { viewModel.restoreToolPreview(); onClose() },
        onReset = { effectDrafts = emptyList(); preview(emptyList()) },
        onApply = {
            if (isOverlay) {
                viewModel.setOverlayEffects(targetClipId, effectDrafts)
            } else {
                viewModel.setClipEffects(targetClipId, effectDrafts)
            }
            onClose()
        },
        headerAction = {
            TextButton(onClick = { comparing = !comparing; preview(effectDrafts) }, modifier = Modifier.testTag("effects-compare")) {
                Text(if (comparing) "Original" else "Compare", style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = Modifier.testTag("visual-effects-panel")
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Color", "Basic", "Blur", "Stylize", "Cinematic").forEach { name ->
                EditorChoice(name, selectedCategory == name, { selectedCategory = name })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            catalogEffects.forEach { type ->
                val added = effectDrafts.any { it.type == type }
                Column(Modifier.width(84.dp).testTag("catalog-effect-${type.name}")
                    .clickable {
                        val existing = effectDrafts.lastOrNull { it.type == type }
                        if (existing != null) selectedEffectId = existing.id
                        else {
                            val effect = VisualEffect.createDefault(type)
                            effectDrafts = effectDrafts + effect; selectedEffectId = effect.id; preview(effectDrafts)
                        }
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp))
                        .background(VEColor.DarkSecondarySurface)
                        .border(if (added) 2.dp else 1.dp, if (added) VEColor.AccentCyan else VEColor.DarkBorder, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center) {
                        val thumbnail = thumbnails[type]
                        if (thumbnail != null) Image(thumbnail.asImageBitmap(), type.displayName, Modifier.fillMaxSize().testTag("effect-thumbnail-${type.name}"), contentScale = ContentScale.Crop)
                        else if (!thumbnailError && source != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else EditorIcon(Icons.Outlined.Image, null, tint = VEColor.DarkMutedText)
                        if (added) Icon(Icons.Outlined.CheckCircle, null, tint = VEColor.AccentCyan,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(18.dp))
                    }
                    Text(type.displayName, color = if (added) VEColor.AccentCyan else Color.White,
                        style = MaterialTheme.typography.labelSmall, maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        if (thumbnailError) Text("Thumbnail preview unavailable. Select an effect to preview on your clip.",
            style = MaterialTheme.typography.labelSmall, color = VEColor.DarkMutedText)

        // Stack of applied effects
        if (effectDrafts.isNotEmpty()) {
            Text(
                "APPLIED EFFECTS (${effectDrafts.size})",
                color = VEColor.DarkMutedText,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                effectDrafts.forEachIndexed { index, effect ->
                    val isSelected = effect.id == selectedEffectId
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) VEColor.PrimaryBlue.copy(alpha = 0.25f) else VEColor.DarkSecondarySurface)
                            .border(1.dp, if (isSelected) VEColor.AccentCyan else VEColor.DarkBorder, RoundedCornerShape(8.dp))
                            .clickable { selectedEffectId = effect.id }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = effect.enabled,
                            onCheckedChange = { checked ->
                                updateDraft(effect.copy(enabled = checked))
                            },
                            modifier = Modifier.size(48.dp).padding(end = 4.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            effect.type.displayName,
                            color = if (isSelected) VEColor.AccentCyan else Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.width(6.dp))
                        if (index > 0) {
                            IconButton(
                                onClick = {
                                    val list = effectDrafts.toMutableList()
                                    val item = list.removeAt(index)
                                    list.add(index - 1, item)
                                    effectDrafts = list
                                    preview(list)
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                EditorIcon(Icons.Outlined.ArrowBack, "Move Up", tint = VEColor.DarkSecondaryText, modifier = Modifier.size(14.dp))
                            }
                        }
                        if (index < effectDrafts.size - 1) {
                            IconButton(
                                onClick = {
                                    val list = effectDrafts.toMutableList()
                                    val item = list.removeAt(index)
                                    list.add(index + 1, item)
                                    effectDrafts = list
                                    preview(list)
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                EditorIcon(Icons.Outlined.ArrowForward, "Move Down", tint = VEColor.DarkSecondaryText, modifier = Modifier.size(14.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                val dup = effect.copy(id = java.util.UUID.randomUUID().toString())
                                val list = effectDrafts.toMutableList()
                                list.add(index + 1, dup)
                                effectDrafts = list
                                selectedEffectId = dup.id
                                preview(list)
                            },
                            modifier = Modifier.size(48.dp).testTag("effect-duplicate-${effect.id}")
                        ) {
                            EditorIcon(Icons.Outlined.ContentCopy, "Duplicate", tint = VEColor.DarkSecondaryText, modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = {
                                val remaining = effectDrafts.filterNot { it.id == effect.id }
                                effectDrafts = remaining
                                if (selectedEffectId == effect.id) {
                                    selectedEffectId = remaining.firstOrNull()?.id
                                }
                                preview(remaining)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            EditorIcon(Icons.Default.Close, "Remove", tint = VEColor.Error, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // Active effect parameter controls
        if (activeEffect != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(VEColor.DarkSecondarySurface, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        activeEffect.type.displayName,
                        color = VEColor.AccentCyan,
                        style = MaterialTheme.typography.titleSmall
                    )
                    val currentPlayheadMicros = localPlayhead
                    val isKeyframed = activeEffect.keyframes.any { it.timeMicros == currentPlayheadMicros }
                    TextButton(
                        onClick = {
                            val nextKeyframes = if (isKeyframed) {
                                activeEffect.keyframes.filterNot { it.timeMicros == currentPlayheadMicros }
                            } else {
                                (activeEffect.keyframes + VisualKeyframe(
                                    id = java.util.UUID.randomUUID().toString(),
                                    timeMicros = currentPlayheadMicros,
                                    value = activeEffect.intensity
                                )).sortedBy { it.timeMicros }
                            }
                            updateDraft(activeEffect.copy(keyframes = nextKeyframes))
                        },
                        modifier = Modifier.testTag("effect-keyframe-toggle")
                    ) {
                        EditorIcon(
                            if (isKeyframed) Icons.Outlined.Diamond else Icons.Outlined.AddCircleOutline,
                            contentDescription = if (isKeyframed) "Keyframe active" else "Add keyframe",
                            tint = if (isKeyframed) VEColor.AccentCyan else VEColor.DarkSecondaryText,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isKeyframed) "Keyframed" else "+ Keyframe",
                            color = if (isKeyframed) VEColor.AccentCyan else VEColor.DarkSecondaryText,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                if (activeEffect.keyframes.isNotEmpty()) key(activeEffect.id) {
                    KeyframeNavigation(KeyframeTrack("intensity", activeEffect.keyframes), localPlayhead, targetStart,
                        viewModel::seekTo) { updateDraft(activeEffect.copy(keyframes = it.keyframes)) }
                }

                // Global Intensity slider
                EditorValueSlider(
                    label = "Intensity",
                    value = activeEffect.evaluateIntensityAt(localPlayhead).coerceIn(0f, 1f),
                    range = 0f..1f,
                    display = "${(activeEffect.intensity * 100).toInt()}%",
                    onChange = { value ->
                        val current = activeEffect.keyframes.firstOrNull { it.timeMicros == localPlayhead }
                        updateDraft(if (current == null) activeEffect.copy(intensity = value) else activeEffect.copy(
                            keyframes = activeEffect.keyframes.map { if (it.id == current.id) it.copy(value = value) else it }))
                    },
                    modifier = Modifier.testTag("effect-intensity-slider")
                )

                TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.testTag("effect-details")) {
                    Text(if (detailsExpanded) "Hide parameters & timing" else "Parameters & timing", style = MaterialTheme.typography.labelSmall)
                }
                if (detailsExpanded) {
                // Effect interval timing
                val clipDuration = if (isOverlay) {
                    project.timeline.overlayClips.firstOrNull { it.id == targetClipId }?.timelineDurationMicros ?: 5_000_000L
                } else {
                    project.timeline.videoClips.firstOrNull { it.id == targetClipId }?.timelineDurationMicros ?: 5_000_000L
                }
                val startSec = (activeEffect.startTimeMicros / 1_000_000f).coerceIn(0f, clipDuration / 1_000_000f)
                val durSec = ((activeEffect.durationMicros ?: clipDuration) / 1_000_000f).coerceIn(0.1f, maxOf(0.1f, (clipDuration - activeEffect.startTimeMicros) / 1_000_000f))

                EditorValueSlider(
                    label = "Start",
                    value = startSec,
                    range = 0f..maxOf(0.1f, clipDuration / 1_000_000f),
                    display = "${"%.2f".format(startSec)} s",
                    onChange = {
                        val newStartUs = (it * 1_000_000L).toLong()
                        updateDraft(activeEffect.copy(startTimeMicros = newStartUs))
                    },
                    modifier = Modifier.testTag("effect-start-slider")
                )
                EditorValueSlider(
                    label = "Duration",
                    value = durSec,
                    range = 0.1f..maxOf(0.1f, (clipDuration - activeEffect.startTimeMicros) / 1_000_000f),
                    display = "${"%.2f".format(durSec)} s",
                    onChange = {
                        val newDurUs = (it * 1_000_000L).toLong()
                        updateDraft(activeEffect.copy(durationMicros = newDurUs))
                    },
                    modifier = Modifier.testTag("effect-duration-slider")
                )

                // Type-specific parameters
                when (activeEffect.type) {
                    VisualEffectType.GaussianBlur -> {
                        val radius = activeEffect.param("radius", 12f)
                        EditorValueSlider(
                            label = "Blur",
                            value = radius,
                            range = 1f..50f,
                            display = "${radius.toInt()} px",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["radius"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Sharpen -> {
                        val amount = activeEffect.param("strength", 0.5f)
                        EditorValueSlider(
                            label = "Sharpen",
                            value = amount,
                            range = 0f..2f,
                            display = "${(amount * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["strength"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Vignette -> {
                        val radius = activeEffect.param("radius", 0.75f)
                        val feather = activeEffect.param("softness", 0.45f)
                        EditorValueSlider(
                            label = "Radius",
                            value = radius,
                            range = 0.1f..1.5f,
                            display = "${(radius * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["radius"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                        EditorValueSlider(
                            label = "Feather",
                            value = feather,
                            range = 0.05f..1f,
                            display = "${(feather * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["softness"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.FilmGrain -> {
                        val amount = activeEffect.param("grainAmount", 0.25f)
                        EditorValueSlider(
                            label = "Grain",
                            value = amount,
                            range = 0f..1f,
                            display = "${(amount * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["grainAmount"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Exposure -> {
                        val exp = activeEffect.param("exposure", 0.2f)
                        EditorValueSlider(
                            label = "EV",
                            value = exp,
                            range = -2f..2f,
                            display = "${"%.2f".format(exp)}",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["exposure"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Brightness -> {
                        val b = activeEffect.param("brightness", 0.15f)
                        EditorValueSlider(
                            label = "Bright",
                            value = b,
                            range = -1f..1f,
                            display = "${(b * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["brightness"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Contrast -> {
                        val c = activeEffect.param("contrast", 0.2f)
                        EditorValueSlider(
                            label = "Contrast",
                            value = c,
                            range = -1f..1f,
                            display = "${(c * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["contrast"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Saturation -> {
                        val s = activeEffect.param("saturation", 0.25f)
                        EditorValueSlider(
                            label = "Saturate",
                            value = s,
                            range = -1f..1f,
                            display = "${(s * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["saturation"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Temperature -> {
                        val t = activeEffect.param("temperature", 0.3f)
                        EditorValueSlider(
                            label = "Temp",
                            value = t,
                            range = -1f..1f,
                            display = "${(t * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["temperature"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Tint -> {
                        val tint = activeEffect.param("tint", 0.2f)
                        EditorValueSlider(
                            label = "Tint",
                            value = tint,
                            range = -1f..1f,
                            display = "${(tint * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["tint"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Highlights -> {
                        val hl = activeEffect.param("highlights", 0.2f)
                        EditorValueSlider(
                            label = "Highlight",
                            value = hl,
                            range = -1f..1f,
                            display = "${(hl * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["highlights"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Shadows -> {
                        val sh = activeEffect.param("shadows", 0.2f)
                        EditorValueSlider(
                            label = "Shadow",
                            value = sh,
                            range = -1f..1f,
                            display = "${(sh * 100).toInt()}%",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["shadows"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    VisualEffectType.Hue -> {
                        val hue = activeEffect.param("hue", 0f)
                        EditorValueSlider(
                            label = "Hue",
                            value = hue.coerceIn(-.5f, .5f),
                            range = -0.5f..0.5f,
                            display = "${(hue * 360f).toInt()}°",
                            onChange = {
                                val params = activeEffect.parameters.toMutableMap()
                                params["hue"] = it
                                updateDraft(activeEffect.copy(parameters = params))
                            }
                        )
                    }
                    else -> {}
                }
                }
            }
        }


    }
}
