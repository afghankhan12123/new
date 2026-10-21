package com.videoeditor.feature.editor

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Filter
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Deblur
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.videoeditor.feature.editor.PremiumSlider as Slider
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import com.videoeditor.feature.editor.EditorColor as VEColor
import com.videoeditor.core.designsystem.color.VETimelineColor
import com.videoeditor.core.designsystem.gradient.VEGradient
import com.videoeditor.core.designsystem.spacing.VERadius
import com.videoeditor.core.designsystem.spacing.VESpacing
import com.videoeditor.editing.timeline.RulerScale
import com.videoeditor.editing.timeline.TimelineGesture
import com.videoeditor.editing.timeline.TimelineSelection
import com.videoeditor.editing.timeline.selectedAudioClipId
import com.videoeditor.editing.timeline.selectedTextOverlayId
import com.videoeditor.editing.timeline.videoClipId
import com.videoeditor.feature.editor.timeline.TimelineCallbacks
import com.videoeditor.feature.editor.timeline.TimelineView

@Composable
fun EditorScreen(
    projectId: String,
    initialTool: String? = null,
    onInitialToolHandled: () -> Unit = {},
    onBack: () -> Unit,
    onExport: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    MaterialTheme(colorScheme = editorColorScheme(), typography = editorTypography()) {
        EditorContent(projectId, initialTool, onInitialToolHandled, onBack, onExport, viewModel)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun EditorContent(
    projectId: String,
    initialTool: String? = null,
    onInitialToolHandled: () -> Unit = {},
    onBack: () -> Unit,
    onExport: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.screenState.collectAsStateWithLifecycle(initialValue = EditorUiState())
    val liveTimelineState = viewModel.uiState.collectAsStateWithLifecycle()
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    // Surface transient user messages (invalid split point, unreadable audio).
    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeUserMessage()
        }
    }

    // The Photo Picker handles images/videos only; audio comes through the
    // document picker, whose grants are persistable (projects reopen later).
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::addAudioFromUri) }

    val addMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.appendMedia(uris)
        }
    }
    val launchAddMedia = {
        addMediaPicker.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
            ),
        )
    }

    val toolState = rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<EditorToolState, String>(
        save = { it.active.name }, restore = { EditorToolState(EditorTool.valueOf(it)) })) { EditorToolState() }
    val transitionDraft = rememberSaveable { mutableStateOf<String?>(null) }
    var transitionOutgoing by remember { toolState.draft(EditorTool.Transition, transitionDraft) }
    var transitionIncoming by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var showPipTools by remember { toolState.flag(EditorTool.Pip) }
    var showStickerTools by remember { toolState.flag(EditorTool.Sticker) }
    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addOverlayFromUri)
    }


    var relinkingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = relinkingId
        if (uri != null && id != null) viewModel.relinkFile(id, uri)
        relinkingId = null
    }
    uiState.missingMediaIds.firstOrNull()?.let { id ->
        com.videoeditor.core.designsystem.component.DecisionDialog(
            title = "Missing Media",
            message = "${uiState.project?.mediaRef(id)?.displayName ?: "Source file"} cannot be opened. Relink a file to preserve your edits.",
            primaryLabel = "Relink File", forceDark = true, icon = Icons.Outlined.BrokenImage,
            onPrimary = {
                    relinkingId = id
                    val type = uiState.project?.mediaRef(id)?.type
                    relinkPicker.launch(arrayOf(when (type) {
                        com.videoeditor.core.model.MediaType.Audio -> "audio/*"
                        com.videoeditor.core.model.MediaType.Photo -> "image/*"
                        else -> "video/*"
                    }))
                },
            onDismiss = onBack, dismissLabel = "Back",
        )
    }

    // The text editor is a bottom sheet that edits the selected overlay live in
    // the preview. For a new overlay it holds a draft id plus the pre-edit
    // snapshot so Cancel can restore (or delete) without committing partial text.
    val textDraft = androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver = TextEditorStateSaver,
    ) { mutableStateOf<TextEditorState?>(null) }
    var textEditor by remember { toolState.draft(EditorTool.Text, textDraft) }

    LaunchedEffect(uiState.project?.id, textEditor?.overlayId) {
        textEditor?.let { if (uiState.project?.timeline?.textOverlays?.any { text -> text.id == it.overlayId } == true)
            viewModel.beginTextEditing(it.overlayId) }
    }

    // Speed picker for the selected clip.
    var showDiscardTool by remember { mutableStateOf(false) }
    var showTrimPanel by remember { toolState.flag(EditorTool.Trim) }
    var showSplitPanel by remember { toolState.flag(EditorTool.Split) }
    var expandedTimeline by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showSpeedSheet by remember { toolState.flag(EditorTool.Speed) }
    // Volume control for the selected clip.
    var showVolumeSheet by remember { toolState.flag(EditorTool.Volume) }
    // Stage 8 audio tools for the selected audio clip.
    var showAutomationPanel by remember { toolState.flag(EditorTool.Automation) }
    var showEqualizerPanel by remember { toolState.flag(EditorTool.Equalizer) }
    var showBalancePanel by remember { toolState.flag(EditorTool.Balance) }
    var showDuckingPanel by remember { toolState.flag(EditorTool.Ducking) }
    var showFadePanel by remember { toolState.flag(EditorTool.Fade) }
    var showTransformSheet by remember { toolState.flag(EditorTool.Transform) }
    var showEffectsPanel by remember { toolState.flag(EditorTool.Effects) }
    var showMaskSheet by remember { toolState.flag(EditorTool.Mask) }
    var showChromaSheet by remember { toolState.flag(EditorTool.Chroma) }
    var showBlendSheet by remember { toolState.flag(EditorTool.Blend) }
    var transformMode by remember { mutableStateOf<com.videoeditor.core.model.ScaleMode?>(null) }
    var transformDraft by remember { mutableStateOf<com.videoeditor.core.model.VisualTransform?>(null) }
    var replacingStickerId by rememberSaveable { mutableStateOf<String?>(null) }
    var galleryPanel by remember { toolState.labels(mapOf("Stickers" to EditorTool.StickerLibrary, "Effects" to EditorTool.EffectLibrary)) }
    var canvasTitle by remember { mutableStateOf("Crop & Ratio") }
    var colorPanel by remember { toolState.labels(mapOf("Filters" to EditorTool.Filters, "Adjust" to EditorTool.Adjust)) }
    var showUnsaved by remember { mutableStateOf(false) }
    val closeEditor = { if (uiState.hasUnsavedChanges) showUnsaved = true else onBack() }
    androidx.activity.compose.BackHandler(uiState.hasUnsavedChanges) { showUnsaved = true }
    var showPhotoDuration by remember { toolState.flag(EditorTool.PhotoDuration) }
    var showCanvas by remember { toolState.flag(EditorTool.Canvas) }
    var canvasDraft by remember { mutableStateOf<com.videoeditor.core.model.CanvasSettings?>(null) }
    var showAudioMenu by remember { toolState.flag(EditorTool.Audio) }
    var showVoiceover by remember { mutableStateOf(false) }
    var microphoneDenied by remember { mutableStateOf(false) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        showVoiceover = granted; microphoneDenied = !granted
    }

    val dismissAllTools = {
        viewModel.finishTextEditing(false)
        textEditor = null
        showTrimPanel = false; showSplitPanel = false; showSpeedSheet = false
        showTransformSheet = false; showVolumeSheet = false; showCanvas = false
        colorPanel = null; showPhotoDuration = false
        showAutomationPanel = false; showEqualizerPanel = false
        showBalancePanel = false; showDuckingPanel = false; showFadePanel = false
        showEffectsPanel = false; showMaskSheet = false; showChromaSheet = false
        showBlendSheet = false; showAudioMenu = false; showPipTools = false
        showStickerTools = false; galleryPanel = null; replacingStickerId = null
        transitionOutgoing = null; transitionIncoming = null
        canvasDraft = null
        viewModel.restoreToolPreview()
    }

    LaunchedEffect(viewModel) {
        viewModel.canvasEditRequests.collect { selection ->
            if (selection is TimelineSelection.Text && textEditor?.overlayId == selection.overlayId) return@collect
            // An outside tap finishes direct text entry; the explicit X still rolls it back.
            if (selection is TimelineSelection.None && textEditor != null) viewModel.finishTextEditing(true)
            dismissAllTools()
            viewModel.select(selection)
            when (selection) {
                is TimelineSelection.Text -> {
                    viewModel.beginTextEditing(selection.overlayId)
                    textEditor = TextEditorState(selection.overlayId, false,
                        viewModel.uiState.value.project?.timeline?.textOverlays?.firstOrNull { it.id == selection.overlayId })
                }
                is TimelineSelection.Sticker -> showStickerTools = true
                is TimelineSelection.Overlay -> showPipTools = true
                else -> Unit
            }
        }
    }

    LaunchedEffect(uiState.selection) {
        val selected = uiState.selection
        val invalid = when (toolState.active) {
            EditorTool.Text -> textEditor?.overlayId != selected.selectedTextOverlayId
            EditorTool.Sticker -> selected !is TimelineSelection.Sticker
            EditorTool.Mask, EditorTool.Chroma, EditorTool.Blend -> selected !is TimelineSelection.Overlay
            EditorTool.Automation, EditorTool.Equalizer, EditorTool.Balance, EditorTool.Ducking, EditorTool.Fade -> selected !is TimelineSelection.Audio
            EditorTool.Speed -> selected !is TimelineSelection.Video && selected !is TimelineSelection.Audio
            EditorTool.Trim, EditorTool.Transform, EditorTool.PhotoDuration -> selected !is TimelineSelection.Video
            else -> false
        }
        if (invalid) dismissAllTools()
    }
    androidx.activity.compose.BackHandler(showTrimPanel || showSplitPanel || showSpeedSheet || showTransformSheet || showVolumeSheet || showCanvas || colorPanel != null || transitionOutgoing != null || textEditor != null || showPhotoDuration || showAutomationPanel || showEqualizerPanel || showBalancePanel || showDuckingPanel || showFadePanel || showEffectsPanel || showMaskSheet || showChromaSheet || showBlendSheet) {
        showDiscardTool = true
    }
    androidx.activity.compose.BackHandler(showAudioMenu || galleryPanel != null || showPipTools || showStickerTools) {
        showAudioMenu = false; galleryPanel = null; showPipTools = false; showStickerTools = false
    }
    if (showDiscardTool) AlertDialog(onDismissRequest = { showDiscardTool = false },
        title = { Text("Discard tool changes?") }, text = { Text("Apply the current edit to keep it, or discard it and return to the editor.") },
        confirmButton = { TextButton(onClick = {
            dismissAllTools()
            textEditor = null
            showDiscardTool = false
        }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { showDiscardTool = false }) { Text("Keep editing") } })

    LaunchedEffect(uiState.project?.id, initialTool) {
        if (uiState.project != null && initialTool != null) {
            when(initialTool) {
                "Music" -> showAudioMenu = true
                "Text" -> viewModel.addTextDraft()?.let { textEditor = TextEditorState(it, true, null) }
                "Aspect Ratio" -> showCanvas = true
            }
            onInitialToolHandled()
        }
    }

    // Pause playback when the app goes to the background; the engine keeps the
    // playhead so playback resumes where the user left off.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val playbackOwner = Any()
        viewModel.previewController.bindPlaybackOwner(playbackOwner)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.previewController.resumePlayback(playbackOwner)
                Lifecycle.Event.ON_STOP -> viewModel.previewController.suspendPlayback(playbackOwner)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.previewController.releasePlaybackOwner(playbackOwner)
        }
    }

    val callbacks = remember(viewModel) {
        TimelineCallbacks(
            onViewportSize = viewModel::onViewportSize,
            onTap = { hit ->
                viewModel.onTap(hit)
                val selected = viewModel.uiState.value.selection
                if (selected is TimelineSelection.Text) viewModel.requestCanvasEditing(selected)
            },
            onScrubBegin = viewModel::beginScrub,
            onScrubBy = viewModel::scrubBy,
            onScrollEnd = viewModel::endScroll,
            onFling = viewModel::fling,
            onZoomBegin = viewModel::beginZoom,
            onZoomBy = viewModel::zoomBy,
            onZoomEnd = viewModel::endZoom,
            onTrimBegin = viewModel::beginTrim,
            onTrimBy = viewModel::trimBy,
            onTrimEnd = viewModel::endTrim,
            onReorderBegin = viewModel::beginReorder,
            onReorderTo = viewModel::reorderTo,
            onReorderEnd = viewModel::endReorder,
            onFreeMoveBegin = viewModel::beginFreeMove,
            onFreeMoveBy = viewModel::freeMoveBy,
            onFreeMoveEnd = viewModel::endFreeMove,
            onAudioEnvelope = { id, envelope -> viewModel.updateAudioEnvelope(id, envelope) },
            onTextTrimBegin = viewModel::beginTextTrim,
            onTextTrimBy = viewModel::textTrimBy,
            onTextTrimEnd = viewModel::endTextTrim,
        )
    }

    val isLandscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showAudioNotice by remember { mutableStateOf(false) }
    if (showAudioNotice) {
        AlertDialog(
            onDismissRequest = { showAudioNotice = false },
            containerColor = VEColor.DarkPrimarySurface,
            titleContentColor = Color.White,
            textContentColor = VEColor.DarkSecondaryText,
            title = { Text("Audio preview limited") },
            text = {
                val affected = uiState.project?.timeline?.audioClips.orEmpty()
                    .filter { it.id in uiState.preview.omittedAudioClipIds }
                    .map { uiState.project?.mediaRef(it.mediaRefId)?.displayName ?: "Audio clip" }
                Text("Some audio clips cannot be heard in preview because they start later or are shorter than the composition. Export includes this audio. Affected clips: " + affected.joinToString())
            },
            confirmButton = { TextButton(onClick = { showAudioNotice = false }) { Text("Got it") } },
        )
    }
    val audioEffectPanelOpen = showAutomationPanel || showEqualizerPanel || showBalancePanel || showDuckingPanel || showFadePanel
    val corePanelOpen = audioEffectPanelOpen || showAudioMenu || galleryPanel != null || showTrimPanel || showSplitPanel || showSpeedSheet || showTransformSheet || showVolumeSheet || showCanvas || colorPanel != null || transitionOutgoing != null || textEditor != null || showPipTools || showStickerTools || showPhotoDuration || showEffectsPanel || showMaskSheet || showChromaSheet || showBlendSheet
    val panelKey = when {
        showAutomationPanel -> "Automation"; showEqualizerPanel -> "Equalizer"
        showBalancePanel -> "Balance"; showDuckingPanel -> "Ducking"; showFadePanel -> "Fade"
        showEffectsPanel -> "Effects"; showMaskSheet -> "Mask"
        showChromaSheet -> "Chroma Key"; showBlendSheet -> "Blend Mode"
        colorPanel != null -> colorPanel!!; textEditor != null -> "Text"; showPipTools -> "PiP"
        showStickerTools -> "Stickers"
        showSplitPanel -> "Split"; showTrimPanel -> "Trim"; showSpeedSheet -> "Speed"
        showVolumeSheet -> "Volume"; transitionOutgoing != null -> "Transitions"
        showAudioMenu -> "Audio"; showCanvas -> "Canvas"; showTransformSheet -> "Transform"
        else -> galleryPanel ?: "Main"
    }
    val panelExpansion = rememberSaveable(panelKey) { mutableStateOf(false) }
    val panelExpanded = corePanelOpen && panelExpansion.value
    var selectionActionsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(corePanelOpen, uiState.selection) {
        selectionActionsExpanded = uiState.selection !is TimelineSelection.None
    }
    val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val timelinePanel: @Composable () -> Unit = {
        if (uiState.preview.omittedAudioClipIds.isNotEmpty()) {
            Text(
                "ⓘ Audio preview limited",
                color = VEColor.DarkSecondaryText,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().background(VEColor.DarkPrimarySurface)
                    .clickable { showAudioNotice = true }.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // Transport: time, boundary nav, play/pause, undo/redo.
        if (!isLandscape) TransportBar(
            uiState = uiState,
            viewModel = viewModel,
            historyEnabled = !corePanelOpen || showPipTools,
            timelineOptions = { TimelineOptions(expandedTimeline, { expandedTimeline = !expandedTimeline },
                { dismissAllTools(); showPipTools = true }, viewModel::zoomToFit, viewModel::skipToPreviousBoundary,
                viewModel::skipToNextBoundary, { viewModel.stepZoom(.8f) }, { viewModel.stepZoom(1.25f) }, launchAddMedia) },
        )

        Column(Modifier.heightIn(max = if (corePanelOpen) 142.dp else if (expandedTimeline) 300.dp else 220.dp)
            .verticalScroll(rememberScrollState())) {
        androidx.compose.animation.AnimatedVisibility(visible = !keyboardVisible && !panelExpanded,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180))) {
        TimelineView(
            state = uiState,
            liveState = liveTimelineState,
            assets = assets,
            onRefreshAssets = { content, thumbHeight ->
                viewModel.refreshAssets(content, thumbHeight)
            },
            callbacks = remember(callbacks, corePanelOpen) { if (corePanelOpen) callbacks.navigationOnly() else callbacks },
            onEditTransition = if (corePanelOpen) null else { a,b -> transitionOutgoing=a;transitionIncoming=b },
            onAddMedia = if (corePanelOpen) null else launchAddMedia,
            expanded = expandedTimeline,
            compact = corePanelOpen,
            reserveOverlayLane = showPipTools,
            compactRows = when {
                audioEffectPanelOpen || showSplitPanel || showVolumeSheet || showAudioMenu -> setOf(com.videoeditor.editing.timeline.TimelineLayout.ROW_VIDEO, com.videoeditor.editing.timeline.TimelineLayout.ROW_AUDIO)
                textEditor != null -> setOf(com.videoeditor.editing.timeline.TimelineLayout.ROW_VIDEO, com.videoeditor.editing.timeline.TimelineLayout.ROW_TEXT)
                else -> setOf(com.videoeditor.editing.timeline.TimelineLayout.ROW_VIDEO)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        }
        if (!keyboardVisible && !panelExpanded && (!corePanelOpen || showPipTools)) PipTimelineLane(viewModel, compact = corePanelOpen, navigation = callbacks) { dismissAllTools(); showPipTools = true }
        if (!keyboardVisible && !panelExpanded) StickerTimelineLane(viewModel, compact = corePanelOpen, navigation = callbacks) { dismissAllTools(); showStickerTools = true }
        }
    }
    val selectionActions: @Composable () -> Unit = {
        if (!corePanelOpen && selectionActionsExpanded) ContextualActionBar(
            timelineOptions = {
                TimelineOptions(expandedTimeline, { expandedTimeline = !expandedTimeline },
                    { dismissAllTools(); showPipTools = true }, viewModel::zoomToFit, viewModel::skipToPreviousBoundary,
                    viewModel::skipToNextBoundary, { viewModel.stepZoom(.8f) }, { viewModel.stepZoom(1.25f) },
                    onAddMedia = launchAddMedia)
            },
            onAddMedia = launchAddMedia,
            uiState = uiState,
            viewModel = viewModel,
            onSplit = { showTrimPanel = false; showSpeedSheet = false; showTransformSheet = false; showSplitPanel = true },
            onDuplicate = viewModel::duplicateSelectedClip,
            onDelete = viewModel::deleteSelected,
            onTrim = {
                val clip = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }
                if (clip?.isPhoto == true) showPhotoDuration = true
                else if (clip != null) { showSplitPanel = false; showSpeedSheet = false; showTransformSheet = false; showTrimPanel = true; viewModel.focusOnSelectedClip() }
                else viewModel.focusOnSelectedClip()
            },
            onTransform = { transformDraft = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }?.transform; showTrimPanel = false; showSplitPanel = false; showSpeedSheet = false; showTransformSheet = true },
            onSpeed = { showTrimPanel = false; showSplitPanel = false; showTransformSheet = false; showSpeedSheet = true },
            onTransition = {
                val clips=uiState.project?.timeline?.videoClips.orEmpty()
                val index=clips.indexOfFirst { it.id==uiState.selection.videoClipId }
                val pair=if(index in 0 until clips.lastIndex) clips[index] to clips[index+1]
                    else if(index>0) clips[index-1] to clips[index] else null
                pair?.let { transitionOutgoing=it.first.id;transitionIncoming=it.second.id }
            },
            onVolume = { showVolumeSheet = true },
            onAutomation = { showAutomationPanel = true },
            onFade = { showFadePanel = true },
            onDucking = { showDuckingPanel = true },
            onEqualizer = { showEqualizerPanel = true },
            onBalance = { showBalancePanel = true },
            onEditText = {
                val overlayId = uiState.selection.selectedTextOverlayId ?: return@ContextualActionBar
                viewModel.beginTextEditing(overlayId)
                textEditor = TextEditorState(
                    overlayId = overlayId,
                    isNew = false,
                    snapshot = uiState.project?.timeline?.textOverlays
                        ?.firstOrNull { it.id == overlayId },
                )
            },
            onAddText = {
                dismissAllTools()
                val draftId = viewModel.addTextDraft()
                if (draftId != null) {
                    textEditor = TextEditorState(
                        overlayId = draftId,
                        isNew = true,
                        snapshot = null,
                    )
                }
            },
            onEffects = { showEffectsPanel = true },
            onOverlayTools = { showPipTools = true },
            onMask = { showMaskSheet = true },
            onChroma = { showChromaSheet = true },
            onBlend = { showBlendSheet = true },
            onStickerTools = { showStickerTools = true },
            onReplaceSticker = {
                replacingStickerId = (uiState.selection as? TimelineSelection.Sticker)?.stickerId
                galleryPanel = "Stickers"
            },
        )



    }
    val activeToolPanel: @Composable () -> Unit = {
        if (showAudioMenu) EditorAudioPanel(uiState, { showAudioMenu = false },
            { showAudioMenu = false; audioPicker.launch(arrayOf("audio/*")) },
            { showAudioMenu = false; viewModel.extractSelectedAudio() },
            { showAudioMenu = false; viewModel.previewController.pause(); microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            { id -> showAudioMenu = false; viewModel.addProjectAudio(id) })
        galleryPanel?.let { panel ->
            if (panel == "Stickers") EditorStickerPanel(
                onSelect = { key ->
                    val original = uiState.project?.timeline?.stickers?.firstOrNull { it.id == replacingStickerId }
                    galleryPanel = null; replacingStickerId = null
                    if (original != null) viewModel.updateSticker(original.copy(assetKey = key)) else viewModel.addSticker(key)
                },
                onClose = { galleryPanel = null; replacingStickerId = null },
            ) else if (panel == "Effects") {
                val targetClipId = uiState.selectedOverlayId ?: uiState.selection.videoClipId ?: uiState.project?.timeline?.videoClips?.firstOrNull()?.id
                if (targetClipId != null) {
                    VisualEffectsPanel(
                        viewModel = viewModel,
                        targetClipId = targetClipId,
                        isOverlay = uiState.selectedOverlayId != null,
                        onClose = { galleryPanel = null }
                    )
                } else {
                    galleryPanel = null
                }
            }
        }

        if (showEffectsPanel) {
            val targetClipId = uiState.selectedOverlayId ?: uiState.selection.videoClipId ?: uiState.project?.timeline?.videoClips?.firstOrNull()?.id
            if (targetClipId != null) {
                VisualEffectsPanel(
                    viewModel = viewModel,
                    targetClipId = targetClipId,
                    isOverlay = uiState.selectedOverlayId != null,
                    onClose = { showEffectsPanel = false }
                )
            }
        }
        if (showMaskSheet) {
            uiState.selectedOverlayId?.let { id ->
                MaskToolsSheet(viewModel, id, onDismiss = { showMaskSheet = false })
            }
        }
        if (showChromaSheet) {
            uiState.selectedOverlayId?.let { id ->
                ChromaKeySheet(viewModel, id, onDismiss = { showChromaSheet = false })
            }
        }
        if (showBlendSheet) {
            uiState.selectedOverlayId?.let { id ->
                BlendModeSheet(viewModel, id, onDismiss = { showBlendSheet = false })
            }
        }

        if (showPhotoDuration) {
            val initial = (uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }?.photoDurationMicros ?: 3_000_000L) / 1_000_000f
            var seconds by remember { mutableStateOf(initial) }
            EditorToolPanel("Photo duration", { showPhotoDuration = false }, { seconds = initial },
                { viewModel.setPhotoDuration((seconds * 1_000_000).toLong()); showPhotoDuration = false }) {
                Text("${"%.2f".format(seconds)} s", color = Color.White)
                Slider(seconds, { seconds = it }, valueRange = .1f..60f,
                    modifier = Modifier.testTag("photo-duration-slider").semantics { contentDescription = "Photo duration in seconds" })
            }
        }

    if (showPipTools) PipToolsSheet(viewModel,
        onAdd = { showPipTools = false; overlayPicker.launch(arrayOf("video/*", "image/*")) },
        onDismiss = { showPipTools = false })
    if (showStickerTools) StickerToolsSheet(viewModel, onDismiss = { showStickerTools = false })
    textEditor?.let { editorState ->
        val overlay = uiState.project?.timeline?.textOverlays
            ?.firstOrNull { it.id == editorState.overlayId }
        if (overlay == null) {
            // The lifecycle flow emits an empty initial state on recreation.
            // Only a loaded project can establish that the overlay was deleted.
            if (uiState.project?.id == projectId) textEditor = null
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextEditorSheet(
                    overlay = overlay,
                    isNew = editorState.isNew,
                    onTextChange = viewModel::setSelectionText,
                    onFontSize = viewModel::setSelectionFontSize,
                    onColor = viewModel::setSelectionTextColor,
                    onAlignment = viewModel::setSelectionAlignment,
                    onOpacity = viewModel::setSelectionOpacity,
                    onBackground = viewModel::setSelectionBackground,
                    onStyle = viewModel::updateSelectedText,
                    onDone = { viewModel.finishTextEditing(true); textEditor = null },
                    onCancel = {
                        viewModel.finishTextEditing(false)
                        textEditor = null
                    },
                )
            }
        }
    }

    if(transitionOutgoing!=null && transitionIncoming!=null) TransitionToolsSheet(viewModel,
        transitionOutgoing!!,transitionIncoming!!) { transitionOutgoing=null;transitionIncoming=null }

        if (showCanvas) uiState.project?.let { project ->
            DisposableEffect(Unit) { onDispose { canvasDraft = null; viewModel.restoreToolPreview() } }
            CanvasRatioSheet(project.canvas, viewModel::setCanvasDesign,
                { showCanvas = false; canvasDraft = null }, { preset, mode, background -> canvasDraft = viewModel.previewCanvas(preset, mode, background) }, title = canvasTitle)
        }

        if (showTrimPanel) uiState.project?.timeline?.videoClips
            ?.firstOrNull { it.id == uiState.selection.videoClipId }?.let { clip ->
                TrimToolPanel(clip, uiState.project?.timeline?.transitions?.any { it.outgoingClipId == clip.id || it.incomingClipId == clip.id } == true,
                    { viewModel.uiState.value.playheadMicros },
                    { showTrimPanel = false }, { start, end ->
                        if (viewModel.trimSelectedSource(start, end)) showTrimPanel = false
                    })
            }
        if (showSplitPanel) SplitToolPanel(viewModel) { showSplitPanel = false }


    if (showSpeedSheet) {
        val audio = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId }
        SpeedDialog(
            videoProject = uiState.project.takeIf { audio == null },
            videoClipId = uiState.selection.videoClipId,
            sourceDuration = audio?.sourceDurationMicros ?: uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }?.sourceDurationMicros ?: 0L,
            current = audio?.playbackSpeed ?: uiState.project?.timeline?.videoClips
                ?.firstOrNull { it.id == uiState.selection.videoClipId }
                ?.playbackSpeed ?: 1f,
            onDismiss = { showSpeedSheet = false },
            onSelect = { speed, shorten ->
                if (audio != null) viewModel.updateSelectedAudio { it.copy(playbackSpeed = speed) }
                else viewModel.setSpeed(speed, shorten)
                showSpeedSheet = false
            },
        )
    }
    if (showTransformSheet) {
        uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }?.let { clip ->
            TransformDialog(clip.transform, uiState.project!!.canvas.scaleMode,
                assets.strips[clip.id]?.frames?.firstOrNull { it != null },
                transformDraft ?: clip.transform, { value, mode -> transformDraft = value; transformMode = mode },
                viewModel::previewVisualTransform, viewModel::restoreToolPreview,
                viewModel::setVisualTransformAndCanvasMode,
                { showTransformSheet = false })
        }
    }
        if (showVolumeSheet) {
        VolumeDialog(
            current = currentVolume(uiState),
            currentMuted = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }?.muted
                ?: uiState.project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId }?.muted ?: false,
            peaks = assets.waveforms[uiState.selection.selectedAudioClipId],
            sourceTitle = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId }
                ?.let { uiState.project?.mediaRef(it.mediaRefId)?.displayName }.orEmpty(),
            playing = uiState.preview.isPlaying,
            onPlayback = viewModel.previewController::togglePlayPause,
            onDismiss = { showVolumeSheet = false },
            onChange = viewModel::setClipAudioVolume,
            audio = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId },
            onAudio = viewModel::updateSelectedAudio,
        )
    }
    // Stage 8 audio tool panels. Each resolves the selected clip live and commits
    // one undo entry through the same updateSelectedAudio command path as the
    // existing volume sheet, so every tool is undoable and persists identically.
    if (showAutomationPanel) {
        val clip = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId }
        if (clip != null) {
            VolumeAutomationPanel(
                audioClip = clip,
                peaks = assets.waveforms[clip.id],
                playheadMicros = uiState.playheadMicros,
                onPlayhead = viewModel::seekTo,
                onEnvelope = { envelope -> viewModel.updateSelectedAudio { it.copy(volumeEnvelope = envelope) } },
                onReset = { viewModel.updateSelectedAudio { it.copy(volumeEnvelope = com.videoeditor.core.model.VolumeEnvelope()) } },
                onDismiss = { showAutomationPanel = false },
            )
        } else {
            showAutomationPanel = false
        }
    }
    if (showEqualizerPanel) {
        val clip = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId }
        if (clip != null) {
            EqualizerPanel(
                audioClip = clip,
                onEqualizer = { eq -> viewModel.updateSelectedAudio { it.copy(equalizer = eq) } },
                onReset = { viewModel.updateSelectedAudio { it.copy(equalizer = com.videoeditor.core.model.EqualizerSettings()) } },
                onDismiss = { showEqualizerPanel = false },
            )
        } else {
            showEqualizerPanel = false
        }
    }
    if (showBalancePanel) {
        val clip = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId }
        if (clip != null) {
            AudioBalancePanel(
                audioClip = clip,
                onBalance = { balance -> viewModel.updateSelectedAudio { it.copy(balance = balance) } },
                onReset = { viewModel.updateSelectedAudio { it.copy(balance = com.videoeditor.core.model.AudioBalance()) } },
                onDismiss = { showBalancePanel = false },
            )
        } else {
            showBalancePanel = false
        }
    }
    if (showFadePanel) {
        val clip = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId }
        if (clip != null) {
            FadeCurvePanel(
                audioClip = clip,
                onFade = { fadeIn, fadeOut, fadeInCurve, fadeOutCurve ->
                    viewModel.updateSelectedAudio {
                        it.copy(fadeInMicros = fadeIn, fadeOutMicros = fadeOut, fadeInCurve = fadeInCurve, fadeOutCurve = fadeOutCurve)
                    }
                },
                onReset = { viewModel.updateSelectedAudio {
                    it.copy(fadeInMicros = 0L, fadeOutMicros = 0L, fadeInCurve = com.videoeditor.core.model.FadeCurve.Linear, fadeOutCurve = com.videoeditor.core.model.FadeCurve.Linear)
                } },
                onDismiss = { showFadePanel = false },
            )
        } else {
            showFadePanel = false
        }
    }
    if (showDuckingPanel) {
        val project = uiState.project
        val clip = project?.timeline?.audioClips?.firstOrNull { it.id == uiState.selection.selectedAudioClipId }
        if (project != null && clip != null) {
            DuckingPanel(
                audioClip = clip,
                project = project,
                analysisFailed = viewModel.duckingAnalysisFailed(clip.id),
                onDucking = { settings -> viewModel.updateSelectedAudio { it.copy(ducking = settings) } },
                onReset = { viewModel.updateSelectedAudio { it.copy(ducking = com.videoeditor.core.model.DuckingSettings()) } },
                onDismiss = { showDuckingPanel = false },
            )
        } else {
            showDuckingPanel = false
        }
    }
        colorPanel?.let { panel ->
        val clip = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }
            ?: uiState.project?.timeline?.videoClips?.firstOrNull { it.timelineStartMicros <= uiState.playheadMicros && it.timelineEndMicros > uiState.playheadMicros }
            ?: uiState.project?.timeline?.videoClips?.firstOrNull()
        clip?.let { targetClip ->
            ColorGradeDialog(targetClip.filter, targetClip.colorAdjustments, panel,
                assets.strips[targetClip.id]?.frames?.firstOrNull { it != null },
                viewModel::setColorGrade, { colorPanel = null }, viewModel::previewColorGrade, viewModel::restoreToolPreview)
        }
    }

    }
    val primaryTools: @Composable () -> Unit = {
        BottomToolbar(
            navigationEnabled = !keyboardVisible && textEditor == null,
            visualEnabled = uiState.project?.timeline?.videoClips?.isNotEmpty() == true,
            speedEnabled = uiState.project?.timeline?.videoClips?.any { !it.isPhoto } == true,
            selectedTool = when {
                showAudioMenu -> "Audio"
                showStickerTools -> "Stickers"
                galleryPanel != null -> galleryPanel!!
                showSpeedSheet -> "Speed"
                showVolumeSheet -> "Audio"
                showCanvas -> "Canvas"
                showPipTools -> "Overlay"
                colorPanel != null -> colorPanel!!
                transitionOutgoing != null -> "Transition"
                else -> when (uiState.selection) {
                is TimelineSelection.Audio -> "Audio"
                is TimelineSelection.Text -> "Text"
                is TimelineSelection.Sticker -> "Stickers"
                else -> "Edit"
                }
            },
            onEdit = {
                val clip = uiState.project?.timeline?.videoClips?.firstOrNull {
                    uiState.playheadMicros in it.timelineStartMicros until it.timelineEndMicros
                } ?: uiState.project?.timeline?.videoClips?.firstOrNull()
                clip?.let { viewModel.select(TimelineSelection.Video(it.id)) }
            },
            onAudio = {
                dismissAllTools()
                if (uiState.selection !is TimelineSelection.Audio && uiState.selection !is TimelineSelection.Video) {
                    val clip = uiState.project?.timeline?.videoClips?.firstOrNull {
                        uiState.playheadMicros in it.timelineStartMicros until it.timelineEndMicros && !it.isPhoto
                    }
                    viewModel.select(clip?.let { TimelineSelection.Video(it.id) } ?: TimelineSelection.None)
                }
                showAudioMenu = true
            },
            onText = {
                val selectedTextId = uiState.selection.selectedTextOverlayId
                    ?: uiState.project?.timeline?.textOverlays?.firstOrNull {
                        uiState.playheadMicros in it.startMicros..(it.startMicros + it.durationMicros)
                    }?.id
                if (selectedTextId != null) {
                    dismissAllTools()
                    textEditor = TextEditorState(
                        overlayId = selectedTextId,
                        isNew = false,
                        snapshot = uiState.project?.timeline?.textOverlays?.firstOrNull { it.id == selectedTextId },
                    )
                } else {
                    dismissAllTools()
                    val draftId = viewModel.addTextDraft()
                    if (draftId != null) {
                        textEditor = TextEditorState(
                            overlayId = draftId,
                            isNew = true,
                            snapshot = null,
                        )
                    }
                }
            },
            transitionEnabled = nearestVideoJoin(uiState) != null,
            onTransition = {
                nearestVideoJoin(uiState)?.let { pair ->
                    dismissAllTools(); transitionOutgoing = pair.first.id; transitionIncoming = pair.second.id
                }
            },
            onCanvas = { dismissAllTools(); canvasTitle = "Canvas"; showCanvas = true },
            onStickers = {
                dismissAllTools()
                if (uiState.selection !is TimelineSelection.Sticker) viewModel.select(TimelineSelection.None)
                galleryPanel = "Stickers"
            },
            onEffects = { dismissAllTools(); galleryPanel = "Effects" },
            onOverlay = { dismissAllTools(); showPipTools = true },
            onFilters = {
                dismissAllTools()
                val targetClip = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }
                    ?: uiState.project?.timeline?.videoClips?.firstOrNull { it.timelineStartMicros <= uiState.playheadMicros && it.timelineEndMicros > uiState.playheadMicros }
                    ?: uiState.project?.timeline?.videoClips?.firstOrNull()
                targetClip?.let {
                    viewModel.select(TimelineSelection.Video(it.id))
                    colorPanel = "Filters"
                }
            },
            onAdjust = {
                dismissAllTools()
                val targetClip = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }
                    ?: uiState.project?.timeline?.videoClips?.firstOrNull { it.timelineStartMicros <= uiState.playheadMicros && it.timelineEndMicros > uiState.playheadMicros }
                    ?: uiState.project?.timeline?.videoClips?.firstOrNull()
                targetClip?.let {
                    viewModel.select(TimelineSelection.Video(it.id))
                    colorPanel = "Adjust"
                }
            },
            onSpeed = {
                val targetClip = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }
                    ?: uiState.project?.timeline?.videoClips?.firstOrNull { it.timelineStartMicros <= uiState.playheadMicros && it.timelineEndMicros > uiState.playheadMicros && !it.isPhoto }
                    ?: uiState.project?.timeline?.videoClips?.firstOrNull { !it.isPhoto }
                targetClip?.let {
                    dismissAllTools()
                    viewModel.select(TimelineSelection.Video(it.id))
                    showSpeedSheet = true
                }
            },
        )
    }

    CompositionLocalProvider(LocalEditorPanelExpansion provides panelExpansion) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
    val previewHeight = EditorMetrics.previewHeight(maxHeight, corePanelOpen, keyboardVisible, panelExpanded)
    Column(modifier = Modifier.fillMaxSize().background(VEColor.DarkBackground)) {
        EditorTopBar(
            title = uiState.project?.name ?: "Editor",
            canUndo = uiState.canUndo && !corePanelOpen,
            canRedo = uiState.canRedo && !corePanelOpen,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onBack = {
                when {
                    showPipTools -> showPipTools = false
                    showAudioMenu -> showAudioMenu = false
                    galleryPanel != null -> galleryPanel = null
                    corePanelOpen -> showDiscardTool = true
                    else -> closeEditor()
                }
            },
            onExport = { viewModel.saveProject(onExport) },
            exportEnabled = uiState.project != null && !corePanelOpen,
            onToggleFullscreen = viewModel::toggleFullscreenPreview,
            onCanvas = { canvasTitle = "Crop & Ratio"; showCanvas = true },
            resolutionLabel = uiState.project?.exportPreferences?.resolution?.label?.uppercase() ?: "—",
            currentResolution = uiState.project?.exportPreferences?.resolution,
            onSelectResolution = viewModel::setExportResolution,
        )
        when {
            uiState.isFullscreenPreview -> {
                PreviewSurface(Modifier.fillMaxWidth().weight(1f), if (canvasDraft != null) uiState.copy(project = uiState.project?.copy(canvas = canvasDraft!!)) else uiState, viewModel, if (showTransformSheet) transformDraft else null, { transformDraft = it }, transformMode, !corePanelOpen || textEditor != null || showPipTools, canvasGuide = showCanvas)
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(onClick = viewModel::toggleFullscreenPreview)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(VESpacing.Sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Tap to exit fullscreen", color = Color.White,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            isLandscape -> {
                // Fixed-height controls must not consume the landscape preview.
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(modifier = Modifier.weight(0.45f).fillMaxSize()) {
                        PreviewSurface(Modifier.fillMaxWidth().weight(1f), if (canvasDraft != null) uiState.copy(project = uiState.project?.copy(canvas = canvasDraft!!)) else uiState, viewModel, if (showTransformSheet) transformDraft else null, { transformDraft = it }, transformMode, !corePanelOpen || textEditor != null || showPipTools, canvasGuide = showCanvas)
                        TransportBar(uiState, !corePanelOpen || textEditor != null || showPipTools, viewModel, compact = true)
                    }
                    Column(modifier = Modifier.weight(0.55f).fillMaxSize()) {
                        Box(Modifier.weight(if (corePanelOpen) .35f else 1f)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) { timelinePanel() }
                        }
                        if (corePanelOpen) Box(Modifier.weight(.65f)) { activeToolPanel() }
                        if (!keyboardVisible && !corePanelOpen) selectionActions()
                        if (!keyboardVisible && !corePanelOpen) primaryTools()
                    }
                }
            }
            else -> {
                PreviewSurface(Modifier.fillMaxWidth().height(previewHeight), if (canvasDraft != null) uiState.copy(project = uiState.project?.copy(canvas = canvasDraft!!)) else uiState, viewModel, if (showTransformSheet) transformDraft else null, { transformDraft = it }, transformMode, !corePanelOpen || textEditor != null || showPipTools, canvasGuide = showCanvas)
                timelinePanel()
                if (corePanelOpen) Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    activeToolPanel()
                }
                else Spacer(Modifier.weight(1f))
                if (!keyboardVisible && !corePanelOpen) selectionActions()
                if (!keyboardVisible && !corePanelOpen) primaryTools()
            }
        }
    }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    } // Box
    } // adaptive panel scope


    if (showUnsaved) com.videoeditor.core.designsystem.component.DecisionDialog(
        title = "Unsaved Changes", message = "You have unsaved changes in this project.",
        primaryLabel = "Save and Continue", onPrimary = { viewModel.saveProject { showUnsaved = false; onBack() } },
        secondaryLabel = "Discard Changes", onSecondary = { showUnsaved = false; onBack() },
        onDismiss = { showUnsaved = false }, forceDark = true)
    if (showVoiceover) VoiceoverDialog(viewModel::addAudioFromUri, { showVoiceover = false })
    if (microphoneDenied) com.videoeditor.core.designsystem.component.DecisionDialog(
        title = "Microphone permission", forceDark = true, icon = Icons.Outlined.MicOff,
        message = "Allow microphone access in Android settings to record a voiceover. You can still import audio files.",
        primaryLabel = "Open Settings", onPrimary = {
            microphoneDenied = false
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        }, onDismiss = { microphoneDenied = false }, dismissLabel = "Done")


}

