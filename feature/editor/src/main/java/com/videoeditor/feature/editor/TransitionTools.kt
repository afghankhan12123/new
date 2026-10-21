package com.videoeditor.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.feature.editor.EditorColor as VEColor
import com.videoeditor.core.model.Transition
import com.videoeditor.editing.timeline.TimelineLayout
import com.videoeditor.editing.timeline.videoClipId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Join controls use the same viewport as clip drawing. Selected trim handles keep priority. */
@Composable
internal fun TransitionJoinIndicators(state:EditorUiState, live:State<EditorUiState>?, pxPerUnit:Float,
    onEdit:(String,String)->Unit) {
    val density=LocalDensity.current
    val touchPx=with(density) { 48.dp.toPx() }
    val clips=state.project?.timeline?.videoClips.orEmpty()
    val joins=remember(clips) { clips.zipWithNext().filter { (a,b) ->
        !a.isPhoto && !b.isPhoto && a.timelineEndMicros==b.timelineStartMicros } }
    val visible by remember(joins,live,state.viewportWidthPx,state.zoomPixelsPerMicro,state.scrollXPx) {
        derivedStateOf {
            val current=live?.value ?: state
            val viewport=current.viewport()
            joins.filter { (a,_) -> viewport?.screenXAtTime(current.scrollXPx,a.timelineEndMicros)
                ?.let { it>=-touchPx && it<=current.viewportWidthPx+touchPx } == true }
        }
    }
    for((a,b) in visible) key(a.id,b.id) {
    val existing=state.project?.timeline?.transitions?.any { it.outgoingClipId==a.id && it.incomingClipId==b.id } == true
        val selected=state.selection.videoClipId in listOf(a.id,b.id)
        val offset=Modifier.offset {
            val current=live?.value ?: state
            IntOffset(((current.viewport()?.screenXAtTime(current.scrollXPx,a.timelineEndMicros) ?: -1000f)-touchPx/2).roundToInt(),
                ((TimelineLayout.RULER_HEIGHT+TimelineLayout.VIDEO_HEIGHT/2)*pxPerUnit-touchPx/2).roundToInt())
        }
        val transition = state.project?.timeline?.transitions?.firstOrNull { it.outgoingClipId == a.id && it.incomingClipId == b.id }
        IconButton(onClick={onEdit(a.id,b.id)},modifier=offset.size(48.dp)
            .testTag("transition-join-${a.id}-${b.id}")
            .semantics { contentDescription=transition?.let { "Edit ${it.type.displayName}" } ?: "Add transition between clips" }) {
            EditorIcon(Icons.AutoMirrored.Filled.CompareArrows,null,
                tint=if(existing) Color.White else VEColor.DarkSecondaryText,
                modifier=Modifier.background(if(existing) VEColor.PrimaryBlue else VEColor.DarkPrimarySurface,
                    RoundedCornerShape(4.dp)).padding(3.dp).size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransitionToolsSheet(viewModel:EditorViewModel,outgoingId:String,incomingId:String,onDismiss:()->Unit) {
    val state by viewModel.screenState.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value)
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val existing = state.project?.timeline?.transitions?.firstOrNull { it.outgoingClipId == outgoingId && it.incomingClipId == incomingId }
    val id = rememberSaveable(outgoingId, incomingId) { existing?.id ?: java.util.UUID.randomUUID().toString() }
    var enabled by rememberSaveable(outgoingId, incomingId) { mutableStateOf(true) }
    var reviewHandles by remember { mutableStateOf(false) }
    var selectedType by rememberSaveable(outgoingId, incomingId) {
        mutableStateOf(existing?.type ?: com.videoeditor.core.model.TransitionType.CrossDissolve)
    }
    var duration by rememberSaveable(outgoingId, incomingId) { mutableLongStateOf(existing?.durationMicros ?: 300_000L) }
    val limit = remember(state.project, id, outgoingId, incomingId, selectedType) {
        viewModel.transitionDurationLimit(Transition(id, outgoingId, incomingId, 1L, selectedType))
    }
    val adjustmentLimit = remember(state.project, id, outgoingId, incomingId, selectedType) {
        viewModel.transitionDurationWithHandlesLimit(Transition(id, outgoingId, incomingId, 100_000L, selectedType))
    }
    val minSec = 0.10f
    val maxSec = maxOf(minSec, maxOf(limit, adjustmentLimit) / 1_000_000f)
    val currentSec = (duration / 1_000_000f).coerceIn(minSec, maxSec)
    var durationInitialized by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(limit, adjustmentLimit, id) {
        val maximum = maxOf(100_000L, limit, adjustmentLimit)
        duration = if (!durationInitialized && existing == null) {
            minOf(300_000L, if (limit >= 100_000L) limit else maximum)
        } else duration.coerceIn(100_000L, maximum)
        durationInitialized = true
    }

    val draft = Transition(id, outgoingId, incomingId, duration.coerceAtLeast(100_000L), selectedType)
    val handlePlan = viewModel.calculateTransitionHandlePlan(outgoingId, incomingId, draft.durationMicros)
    val error = if (enabled) viewModel.transitionValidation(draft) else null

    if (reviewHandles && handlePlan != null) AlertDialog(
        onDismissRequest = { reviewHandles = false },
        title = { Text("Adjust source handles?") },
        text = { Text(handlePlan.explanation + " Final transition: ${"%.2f".format(duration / 1_000_000f)} s.") },
        confirmButton = { TextButton(onClick = {
            reviewHandles = false
            if (viewModel.applyTransitionWithHandles(draft)) onDismiss()
        }, enabled = handlePlan.possible, modifier = Modifier.testTag("transition-confirm-handles")) { Text("Trim & apply") } },
        dismissButton = { TextButton(onClick = { reviewHandles = false }) { Text("Cancel") } },
    )

    DisposableEffect(viewModel, outgoingId, incomingId) { onDispose { viewModel.cancelTransitionPreview() } }
    fun dismiss() { viewModel.cancelTransitionPreview(); onDismiss() }
    EditorToolPanel("Transitions", { dismiss() },
        { viewModel.cancelTransitionPreview(); enabled = existing != null; selectedType = existing?.type ?: com.videoeditor.core.model.TransitionType.CrossDissolve; duration = existing?.durationMicros ?: 300_000L },
        {
            if (enabled && error == null) { if (viewModel.applyTransition(draft)) onDismiss() }
            else if (!enabled) { existing?.let { viewModel.removeTransition(it.id) }; dismiss() }
        }, applyEnabled = (enabled && error == null && limit >= 100_000L) || (!enabled && existing != null), tagPrefix = "transition", modifier = Modifier.testTag("transition-sheet"),
        headerAction = {
            TextButton(onClick = { viewModel.previewTransition(draft) }, enabled = enabled && error == null && limit >= 100_000L,
                modifier = Modifier.heightIn(min = 48.dp).testTag("transition-preview")) { Text("Preview", style = MaterialTheme.typography.labelMedium) }
        }) {
            EditorGalleryRow { tileWidth ->
                // "None" option
                Column(Modifier.width(tileWidth)
                    .clickable { viewModel.cancelTransitionPreview(); enabled = false }
                    .padding(2.dp).testTag("transition-none"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .border(if (!enabled) 2.dp else 1.dp, if (!enabled) VEColor.AccentCyan else VEColor.DarkBorder, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        EditorIcon(Icons.Default.Close, "None", tint = if (!enabled) VEColor.AccentCyan else VEColor.DarkSecondaryText)
                    }
                    Text("None", color = if (!enabled) VEColor.AccentCyan else VEColor.DarkSecondaryText,
                        style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp).heightIn(min = 32.dp))
                }

                // All real transition types
                com.videoeditor.core.model.TransitionType.values().forEach { type ->
                    val selected = enabled && selectedType == type
                    Column(Modifier.width(tileWidth)
                        .clickable {
                            viewModel.cancelTransitionPreview()
                            enabled = true
                            selectedType = type
                        }
                        .padding(2.dp).testTag(if (type == com.videoeditor.core.model.TransitionType.CrossDissolve) "transition-cross-dissolve" else "transition-${type.name}"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .border(if (selected) 2.dp else 1.dp, if (selected) VEColor.AccentCyan else VEColor.DarkBorder, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            assets.strips[outgoingId]?.frames?.firstOrNull { it != null }?.let {
                                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        Text(type.displayName, color = if (selected) VEColor.AccentCyan else VEColor.DarkSecondaryText,
                            style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp).heightIn(min = 32.dp))
                    }
                }
            }

            EditorValueSlider("Duration", currentSec, minSec..maxSec,
                "${"%.2f".format(duration / 1_000_000f)} s", {
                    viewModel.cancelTransitionPreview(); duration = (it * 1_000_000).roundToLong()
                }, Modifier.testTag("transition-duration"), enabled = maxOf(limit, adjustmentLimit) >= 100_000L)
            if (enabled && error != null) {
                Row(Modifier.fillMaxWidth().background(VEColor.DarkSecondarySurface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(error,
                        color = VEColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f).testTag("transition-validation"))
                    if (handlePlan?.possible == true) {
                        TextButton(onClick = {
                            viewModel.createTransitionHandles(outgoingId, incomingId)
                            duration = 1_000_000L
                        }, modifier = Modifier.heightIn(min = 48.dp).testTag("transition-create-handles")) {
                            Text("Trim & apply")
                        }
                    }
                    TextButton(onClick = { reviewHandles = true },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("transition-trim-and-apply")) {
                        Text(if (handlePlan?.possible == true) "Review trims" else "Details")
                    }
                }
            } else Text(if (enabled) "${selectedType.displayName} · source handles available" else "Transition disabled",
                color = VEColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("transition-validation"))
            if (existing != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { viewModel.removeTransition(existing.id); onDismiss() },
                    modifier = Modifier.testTag("transition-remove")) { Text("Remove", color = VEColor.Error) }
            }
    }
}
