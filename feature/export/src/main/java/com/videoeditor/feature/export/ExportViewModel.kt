package com.videoeditor.feature.export

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videoeditor.core.common.di.IoDispatcher
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.common.result.onFailure
import com.videoeditor.core.common.result.onSuccess
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportProgress
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.media.export.ExportStage
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportPhase {
    Configuring,
    Exporting,
    Completed,
    Failed,
}

data class ExportUiState(
    val project: Project? = null,
    val phase: ExportPhase = ExportPhase.Configuring,
    val resolution: ExportResolution = ExportResolution.RES_1080P,
    val frameRate: FrameRate = FrameRate.FPS_30,
    val quality: ExportQuality = ExportQuality.Standard,
    /** Highest detected source frame rate across the project's clips; null while detection runs. */
    val sourceFrameRate: Int? = null,
    /** True when the selected frame rate changes the output cadence. */
    val frameRateApplied: Boolean = false,
    /** True once the user has changed a setting on this screen; a late preference
     * restore must never overwrite a selection the user already made. */
    val settingsTouched: Boolean = false,
    val progress: Float = 0f,
    val stage: ExportStage = ExportStage.Preparing,
    val outputFile: File? = null,
    val outputDurationMicros: Long? = null,
    val outputDimensions: String? = null,
    val outputFrameRate: String? = null,
    val errorMessage: String? = null,
    /** True once the file has actually been copied into the gallery/Photos. */
    val savedToGallery: Boolean = false,
    val isSaving: Boolean = false,
    /** A user-presentable note about the last save/share attempt. */
    val saveMessage: String? = null,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val exportEngine: ExportEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val preferencesRepository: com.videoeditor.core.datastore.UserPreferencesRepository? = null,
    private val storageBudget: com.videoeditor.core.media.export.ExportStorageBudget = com.videoeditor.core.media.export.ExportStorageBudget(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null
    private var queuedExportJob: Job? = null
    private val preferenceWrites = kotlinx.coroutines.sync.Mutex()

    fun loadProject(projectId: String) {
        if (_uiState.value.project?.id == projectId) return
        viewModelScope.launch {
            val doc = withContext(ioDispatcher) {
                projectRepository.loadDocument(projectId).getOrNull()?.let { com.videoeditor.core.media.MediaMetadataExtractor().resolveSpeedAudio(context, it) }
            }
            val prefs = preferencesRepository?.preferences?.let { it.first() }
            val interrupted = withContext(ioDispatcher) {
                val marker = pendingExport(projectId)
                if (!marker.exists()) false else {
                    val name = marker.readLines().firstOrNull()
                    // Journal contains only an app-created filename, never a
                    // provider URI or user media path.
                    if (name != null && name == File(name).name && name.startsWith("export_${projectId}_")) {
                        File(marker.parentFile, name).delete()
                    }
                    marker.delete()
                    true
                }
            }
            val restored = doc?.exportPreferences
            val touched = _uiState.value.settingsTouched
            val initialFrameRate = restored?.frameRate ?: FrameRate.FPS_30
            _uiState.value = _uiState.value.copy(project = doc,
                phase = if (interrupted) ExportPhase.Failed else ExportPhase.Configuring,
                errorMessage = if (interrupted) "The previous export was interrupted. Your project is saved. Try exporting again." else null,
                // A selection the user already made wins over the restore.
                resolution = if (touched) _uiState.value.resolution else restored?.resolution
                    ?: prefs?.defaultResolution?.let { runCatching { ExportResolution.valueOf(it) }.getOrNull() }
                    ?: ExportResolution.RES_1080P,
                frameRate = if (touched) _uiState.value.frameRate else initialFrameRate,
                quality = if (touched) _uiState.value.quality else restored?.quality ?: ExportQuality.Standard,
            )
            // Frame-rate detection reads every video source's container metadata,
            // which is slow for picker URIs. Run it after the document is published
            // so the screen is interactive immediately; the frame-rate section
            // shows the full option set until the source cadence is known.
            if (doc != null) {
                viewModelScope.launch {
                    val sourceFps = withContext(ioDispatcher) { detectSourceFrameRate(doc) }
                    _uiState.value.let { current ->
                        val validated = if (current.settingsTouched) current.frameRate
                            .takeIf { it in supportedFrameRates(sourceFps) } ?: FrameRate.FPS_30
                        else current.frameRate.takeIf { it in supportedFrameRates(sourceFps) } ?: FrameRate.FPS_30
                        if (current.project?.id != doc.id) return@launch
                        if (current.phase != ExportPhase.Configuring) {
                            _uiState.value = current.copy(sourceFrameRate = sourceFps)
                            return@launch
                        }
                        _uiState.value = current.copy(
                            sourceFrameRate = sourceFps,
                            frameRate = validated,
                            frameRateApplied = appliesFrameRate(validated, sourceFps),
                        )
                    }
                }
            }
        }
    }

    fun setResolution(r: ExportResolution) {
        _uiState.value = _uiState.value.copy(resolution = r, settingsTouched = true)
        persistPreferences()
    }

    fun setQuality(q: ExportQuality) {
        _uiState.value = _uiState.value.copy(quality = q, settingsTouched = true)
        persistPreferences()
    }

    fun setFrameRate(f: FrameRate) {
        val state = _uiState.value
        if (f !in supportedFrameRates(state.sourceFrameRate)) return
        _uiState.value = state.copy(frameRate = f, settingsTouched = true,
            frameRateApplied = appliesFrameRate(f, state.sourceFrameRate))
        persistPreferences()
    }

    /** Frame rates the current project can actually export at. A source cadence
     * can be preserved (no conversion) or dropped to a lower standard rate;
     * rates above the source are not offered because they cannot add real
     * motion detail. */
    fun supportedFrameRates(sourceFps: Int?): List<FrameRate> =
        com.videoeditor.core.model.ExportProfile.supportedFrameRates(sourceFps)

    private fun appliesFrameRate(selected: FrameRate, sourceFps: Int?): Boolean =
        sourceFps != null && selected.fps != sourceFps && selected.fps <= sourceFps

    /** Persists the current selection as the project's export preferences so a
     * reopened project restores them. Retries and the accepted Stage 6 default
     * both flow through this same validated state. */
    private fun persistPreferences() {
        val state = _uiState.value
        val project = state.project ?: return
        val prefs = com.videoeditor.core.model.ExportPreferences(
            resolution = state.resolution, frameRate = state.frameRate, quality = state.quality)
        if (project.exportPreferences == prefs) return
        _uiState.value = state.copy(project = project.copy(exportPreferences = prefs))
        viewModelScope.launch {
            preferenceWrites.lock()
            try {
                // Read after admission: an older IO write must not land after a newer selection.
                val latest = _uiState.value.project ?: return@launch
                val result = withContext(ioDispatcher) {
                    projectRepository.save(latest.copy(updatedAtMillis = System.currentTimeMillis()))
                }
                if (result.isFailure) _uiState.value = _uiState.value.copy(saveMessage = "Export settings could not be saved. Try again.")
            } finally { preferenceWrites.unlock() }
        }
    }

    /** Reads each clip's encoded frame rate and returns the highest, or null
     * when no clip reports one. Runs off the UI thread. */
    private fun detectSourceFrameRate(project: Project): Int? {
        val retriever = android.media.MediaMetadataRetriever()
        var best: Int? = null
        try {
            project.timeline.videoClips.filter { !it.isPhoto }.mapNotNull { clip ->
                project.mediaRef(clip.mediaRefId)
            }.forEach { ref ->
                runCatching {
                    retriever.setDataSource(context, android.net.Uri.parse(ref.uri))
                    val capture = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    val video = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()
                    val durationUs = ref.durationMicros?.toFloat()
                    val fps = capture ?: if (video != null && durationUs != null && durationUs > 0)
                        video / (durationUs / 1_000_000f) else null
                    fps?.let { if (best == null || it > best!!) best = it.toInt() }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        return best
    }

    fun changeSettings() {
        if (exportJob?.isActive != true) _uiState.value = _uiState.value.copy(phase = ExportPhase.Configuring, errorMessage = null)
    }

    /** Estimated output size in bytes, from realistic bitrate assumptions. */
    fun estimatedSizeBytes(): Long {
        val state = _uiState.value
        val project = state.project ?: return 0L
        // Picture-and-audio length only: a caption must not inflate the estimate.
        // Shares the encoder's bitrate ladder via ExportProfile, including
        // frame-rate scaling and ~1.5% MP4 container overhead. An estimate,
        // never a guarantee.
        return com.videoeditor.core.model.ExportProfile.estimatedSizeBytes(
            project.timeline.renderedDurationMicros, state.resolution, state.quality, state.frameRate)
    }

    fun startExport() {
        if (exportJob?.isActive == true) return
        if (_uiState.value.project == null) {
            // The document is still loading (cold process, picker URIs). Queue
            // the action rather than dropping it: run once the project arrives,
            // with the settings the user has selected by then.
            _uiState.value = _uiState.value.copy(phase = ExportPhase.Exporting, progress = 0f)
            if (queuedExportJob?.isActive == true) return
            queuedExportJob = viewModelScope.launch {
                val loaded = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    uiState.first { it.project != null }
                }
                if (loaded != null) startExport()
                else _uiState.value = _uiState.value.copy(phase = ExportPhase.Failed,
                    errorMessage = "The project could not be loaded. Go back and try again.")
            }
            return
        }
        val project = _uiState.value.project ?: return
        val state = _uiState.value
        val outputDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outputFile = File(outputDir, "export_${project.id}_${System.currentTimeMillis()}.mp4")

        val request = ExportRequest(
            projectId = project.id,
            outputFile = outputFile,
            resolution = state.resolution,
            frameRate = state.frameRate,
            quality = state.quality,
        )

        _uiState.value = state.copy(phase = ExportPhase.Exporting, progress = 0f, outputFile = null,
            savedToGallery = false, saveMessage = null, errorMessage = null)

        exportJob = viewModelScope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    storageBudget.requireCapacity(outputDir, estimatedSizeBytes())
                    val marker = android.util.AtomicFile(pendingExport(project.id))
                    val stream = marker.startWrite()
                    try { stream.write(outputFile.name.toByteArray()); marker.finishWrite(stream) }
                    catch (error: Throwable) { marker.failWrite(stream); throw error }
                }
                exportEngine.export(project, request) { progress: ExportProgress ->
                _uiState.value = _uiState.value.copy(
                    progress = progress.fraction,
                    stage = progress.stage,
                )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.videoeditor.core.common.result.Result.Failure(com.videoeditor.core.media.MediaError.ExportFailed(error.message ?: "Export failed", error))
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + ioDispatcher) { pendingExport(project.id).delete() }
            }
            result
                .onSuccess { file ->
                    val metadata = withContext(ioDispatcher) { readOutputMetadata(file) }
                    _uiState.value = _uiState.value.copy(
                        phase = ExportPhase.Completed,
                        outputFile = file,
                        outputDurationMicros = metadata.first,
                        outputDimensions = metadata.second,
                        outputFrameRate = metadata.third,
                        progress = 1f,
                    )
                    viewModelScope.launch { projectRepository.markExportFailed(project.id, false) }
                    val saveAutomatically = preferencesRepository?.preferences?.first()?.saveToPhotos ?: false
                    if (saveAutomatically) saveToGallery()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        phase = ExportPhase.Failed,
                        errorMessage = error.message,
                    )
                    viewModelScope.launch { projectRepository.markExportFailed(project.id, true) }
                }
        }
    }

    private fun pendingExport(projectId: String): File = File(context.filesDir, "exports/pending_$projectId.txt")

    private fun readOutputMetadata(file: File): Triple<Long?, String?, String?> {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val duration = formats.filter { it.containsKey(android.media.MediaFormat.KEY_DURATION) }
                .maxOfOrNull { it.getLong(android.media.MediaFormat.KEY_DURATION) }
            val dimensions = video?.let {
                val width = it.getInteger(android.media.MediaFormat.KEY_WIDTH)
                val height = it.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                val rotation = if (it.containsKey(android.media.MediaFormat.KEY_ROTATION)) it.getInteger(android.media.MediaFormat.KEY_ROTATION) else 0
                if (rotation % 180 == 0) "$width × $height" else "$height × $width"
            }
            val fps = video?.takeIf { it.containsKey(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)?.let { "$it FPS" }
            Triple(duration, dimensions, fps)
        } catch (_: Exception) { Triple(null, null, null) }
        finally { extractor.release() }
    }

    fun cancelExport() {
        queuedExportJob?.cancel(); queuedExportJob = null
        exportJob?.cancel()
        _uiState.value = _uiState.value.copy(phase = ExportPhase.Configuring, progress = 0f)
    }

    /**
     * Copies the finished export into the device gallery via MediaStore (scoped
     * storage — no broad permission needed). Only flips [ExportUiState.savedToGallery]
     * when the insert actually succeeds, so the completion wording never claims a
     * save that didn't happen.
     */
    fun galleryPermissionDenied() {
        _uiState.value = _uiState.value.copy(saveMessage = "Photos permission was not granted. You can still save to Files or share.")
    }

    fun saveToGallery() {
        if (_uiState.value.isSaving || _uiState.value.savedToGallery) return
        val file = _uiState.value.outputFile ?: return
        _uiState.value = _uiState.value.copy(isSaving = true, saveMessage = "Saving to Photos…")
        viewModelScope.launch {
            val ok = withContext(ioDispatcher) { insertIntoMediaStore(file) }
            _uiState.value = _uiState.value.copy(
                savedToGallery = ok,
                isSaving = false,
                saveMessage = if (ok) "Saved to Photos" else "Could not save to Photos",
            )
        }
    }

    /** SAF cancellation leaves the rendered export available for another destination. */
    fun saveToFiles(uri: android.net.Uri?) {
        if (uri == null || _uiState.value.isSaving) return
        val file = _uiState.value.outputFile ?: return
        _uiState.value = _uiState.value.copy(isSaving = true, saveMessage = "Saving to Files…")
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    require(uri.scheme == "content") { "Invalid destination" }
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Destination cannot be opened")
                    stream.use { out -> file.inputStream().use { it.copyTo(out) }; out.flush() }
                }
            }
            _uiState.value = _uiState.value.copy(isSaving = false, saveMessage =
                if (result.isSuccess) "Saved to Files" else "Could not save to Files: ${result.exceptionOrNull()?.message}")
        }
    }

    /** Returns a shareable content URI for the export, or null if unavailable. */
    fun shareUri(): android.net.Uri? {
        val file = _uiState.value.outputFile ?: return null
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrNull()
    }

    private fun insertIntoMediaStore(file: File): Boolean = runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_MOVIES,
                )
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        try {
            val stream = resolver.openOutputStream(uri) ?: error("Gallery destination unavailable")
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            values.clear()
            values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) { "Gallery publication failed" }
        }
        true
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }.getOrDefault(false)
}