private fun currentVolume(state: EditorUiState): Float {
    val project = state.project ?: return 1f
    return when (val selection = state.selection) {
        is TimelineSelection.Video ->
            project.timeline.videoClips.firstOrNull { it.id == selection.clipId }?.volume ?: 1f

        is TimelineSelection.Audio ->
            project.timeline.audioClips.firstOrNull { it.id == selection.audioClipId }?.volume ?: 1f

        else -> 1f
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PreviewSurface(
    modifier: Modifier,
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    transformDraft: com.videoeditor.core.model.VisualTransform? = null,
    onTransformDraft: (com.videoeditor.core.model.VisualTransform) -> Unit = {},
    transformMode: com.videoeditor.core.model.ScaleMode? = null,
    allowLayerGestures: Boolean = true,
    canvasGuide: Boolean = false,
) {
    Box(
        modifier = modifier.testTag("editor-preview").clipToBounds().background(if (canvasGuide || transformDraft != null) VEColor.DarkSecondarySurface else Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val canvas = uiState.project?.canvas
        val previewInset = if (canvasGuide || transformDraft != null) 8.dp else 0.dp
        AndroidView(
            factory = { ctx ->
                AspectRatioFrameLayout(ctx).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    addView(
                        SurfaceView(ctx).also { viewModel.previewController.attachSurface(it) },
                    )
                }
            },
            update = { frame ->
                if (canvas != null && canvas.height > 0) {
                    frame.setAspectRatio(canvas.width.toFloat() / canvas.height)
                    // Keep the producer buffer independent of adaptive view bounds.
                    // Changing panel height must not destroy/reprepare the decoder graph.
                    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                    val divisor = gcd(canvas.width, canvas.height).coerceAtLeast(1)
                    val unitWidth = canvas.width / divisor
                    val unitHeight = canvas.height / divisor
                    val multiplier = minOf(divisor, 1080 / maxOf(unitWidth, unitHeight))
                    val scale = minOf(1f, 1080f / maxOf(canvas.width, canvas.height))
                    val bufferWidth = if (multiplier > 0) unitWidth * multiplier else (canvas.width * scale).toInt().coerceAtLeast(1)
                    val bufferHeight = if (multiplier > 0) unitHeight * multiplier else (canvas.height * scale).toInt().coerceAtLeast(1)
                    val bufferSize = bufferWidth to bufferHeight
                    if (frame.tag != bufferSize) {
                        (frame.getChildAt(0) as SurfaceView).holder.setFixedSize(bufferWidth, bufferHeight)
                        frame.tag = bufferSize
                    }
                }
            },
            onRelease = { frame ->
                (frame.getChildAt(0) as? SurfaceView)?.let {
                    viewModel.previewController.detachSurface(it)
                }
            },
            modifier = Modifier.fillMaxSize().padding(previewInset),
        )
        if (transformDraft != null && canvas != null) {
            val clip = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == uiState.selection.videoClipId }
            val source = clip?.let { uiState.project?.mediaRef(it.mediaRefId) }
            val rotated = (source?.rotationDegrees ?: 0) % 180 != 0
            val sourceWidth = (if (rotated) source?.height else source?.width) ?: canvas.width
            val sourceHeight = (if (rotated) source?.width else source?.height) ?: canvas.height
            TransformSelectionHandles(Modifier.fillMaxSize().padding(previewInset), canvas.copy(scaleMode = transformMode ?: canvas.scaleMode), sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1),
                transformDraft, onTransformDraft)
        } else if (allowLayerGestures) {
            LayerCanvasHandles(Modifier.fillMaxSize(), viewModel)
        }
        var showBufferingIndicator by remember { mutableStateOf(false) }
        val isInteractingWithOverlay = transformDraft != null ||
            uiState.selection is TimelineSelection.Text ||
            uiState.selection is TimelineSelection.Overlay ||
            uiState.selection is TimelineSelection.Sticker
        val waitingForFrame = uiState.preview.error == null &&
            !uiState.preview.isPlaying &&
            uiState.gesture !is TimelineGesture.Scrolling &&
            !isInteractingWithOverlay &&
            (uiState.preview.isBuffering ||
             uiState.preview.phase in setOf(com.videoeditor.core.media.preview.PreviewPhase.Seeking,
                 com.videoeditor.core.media.preview.PreviewPhase.Recovering, com.videoeditor.core.media.preview.PreviewPhase.Reconfiguring))
        LaunchedEffect(waitingForFrame) {
            if (waitingForFrame) {
                kotlinx.coroutines.delay(400)
                showBufferingIndicator = true
            } else {
                showBufferingIndicator = false
            }
        }
        if (showBufferingIndicator) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp,
                )
                Text("Loading preview…", color = Color.White,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        uiState.preview.error?.let { error ->
            Surface(color = VEColor.DarkElevatedSurface.copy(alpha = .96f), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, VEColor.Error.copy(alpha = .45f)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(error.message, style = MaterialTheme.typography.bodySmall, color = VEColor.DarkPrimaryText)
                    if (error.recoverable) TextButton(onClick = viewModel.previewController::retryPreview,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("preview-retry")) { Text("Retry preview") }
                }
            }
        }
        if (uiState.project != null && uiState.project.timeline.videoClips.isEmpty()) {
            Text("Add media to begin editing", color = VEColor.DarkSecondaryText,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (canvasGuide && canvas != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(previewInset)) {
            val width = minOf(size.width, size.height * canvas.width.toFloat() / canvas.height)
            val height = width * canvas.height / canvas.width
            val left = (size.width - width) / 2
            val top = (size.height - height) / 2
            drawRect(VEColor.AccentCyan, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(width, height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            for (fraction in listOf(1f / 3, 2f / 3)) {
                drawLine(Color.White.copy(alpha = .2f), androidx.compose.ui.geometry.Offset(left + width * fraction, top), androidx.compose.ui.geometry.Offset(left + width * fraction, top + height), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = .2f), androidx.compose.ui.geometry.Offset(left, top + height * fraction), androidx.compose.ui.geometry.Offset(left + width, top + height * fraction), 1.dp.toPx())
            }
            listOf(androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Offset(left + width, top),
                androidx.compose.ui.geometry.Offset(left, top + height), androidx.compose.ui.geometry.Offset(left + width, top + height)).forEach {
                drawCircle(VEColor.AccentCyan, 4.dp.toPx(), it)
                drawCircle(Color.White, 2.7.dp.toPx(), it)
            }
        }

    }
}

@Composable
private fun EditorTopBar(
    title: String,
    onBack: () -> Unit,
    onExport: () -> Unit,
    exportEnabled: Boolean,
    onToggleFullscreen: () -> Unit,
    onCanvas: () -> Unit,
    resolutionLabel: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    currentResolution: com.videoeditor.core.model.ExportResolution? = null,
    onSelectResolution: ((com.videoeditor.core.model.ExportResolution) -> Unit)? = null,
) {
    var showResolutionMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EditorMetrics.Header).padding(start = 4.dp, end = 12.dp).testTag("editor-header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            EditorIcon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close", tint = Color.White)
        }
        Text(title, color = Color.White, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onUndo, enabled = canUndo) {
            EditorIcon(Icons.AutoMirrored.Outlined.Undo, "Undo",
                tint = if (canUndo) Color.White else VEColor.DarkMutedText, modifier = Modifier.size(EditorMetrics.Icon))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            EditorIcon(Icons.AutoMirrored.Outlined.Redo, "Redo",
                tint = if (canRedo) Color.White else VEColor.DarkMutedText, modifier = Modifier.size(EditorMetrics.Icon))
        }
        Box(
            modifier = Modifier.height(48.dp).clickable(enabled = exportEnabled) {
                if (onSelectResolution != null) showResolutionMenu = true else onExport()
            }
                .padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp))
                .background(VEColor.DarkSecondarySurface).border(1.dp, VEColor.DarkBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp).testTag("editor-resolution-badge"), contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(resolutionLabel, style = MaterialTheme.typography.labelMedium, color = Color.White)
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_editor_chevron_down), null, Modifier.size(12.dp), tint = Color.White)
            }
            DropdownMenu(
                expanded = showResolutionMenu,
                onDismissRequest = { showResolutionMenu = false },
                modifier = Modifier.background(VEColor.DarkSecondarySurface).border(1.dp, VEColor.DarkBorder, RoundedCornerShape(8.dp)),
            ) {
                DropdownMenuItem(text = { Text("Canvas & aspect ratio") },
                    onClick = { showResolutionMenu = false; onCanvas() })
                com.videoeditor.core.model.ExportResolution.entries.forEach { res ->
                    val isSelected = currentResolution == res
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(res.label.uppercase(), color = if (isSelected) VEColor.AccentCyan else Color.White, style = MaterialTheme.typography.labelLarge)
                                Text(if (res == com.videoeditor.core.model.ExportResolution.RES_1080P) "1080p FHD" else "720p HD", color = VEColor.DarkSecondaryText, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            showResolutionMenu = false
                            onSelectResolution?.invoke(res)
                        },
                        modifier = Modifier.testTag("resolution-menu-${res.label}"),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.height(48.dp).clickable(enabled = exportEnabled, onClick = onExport)
                .padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(EditorAssetStyle.Export)
                .padding(horizontal = 12.dp).testTag("editor-export-button"), contentAlignment = Alignment.Center,
        ) {
            Text("Export", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlaybackTime(viewModel: EditorViewModel, compact: Boolean) {
    val text by viewModel.playbackTime.collectAsStateWithLifecycle(initialValue = "00:00")
    Text(text, modifier = Modifier.testTag("playback-time"), color = Color.White,
        style = (if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(fontFeatureSettings = "tnum"),
        fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold)
}

/**
 * Transport row: current/total time, clip-boundary navigation, play/pause, and
 * undo/redo. The timeline is the primary scrubber; this mirrors position and
 * offers precise boundary stepping.
 */
@Composable
private fun TransportBar(
    uiState: EditorUiState,
    historyEnabled: Boolean = true,
    viewModel: EditorViewModel,
    compact: Boolean = false,
    timelineOptions: (@Composable () -> Unit)? = null,
) {
    val duration = uiState.project?.timeline?.renderedDurationMicros ?: 0L
    val transportConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val separateTime = compact || transportConfiguration.fontScale > 1.2f
    Column(modifier = Modifier.fillMaxWidth().testTag("editor-transport").drawWithContent {
        drawContent()
        drawLine(VEColor.DarkBorder.copy(alpha = .65f), androidx.compose.ui.geometry.Offset(12.dp.toPx(), size.height),
            androidx.compose.ui.geometry.Offset(size.width - 12.dp.toPx(), size.height), 1.dp.toPx())
    }) {
        if (separateTime) {
            Row(Modifier.padding(horizontal = VESpacing.Sm)) {
                PlaybackTime(viewModel, compact = true)
                Text(" / ${formatEditorTimecode(duration)}", color = VEColor.DarkSecondaryText,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VESpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!separateTime) {
            PlaybackTime(viewModel, compact = false)
            Text(
                " / ${formatEditorTimecode(duration)}",
                style = MaterialTheme.typography.bodySmall,
                color = VEColor.DarkSecondaryText,
            )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::togglePlayPause, enabled = uiState.preview.hasComposition,
                modifier = Modifier.semantics {
                    stateDescription = when {
                        uiState.preview.isBuffering && uiState.preview.playRequested -> "Loading playback"
                        uiState.preview.isBuffering -> "Seeking"
                        uiState.isPlaying -> "Playing"
                        else -> "Paused"
                    }
                }) {
                EditorIcon(
                    if (uiState.preview.playRequested) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            timelineOptions?.invoke()
            IconButton(onClick = viewModel::toggleFullscreenPreview) {
                EditorIcon(Icons.Outlined.Fullscreen, "Fullscreen preview", tint = VEColor.DarkSecondaryText)
            }
        }
    }
}

/**
 * Contextual actions for the current selection. Only actions that are actually
 * implemented and valid for the selected track are shown; an empty selection
 * leaves global entry points to the primary toolbar.
 */
@Composable
private fun ContextualActionBar(
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onTrim: () -> Unit,
    onTransform: () -> Unit,
    onSpeed: () -> Unit,
    onTransition: () -> Unit,
    onVolume: () -> Unit,
    onAutomation: () -> Unit,
    onFade: () -> Unit,
    onDucking: () -> Unit,
    onEqualizer: () -> Unit,
    onBalance: () -> Unit,
    onEditText: () -> Unit,
    onAddText: (() -> Unit)? = null,
    onEffects: () -> Unit = {},
    onOverlayTools: () -> Unit = {},
    onMask: () -> Unit = {},
    onChroma: () -> Unit = {},
    onBlend: () -> Unit = {},
    onStickerTools: () -> Unit = {},
    onReplaceSticker: () -> Unit = {},
    onAddMedia: (() -> Unit)? = null,
    timelineOptions: (@Composable () -> Unit)? = null,
) {
    val selection = uiState.selection
    val actions = buildList {
        when (selection) {
            is TimelineSelection.Video -> {
                val isPhoto = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == selection.clipId }?.isPhoto == true
                if (!isPhoto) add(ActionChipUi("Split", onSplit))
                if(!isPhoto && (uiState.project?.timeline?.videoClips?.size ?: 0) > 1)
                    add(ActionChipUi("Transition",onTransition))
                add(ActionChipUi("Effects", onEffects))
                add(ActionChipUi(if (isPhoto) "Duration" else "Trim", onTrim))
                add(ActionChipUi("Crop", onTransform))
                add(ActionChipUi("Fade", viewModel::toggleSelectedFade))
                add(ActionChipUi("Rotate", {
                    val clip = uiState.project?.timeline?.videoClips?.firstOrNull { it.id == selection.clipId }
                    clip?.let { viewModel.setVisualTransform(it.transform.copy(rotationDegrees = (it.transform.rotationDegrees + 90) % 360)) }
                }))
                if (!isPhoto) {
                    add(ActionChipUi("Volume", onVolume))
                    add(ActionChipUi("Speed", onSpeed))
                }
                add(ActionChipUi("Duplicate", onDuplicate))
                add(ActionChipUi("Delete", onDelete, destructive = true))
            }

            is TimelineSelection.Audio -> {
                add(ActionChipUi("Trim", onTrim))
                add(ActionChipUi("Volume", onVolume))
                add(ActionChipUi("Automation", onAutomation))
                add(ActionChipUi("Fade", onFade))
                add(ActionChipUi("Ducking", onDucking))
                add(ActionChipUi("EQ", onEqualizer))
                add(ActionChipUi("Balance", onBalance))
                add(ActionChipUi("Speed", onSpeed))
                add(ActionChipUi("Split", onSplit))
                add(ActionChipUi("Duplicate", onDuplicate))
                val muted = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == selection.audioClipId }?.muted == true
                add(ActionChipUi(if (muted) "Unmute" else "Mute", {
                    val clip = uiState.project?.timeline?.audioClips?.firstOrNull { it.id == selection.audioClipId }
                    clip?.let { viewModel.setSelectedMuted(!it.muted) }
                }))
                add(ActionChipUi("Delete", onDelete, destructive = true))
            }

            is TimelineSelection.Text -> {
                add(ActionChipUi("Edit", onEditText))
                if (onAddText != null) add(ActionChipUi("Add Text", onAddText))
                add(ActionChipUi("Duplicate", onDuplicate))
                add(ActionChipUi("Delete", onDelete, destructive = true))
            }

            is TimelineSelection.Sticker -> {
                val stickerId = selection.stickerId
                add(ActionChipUi("Edit sticker", onStickerTools))
                add(ActionChipUi("Replace", onReplaceSticker))
                add(ActionChipUi("Duration", onStickerTools))
                add(ActionChipUi("Duplicate", onDuplicate))
                add(ActionChipUi("Bring Forward", { viewModel.reorderSticker(stickerId, true) }))
                add(ActionChipUi("Send Backward", { viewModel.reorderSticker(stickerId, false) }))
                add(ActionChipUi("Reset", { viewModel.resetStickerTransform(stickerId) }))
                add(ActionChipUi("Delete", onDelete, destructive = true))
            }

            is TimelineSelection.Overlay -> {
                add(ActionChipUi("Transform", onOverlayTools))
                add(ActionChipUi("Mask", onMask))
                add(ActionChipUi("Chroma", onChroma))
                add(ActionChipUi("Blend", onBlend))
                add(ActionChipUi("Effects", onEffects))
                add(ActionChipUi("Duplicate", onDuplicate))
                add(ActionChipUi("Delete", onDelete, destructive = true))
            }
            is TimelineSelection.Transition, is TimelineSelection.Effect, TimelineSelection.None -> {
                if (onAddMedia != null) add(ActionChipUi("Add Media", onAddMedia))
            }
        }
    }

    if (actions.isEmpty() && timelineOptions == null) return
    val availableActions = actions
    Row(Modifier.fillMaxWidth().background(VEColor.DarkPrimarySurface), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::clearSelection, modifier = Modifier.testTag("contextual-back")) {
            EditorIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Main tools", tint = VEColor.DarkSecondaryText)
        }
    Row(
        modifier = Modifier
            .weight(1f)
            .background(VEColor.DarkPrimarySurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(VESpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        availableActions.forEach { action ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(VERadius.Control))
                    .background(
                        if (action.destructive) {
                            VEColor.Error.copy(alpha = 0.15f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = if (action.destructive) {
                            VEColor.Error.copy(alpha = 0.5f)
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(VERadius.Control),
                    )
                    // Size before clickable so the whole chip is the touch
                    // target; at least 48dp in both dimensions.
                    .height(EditorMetrics.Dock)
                    .widthIn(min = 64.dp)
                    .testTag("clip-action-${action.label}")
                    .clickable(onClick = action.onClick)
                    .padding(horizontal = VESpacing.Sm),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val icon = when (action.label) {
                        "Add Media" -> Icons.Default.Add
                        "Transition" -> Icons.Outlined.CompareArrows
                        "Split" -> Icons.Outlined.ContentCut
                        "Trim" -> Icons.Outlined.CompareArrows
                        "Crop" -> Icons.Outlined.Crop
                        "Rotate" -> Icons.Outlined.RotateRight
                        "Fade" -> Icons.Outlined.Gradient
                        "Duration" -> Icons.Outlined.Timer
                        "Mute" -> Icons.Outlined.VolumeOff
                        "Unmute" -> Icons.Outlined.VolumeUp
                        "Volume" -> Icons.Outlined.VolumeUp
                        "Speed" -> Icons.Outlined.Speed
                        "Duplicate" -> Icons.Outlined.ContentCopy
                        "Delete" -> Icons.Outlined.Delete
                        "Effects" -> Icons.Outlined.AutoAwesome
                        "Mask" -> Icons.Outlined.Crop
                        "Chroma" -> Icons.Outlined.Tune
                        "Blend" -> Icons.Outlined.ContentCopy
                        else -> Icons.Outlined.TextFields
                    }
                    EditorToolbarIcon(action.label, fallback = icon,
                        modifier = Modifier.size(22.dp),
                        tint = if (action.destructive) VEColor.Error else Color.White)
                    Text(action.label, style = MaterialTheme.typography.labelSmall,
                        color = if (action.destructive) VEColor.Error else Color.White)
                }
            }
        }
    }
    }
}

private data class ActionChipUi(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

@Composable
private fun BottomToolbar(
    navigationEnabled: Boolean,
    speedEnabled: Boolean,
    visualEnabled: Boolean,
    selectedTool: String,
    onEdit: () -> Unit,
    onAudio: () -> Unit,
    onText: () -> Unit,
    onSpeed: () -> Unit,
    onFilters: () -> Unit,
    onAdjust: () -> Unit,
    onCanvas: () -> Unit,
    onOverlay: () -> Unit,
    onStickers: () -> Unit,
    onEffects: () -> Unit,
    transitionEnabled: Boolean,
    onTransition: () -> Unit,
) {

    val tools = listOf(
        "Edit" to onEdit,
        "Audio" to onAudio,
        "Text" to onText,
        "Effects" to onEffects,
        "Overlay" to onOverlay,
        "Stickers" to onStickers,
        "Filters" to onFilters,
        "Canvas" to onCanvas,
        "Adjust" to onAdjust,
        "Speed" to onSpeed,
        "Transition" to onTransition,
    )
    var toolbarViewport by remember { mutableStateOf(0) }
    val toolScroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(selectedTool, toolbarViewport, toolScroll.maxValue) {
        if (toolbarViewport == 0) return@LaunchedEffect
        val index = tools.indexOfFirst { it.first == selectedTool }.coerceAtLeast(0)
        val left = with(density) { (8 + index * 64).dp.roundToPx() }
        val right = left + with(density) { 64.dp.roundToPx() }
        val viewport = toolbarViewport
        val target = when {
            left < toolScroll.value -> left
            right > toolScroll.value + viewport -> right - viewport
            else -> toolScroll.value
        }
        toolScroll.animateScrollTo(target.coerceIn(0, toolScroll.maxValue))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("editor-tools").onSizeChanged { toolbarViewport = it.width }
            .background(VEColor.DarkPrimarySurface)
            .drawWithContent {
                drawContent()
                if (toolScroll.value < toolScroll.maxValue) drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, VEColor.DarkPrimarySurface), startX = size.width - 18.dp.toPx(), endX = size.width),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - 18.dp.toPx(), 0f),
                    size = androidx.compose.ui.geometry.Size(18.dp.toPx(), size.height))
            }
            .horizontalScroll(toolScroll)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tools.forEach { (label, onClick) ->
            val tint = if (label == selectedTool) VEColor.AccentCyan else
                if (((label in listOf("Filters", "Adjust") && !visualEnabled) || (label == "Speed" && !speedEnabled))) VEColor.DarkSecondaryText.copy(alpha = 0.45f) else VEColor.DarkSecondaryText
            val icon = when (label) {
                "Edit" -> Icons.Outlined.ContentCut
                "Audio" -> Icons.Outlined.MusicNote
                "Text" -> Icons.Outlined.TextFields
                "Filters" -> Icons.Outlined.Filter
                "Adjust" -> Icons.Outlined.Tune
                "Transition" -> Icons.Outlined.CompareArrows
                "Canvas" -> Icons.Outlined.Crop
                "Stickers" -> Icons.Outlined.StarOutline
                "Effects" -> Icons.Outlined.AutoAwesome
                "Overlay" -> Icons.Outlined.ContentCopy
                else -> Icons.Outlined.Speed
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .testTag("editor-tool-$label").semantics { selected = label == selectedTool }
                    .width(64.dp)

                    .background(if (label == selectedTool) VEColor.PrimaryBlue.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .height(EditorMetrics.Dock)
                    .padding(top = 4.dp)
                    .clickable(enabled = navigationEnabled && (label != "Transition" || transitionEnabled) && ((label !in listOf("Filters", "Adjust", "Speed") || (label == "Speed" && speedEnabled) || (label in listOf("Filters", "Adjust") && visualEnabled))), onClick = onClick),
            ) {
                EditorToolbarIcon(label, tint, icon, Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(
                    if (label == "Transition") "Transitions" else label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = tint,
                    textAlign = TextAlign.Center,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Identifies the overlay the text editor sheet is working on, whether it is a
 * brand-new draft, and the pre-edit snapshot used to restore on Cancel.
 */
private data class TextEditorState(
    val overlayId: String,
    val isNew: Boolean,
    val snapshot: com.videoeditor.core.model.TextOverlay?,
)

// Retain the Cancel snapshot as well as the live draft when Android recreates
// the activity for rotation or a viewport change. The project already owns
// the live overlay; restoring the sheet must not create another history edit.
private val TextEditorStateSaver = androidx.compose.runtime.saveable.Saver<TextEditorState?, ArrayList<Any>>(
    save = { state -> if (state == null) arrayListOf() else arrayListOf(
        state.overlayId, state.isNew, state.snapshot?.let {
            kotlinx.serialization.json.Json.encodeToString(com.videoeditor.core.model.TextOverlay.serializer(), it)
        } ?: "",
    ) },
    restore = { saved -> if (saved.isEmpty()) null else TextEditorState(
        saved[0] as String, saved[1] as Boolean, (saved[2] as String).takeIf { it.isNotEmpty() }?.let {
            kotlinx.serialization.json.Json.decodeFromString(com.videoeditor.core.model.TextOverlay.serializer(), it)
        },
    ) },
)

/**
 * A bottom-sheet text editor that edits the selected overlay live in the main
 * preview (the overlay renders through the composition as you type and adjust).
 * Done keeps the edits; Cancel restores the pre-edit snapshot or deletes a new
 * draft, so partial text is never committed. The input respects IME insets so
 * the field and confirmation controls stay reachable with the keyboard open.
 */
@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = VEColor.DarkSecondaryText,
        modifier = Modifier.padding(bottom = VESpacing.Xxs, top = VESpacing.Xs),
        fontWeight = FontWeight.SemiBold,
    )
}

private val TEXT_SWATCHES = listOf(
    0xFFFFFFFF.toInt(), // white
    0xFF000000.toInt(), // black
    0xFFFFD54F.toInt(), // amber
    0xFFFF5252.toInt(), // red
    0xFF4FC3F7.toInt(), // light blue
    0xFF69F0AE.toInt(), // green
)

@Composable
private fun SpeedDialog(
    videoProject: com.videoeditor.core.model.Project?,
    videoClipId: String?,
    sourceDuration: Long,
    current: Float,
    onDismiss: () -> Unit,
    onSelect: (Float, Boolean) -> Unit,
) {
    val options = listOf(0.5f, 1f, 1.5f, 2f)
    var draftSpeed by remember(current) { mutableStateOf(current) }
    var acceptShortening by remember(draftSpeed, videoProject) { mutableStateOf(false) }
    val proposal = remember(draftSpeed, videoProject, videoClipId) {
        if (videoProject != null && videoClipId != null) runCatching {
            com.videoeditor.editing.commands.SpeedChangePlanner.plan(videoProject, videoClipId, draftSpeed)
        } else null
    }
    val adjustment = proposal?.getOrNull()?.takeIf { it.needsTransitionAdjustment }
    val error = proposal?.exceptionOrNull()?.message
    EditorToolPanel("Playback Speed", onDismiss, { draftSpeed = current }, { onSelect(draftSpeed, acceptShortening) },
        applyEnabled = error == null && (adjustment == null || acceptShortening)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { speed ->
                EditorChoice(formatSpeed(speed), kotlin.math.abs(speed - draftSpeed) < .01f,
                    { draftSpeed = speed }, Modifier.weight(1f))
            }
        }
        EditorValueSlider("Speed", draftSpeed, .5f..2f, formatSpeed(draftSpeed), { draftSpeed = it })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        adjustment?.let { plan ->
            Text("Source handles require shorter transitions:", color = VEColor.DarkSecondaryText, style = MaterialTheme.typography.bodySmall)
            plan.shortened.forEach { (before, after) ->
                Text("${before.type.displayName}: ${"%.2f".format(before.durationMicros / 1_000_000f)} s → ${"%.2f".format(after.durationMicros / 1_000_000f)} s",
                    color = VEColor.AccentCyan, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(acceptShortening, { acceptShortening = it })
                Text("Apply speed and shorter transitions together", color = VEColor.DarkSecondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth().background(VEColor.DarkBackground, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("New duration", color = VEColor.DarkSecondaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatEditorTimecode((sourceDuration.toDouble() / draftSpeed).toLong()), color = VEColor.AccentCyan,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        }

    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VolumeDialog(
    current: Float,
    currentMuted: Boolean,
    audio: com.videoeditor.core.model.AudioClip?,
    peaks: com.videoeditor.core.media.waveform.WaveformPeaks?,
    sourceTitle: String,
    playing: Boolean,
    onPlayback: () -> Unit,
    onAudio: ((com.videoeditor.core.model.AudioClip) -> com.videoeditor.core.model.AudioClip) -> Unit,
    onDismiss: () -> Unit,
    onChange: (Float, Boolean) -> Unit,
) {
    var muted by remember { mutableStateOf(currentMuted) }
    var volume by remember { mutableStateOf(current) }
    var fadeIn by remember { mutableStateOf((audio?.fadeInMicros ?: 0L) / 1_000_000f) }
    var fadeOut by remember { mutableStateOf((audio?.fadeOutMicros ?: 0L) / 1_000_000f) }
    val peakScale = remember(peaks) { (peaks?.peakInRange(0, peaks.size) ?: 1f).coerceAtLeast(.000001f) }
    EditorToolPanel("Volume & Fade", onDismiss,
        { volume = current; muted = currentMuted; fadeIn = (audio?.fadeInMicros ?: 0L) / 1_000_000f; fadeOut = (audio?.fadeOutMicros ?: 0L) / 1_000_000f },
        {
            if (audio != null) onAudio { it.copy(volume = volume, muted = muted, fadeInMicros = (fadeIn * 1_000_000).toLong(), fadeOutMicros = (fadeOut * 1_000_000).toLong()) }
            else onChange(volume, muted)
            onDismiss()
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    EditorValueSlider("Volume", volume, 0f..2f, "${(volume * 100).toInt()}%", { volume = it }, Modifier.testTag("clip-volume-slider"))
                }
                if (audio != null) IconButton(onClick = onPlayback) {
                    EditorIcon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (playing) "Pause audio preview" else "Play audio preview", tint = VEColor.AccentCyan)
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                EditorIcon(Icons.Outlined.VolumeOff, null, tint = VEColor.DarkSecondaryText, modifier = Modifier.size(18.dp))
                Text("Mute clip", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                androidx.compose.material3.Switch(muted, { muted = it }, modifier = Modifier.semantics { contentDescription = "Mute clip" })
            }
            if (audio != null) {
                val maximum = (audio.timelineDurationMicros / 1_000_000f).coerceAtLeast(.01f)
                EditorValueSlider("Fade In", fadeIn.coerceIn(0f, maximum), 0f..maximum, "${"%.1f".format(fadeIn)} s", { fadeIn = it })
                EditorValueSlider("Fade Out", fadeOut.coerceIn(0f, maximum), 0f..maximum, "${"%.1f".format(fadeOut)} s", { fadeOut = it })
            } else Text("Select an audio clip for fade controls.", color = VEColor.DarkMutedText, style = MaterialTheme.typography.bodySmall)

    }
}

@Composable
private fun TransformDialog(
    current: com.videoeditor.core.model.VisualTransform,
    mode: com.videoeditor.core.model.ScaleMode,
    sourceThumbnail: android.graphics.Bitmap?,
    externalDraft: com.videoeditor.core.model.VisualTransform,
    onDraftUpdate: (com.videoeditor.core.model.VisualTransform, com.videoeditor.core.model.ScaleMode) -> Unit,
    onPreview: (com.videoeditor.core.model.VisualTransform, com.videoeditor.core.model.ScaleMode) -> Unit,
    onRestore: () -> Unit,
    onCommit: (com.videoeditor.core.model.VisualTransform, com.videoeditor.core.model.ScaleMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = externalDraft
    var canvasMode by remember { mutableStateOf(mode) }
    var rotationInput by remember { mutableStateOf(current.rotationDegrees.toString()) }
    LaunchedEffect(draft.rotationDegrees) { rotationInput = draft.rotationDegrees.toString() }
    val validRotation = rotationInput.toIntOrNull()?.let { it in -360..360 } == true
    DisposableEffect(Unit) { onDispose { onRestore() } }
    LaunchedEffect(draft, canvasMode) { androidx.compose.runtime.withFrameNanos { }; onPreview(draft, canvasMode) }
    val updateDraft: (com.videoeditor.core.model.VisualTransform) -> Unit = { onDraftUpdate(it, canvasMode) }
    fun rotateBy(degrees: Int) = updateDraft(draft.copy(
        rotationDegrees = Math.floorMod(draft.rotationDegrees + degrees + 180, 360) - 180))
    var section by remember { mutableStateOf("Rotate") }
    EditorToolPanel("Rotate & Transform", onDismiss,
        { onDraftUpdate(current, mode); canvasMode = mode; rotationInput = current.rotationDegrees.toString() },
        { onCommit(draft, canvasMode); onDismiss() }, applyEnabled = validRotation) {
        EditorSegments(listOf("Rotate", "Scale", "Position", "Crop"), section, { section = it })
        when(section) {
            "Rotate" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rotation", color = VEColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
                    Slider(draft.rotationDegrees.toFloat().coerceIn(-180f, 180f), { updateDraft(draft.copy(rotationDegrees = it.toInt())) }, valueRange = -180f..180f,
                        modifier = Modifier.weight(1f))
                    EditorNumberField(rotationInput, { value ->
                        rotationInput = value
                        value.toIntOrNull()?.takeIf { it in -360..360 }?.let { updateDraft(draft.copy(rotationDegrees = it)) }
                    }, Modifier.width(64.dp), selected = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorChoice("−90°", false, { rotateBy(-90) }, Modifier.weight(1f))
                    EditorChoice("+90°", false, { rotateBy(90) }, Modifier.weight(1f))
                    EditorChoice("Flip H", draft.flipHorizontal, { updateDraft(draft.copy(flipHorizontal = !draft.flipHorizontal)) }, Modifier.weight(1f))
                    EditorChoice("Flip V", draft.flipVertical, { updateDraft(draft.copy(flipVertical = !draft.flipVertical)) }, Modifier.weight(1f))
                }
                EditorValueSlider("Scale", draft.scale, .1f..4f, "${(draft.scale * 100).toInt()}%", { updateDraft(draft.copy(scale = it)) })
            }
            "Scale" -> {
                Text("Scale  ${"%.0f".format(draft.scale * 100)}%", color = Color.White)
                Slider(draft.scale, { updateDraft(draft.copy(scale = it)) }, valueRange = .1f..4f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.videoeditor.core.model.ScaleMode.entries.forEach { value ->
                        EditorChoice(value.name, canvasMode == value, { canvasMode = value; onDraftUpdate(draft, value) })
                    }
                }
            }
            "Position" -> {
                Text("Horizontal position  ${"%.2f".format(draft.offsetX)}", color = Color.White)
                Slider(draft.offsetX, { updateDraft(draft.copy(offsetX = it)) }, valueRange = -1f..1f)
                Text("Vertical position  ${"%.2f".format(draft.offsetY)}", color = Color.White)
                Slider(draft.offsetY, { updateDraft(draft.copy(offsetY = it)) }, valueRange = -1f..1f)
                EditorChoice("Center", false, { updateDraft(draft.copy(offsetX = 0f, offsetY = 0f)) })
            }
            else -> {
                val crop = draft.crop ?: com.videoeditor.core.model.CropRect()
                Text("Source crop", color = VEColor.DarkSecondaryText)
                sourceThumbnail?.let { SourceCropEditor(it, crop) { value -> updateDraft(draft.copy(crop = value)) } }
                for (edge in listOf("Left", "Top", "Right", "Bottom")) {
                    Text("Crop $edge", color = Color.White)
                    val value = when(edge) { "Left" -> crop.left; "Top" -> crop.top; "Right" -> crop.right; else -> crop.bottom }
                    val range = when(edge) { "Left" -> 0f..(crop.right - .01f); "Top" -> 0f..(crop.bottom - .01f); "Right" -> (crop.left + .01f)..1f; else -> (crop.top + .01f)..1f }
                    Slider(value, { v -> updateDraft(draft.copy(crop = when(edge) { "Left" -> crop.copy(left = v); "Top" -> crop.copy(top = v); "Right" -> crop.copy(right = v); else -> crop.copy(bottom = v) })) }, valueRange = range, modifier = Modifier.testTag("crop-${edge.lowercase()}"))
                }
            }
        }
    }
}

@Composable
private fun ColorGradeDialog(
    filter: com.videoeditor.core.model.FilterSettings,
    adjustments: com.videoeditor.core.model.ColorAdjustments,
    panel: String,
    previewSource: android.graphics.Bitmap?,
    onApply: (com.videoeditor.core.model.FilterSettings, com.videoeditor.core.model.ColorAdjustments) -> Unit,
    onDismiss: () -> Unit,
    onPreview: (com.videoeditor.core.model.FilterSettings, com.videoeditor.core.model.ColorAdjustments) -> Unit,
    onRestore: () -> Unit,
) {
    var f by remember { mutableStateOf(filter) }
    var a by remember { mutableStateOf(adjustments) }
    var section by remember { mutableStateOf("Basic") }
    DisposableEffect(Unit) { onDispose { onRestore() } }
    LaunchedEffect(f, a) { androidx.compose.runtime.withFrameNanos { }; onPreview(f, a) }
    EditorToolPanel(if (panel == "Filters") "Color Filters" else "Adjust Image", onDismiss,
        { f = filter; a = adjustments }, { onApply(f, a); onDismiss() }, rowAlignedBody = panel != "Filters") {
                if (panel == "Filters") {
                    FilterGallery(previewSource, f.filterId) { f = f.copy(filterId = it) }
                    EditorValueSlider("Intensity", f.intensity, 0f..1f, "${(f.intensity * 100).toInt()}%", { f = f.copy(intensity = it) }, Modifier.testTag("filter-intensity"))
                } else {
                    EditorSegments(listOf("Basic", "Color", "Advanced"), section, { section = it })
                    val values = listOf("Exposure" to a.exposure, "Brightness" to a.brightness, "Contrast" to a.contrast,
                        "Saturation" to a.saturation, "Temperature" to a.temperature, "Tint" to a.tint, "Hue" to a.hue,
                        "Highlights" to a.highlights, "Shadows" to a.shadows,
                        "Sharpness" to a.sharpen, "Vignette" to a.vignette)
                    values.filter { (label, _) -> when(section) {
                        "Color" -> label in listOf("Saturation", "Temperature", "Tint", "Hue")
                        "Advanced" -> label in listOf("Sharpness", "Vignette")
                        else -> label in listOf("Exposure", "Brightness", "Contrast", "Highlights", "Shadows")
                    } }.forEach { (label, value) ->
                        val symbol = when (label) {
                            "Brightness" -> Icons.Outlined.Brightness6; "Contrast" -> Icons.Outlined.Contrast
                            "Saturation" -> Icons.Outlined.InvertColors; "Temperature" -> Icons.Outlined.Thermostat
                            "Highlights" -> Icons.Outlined.LightMode; "Shadows" -> Icons.Outlined.DarkMode
                            "Sharpness" -> Icons.Outlined.Deblur; else -> Icons.Outlined.Vignette
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(66.dp))
                        Slider(modifier = Modifier.weight(1f).testTag("grade-${label.lowercase()}"), value = value, valueRange = if (label == "Hue") -180f..180f else if (label in listOf("Sharpness", "Vignette")) 0f..1f else -1f..1f,
                            onValueChange = { v -> a = when(label) {
                                "Exposure" -> a.copy(exposure = v); "Brightness" -> a.copy(brightness = v); "Contrast" -> a.copy(contrast = v)
                                "Saturation" -> a.copy(saturation = v); "Temperature" -> a.copy(temperature = v); "Tint" -> a.copy(tint = v); "Hue" -> a.copy(hue = v)
                                "Highlights" -> a.copy(highlights = v); "Shadows" -> a.copy(shadows = v)
                                "Sharpness" -> a.copy(sharpen = v); else -> a.copy(vignette = v)
                            } }, onValueChangeFinished = {})
                        Text("${if (value > 0f) "+" else ""}${(value * 100).toInt()}", color = VEColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(32.dp))
                        }
                    }
                }

    }
}

/** Secondary navigation shares transport space and remains available in one quiet menu. */
@Composable
private fun TimelineOptions(expanded: Boolean, onExpand: () -> Unit, onPip: () -> Unit,
    onFit: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onZoomOut: () -> Unit, onZoomIn: () -> Unit, onAddMedia: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            EditorIcon(Icons.Outlined.Tune, "Timeline options", tint = VEColor.DarkSecondaryText, modifier = Modifier.size(18.dp))
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            buildList {
                if (onAddMedia != null) add("Add media" to onAddMedia)
                add((if (expanded) "Compact timeline" else "Expand timeline") to onExpand)
                add("PiP" to onPip)
                add("Fit timeline" to onFit)
                add("Previous clip boundary" to onPrevious)
                add("Next clip boundary" to onNext)
                add("Zoom out timeline" to onZoomOut)
                add("Zoom in timeline" to onZoomIn)
            }.forEach { (label, action) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

/** Choose an actual adjacent video pair, then pin its IDs for the complete transition draft. */
private fun nearestVideoJoin(state: EditorUiState): Pair<com.videoeditor.core.model.Clip, com.videoeditor.core.model.Clip>? {
    val pairs = state.project?.timeline?.videoClips.orEmpty().zipWithNext().filter { (a, b) ->
        !a.isPhoto && !b.isPhoto && a.timelineEndMicros == b.timelineStartMicros
    }
    return pairs.filter { it.first.id == state.selection.videoClipId || it.second.id == state.selection.videoClipId }
        .minByOrNull { kotlin.math.abs(it.first.timelineEndMicros - state.playheadMicros) }
        ?: pairs.minByOrNull { kotlin.math.abs(it.first.timelineEndMicros - state.playheadMicros) }
}
