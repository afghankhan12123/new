package com.videoeditor.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking

/** Explicit host-controlled fixture lifecycle, separate from the acceptance test runner. */
class BenchmarkFixtureRunner : androidx.test.runner.AndroidJUnitRunner() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
    }
    override fun onStart() {
        if (!arguments.containsKey("operation")) { super.onStart(); return }
        val result = Bundle()
        val marker = File(targetContext.filesDir, "owned-benchmark-project.txt")
        val directory = File(targetContext.filesDir, "owned-benchmark-media")
        val db = Room.databaseBuilder(targetContext, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        try {
            val repository = ProjectRepository(db.projectDao(), ProjectDocumentStorage(targetContext))
            runBlocking {
                when (arguments.getString("operation")) {
                    "stage6-aac-seed" -> {
                        val tag="${System.currentTimeMillis()}-${System.nanoTime()}"
                        val folder=File(targetContext.filesDir,"owned-stage6-aac-$tag").apply { check(mkdirs()) }
                        val refs=listOf("transition_identity_0.mp4","transition_identity_1.mp4","music_20s.m4a",
                            "audio_sample_identity/48000.m4a").mapIndexed { index,name ->
                            val file=File(folder,"source-$index-${File(name).name}")
                            context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
                            checkNotNull(MediaMetadataExtractor().extract(targetContext,Uri.fromFile(file)).getOrNull())
                        }
                        val base=ProjectCreator().create("Stage 6 audio workflow $tag",refs.take(2))
                        val project=base.copy(mediaRefs=refs,timeline=com.videoeditor.core.model.Timeline(
                            videoClips=listOf(com.videoeditor.core.model.Clip("a",refs[0].id,1_000_000,3_000_000),
                                com.videoeditor.core.model.Clip("b",refs[1].id,1_000_000,3_000_000,2_000_000)),
                            audioClips=listOf(com.videoeditor.core.model.AudioClip("music",refs[2].id,1_000_000,5_000_000,volume=.1f),
                                com.videoeditor.core.model.AudioClip("voiceover",refs[3].id,2_000_000,6_000_000,volume=.5f)),
                            overlayClips=listOf(com.videoeditor.core.model.OverlayClip("right-pip",refs[1].id,0,4_000_000,0,4_000_000,
                                muted=true,transform=com.videoeditor.core.model.VisualTransform(scale=.2f,offsetX=.7f,offsetY=.5f))),
                            textOverlays=listOf(com.videoeditor.core.model.TextOverlay("caption","TOP",startMicros=0,
                                durationMicros=4_000_000,positionX=.85f,positionY=.8f,fontSizeSp=80f,colorArgb=android.graphics.Color.YELLOW))))
                        check(repository.save(project).isSuccess)
                        val audit=File(targetContext.getExternalFilesDir(null),"stage6-aac-seed/$tag").apply { check(mkdirs()) }
                        File(audit,"project.json").writeText(kotlinx.serialization.json.Json.encodeToString(
                            com.videoeditor.core.model.Project.serializer(),project))
                        result.putString("projectId",project.id);result.putString("projectName",project.name)
                        result.putString("ownedFolder",folder.absolutePath);result.putString("auditFolder",audit.absolutePath)
                    }
                    "stage5-frame-seed" -> {
                        val tag = "${System.currentTimeMillis()}-${System.nanoTime()}"
                        val folder = File(targetContext.filesDir, "owned-stage5-performance-$tag").apply { check(mkdirs()) }
                        val sourceProjectId = checkNotNull(arguments.getString("sourceProjectId")) {
                            "Specify the owned project containing the marked main fixture"
                        }
                        val original = checkNotNull(repository.loadDocument(sourceProjectId).getOrNull())
                        val originalRef = checkNotNull(original.mediaRef(original.timeline.videoClips.first().mediaRefId))
                        val mainFile = File(folder, "marked-main.mp4")
                        checkNotNull(targetContext.contentResolver.openInputStream(Uri.parse(originalRef.uri))).use { input ->
                            mainFile.outputStream().use(input::copyTo)
                        }
                        val overlayFile = File(folder, "moving-overlay.mp4")
                        context.assets.open("pip_overlay_frame_identity_audio.mp4").use { input -> overlayFile.outputStream().use(input::copyTo) }
                        val photoFile = File(folder, "cyan-photo.png")
                        android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
                            bitmap.eraseColor(android.graphics.Color.CYAN)
                            photoFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                        val extractor = MediaMetadataExtractor()
                        val refs = listOf(mainFile, overlayFile, photoFile).map { file ->
                            checkNotNull(extractor.extract(targetContext, Uri.fromFile(file)).getOrNull())
                        }
                        val kinds = listOf("one-video", "two-videos", "three-videos", "photo-overlay")
                        val projects = kinds.map { kind ->
                            val base = ProjectCreator().create("Recovery perf $tag $kind", listOf(refs[0]))
                            val overlays = when(kind) {
                                "one-video" -> emptyList()
                                "photo-overlay" -> listOf(com.videoeditor.core.model.OverlayClip(
                                    "photo-$tag", refs[2].id, isPhoto = true, timelineDurationMicros = 6_000_000,
                                    transform = com.videoeditor.core.model.VisualTransform(scale = .3f, offsetX = .5f, offsetY = .5f)))
                                else -> (0 until if(kind == "two-videos") 1 else 2).map { index ->
                                    com.videoeditor.core.model.OverlayClip("video-$tag-$index", refs[1].id,
                                        timelineDurationMicros = 6_000_000, sourceEndMicros = 6_000_000, muted = true, zIndex = index,
                                        transform = com.videoeditor.core.model.VisualTransform(scale = .3f,
                                            offsetX = if(index == 0) .5f else -.5f, offsetY = .5f))
                                }
                            }
                            base.copy(mediaRefs = refs, timeline = base.timeline.copy(overlayClips = overlays)).also {
                                check(repository.save(it).isSuccess)
                                File(folder, "$kind-project.json").writeText(kotlinx.serialization.json.Json.encodeToString(com.videoeditor.core.model.Project.serializer(), it))
                            }
                        }
                        File(folder, "manifest.txt").writeText(projects.joinToString("\n") { "${it.id}\t${it.name}" })
                        result.putString("projectManifest", projects.joinToString("\n") { "${it.id}\t${it.name}" })
                        result.putString("ownedFolder", folder.absolutePath)
                    }
                    "pip-profile-seed" -> {
                        val ids = File(targetContext.filesDir, "owned-pip-profile-projects.txt")
                        if (ids.exists()) {
                            result.putString("projectIds", ids.readText())
                        } else {
                            val folder = File(targetContext.filesDir, "owned-pip-profile-media").apply { mkdirs() }
                            val refs = listOf("pip_main_moving_audio.mp4", "pip_overlay_moving_audio.mp4").map { name ->
                                val file = File(folder, name)
                                context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
                                checkNotNull(MediaMetadataExtractor().extract(targetContext, Uri.fromFile(file)).getOrNull())
                            }
                            val projects = (1..3).map { count ->
                                val base = ProjectCreator().create("PiP profile $count visual layers", listOf(refs[0]))
                                base.copy(mediaRefs = refs, timeline = base.timeline.copy(overlayClips = (0 until count - 1).map { index ->
                                    com.videoeditor.core.model.OverlayClip("profile-$index", refs[1].id,
                                        timelineStartMicros = 1_000_000, timelineDurationMicros = 3_000_000, sourceEndMicros = 3_000_000,
                                        zIndex = index, muted = index > 0,
                                        transform = com.videoeditor.core.model.VisualTransform(scale = .3f, offsetX = if (index == 0) .5f else -.5f))
                                }))
                            }
                            projects.forEach { check(repository.save(it).isSuccess) }
                            ids.writeText(projects.joinToString("\n") { it.id })
                            result.putString("projectIds", ids.readText())
                        }
                    }
                    "premium-seed" -> {
                        val ids = File(targetContext.filesDir, "owned-premium-projects.txt")
                        check(!ids.exists()) { "Clean up owned premium fixtures first" }
                        val folder = File(targetContext.filesDir, "owned-premium-media").apply { mkdirs() }
                        val extractor = MediaMetadataExtractor()
                        val refs = (1..50).map { index ->
                            val file = File(folder, "waterfall-$index.mp4")
                            context.assets.open("waterfall.mp4").use { input -> file.outputStream().use(input::copyTo) }
                            checkNotNull(extractor.extract(targetContext, Uri.fromFile(file)).getOrNull())
                        }
                        val retriever = android.media.MediaMetadataRetriever()
                        val photo = File(folder, "waterfall-photo.jpg")
                        try {
                            retriever.setDataSource(File(folder, "waterfall-1.mp4").path)
                            val bitmap = checkNotNull(retriever.getFrameAtTime(1_000_000))
                            photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                            bitmap.recycle()
                        } finally { retriever.release() }
                        val photoRef = checkNotNull(extractor.extract(targetContext, Uri.fromFile(photo)).getOrNull())
                        val music = File(folder, "music.m4a")
                        context.assets.open("music_20s.m4a").use { input -> music.outputStream().use(input::copyTo) }
                        val audio = checkNotNull(extractor.extract(targetContext, Uri.fromFile(music)).getOrNull())
                        val projects = (listOf(3, 10, 30, 50).map { count ->
                            ProjectCreator().create("Premium $count sources", refs.take(count))
                        } + ProjectCreator().create("Premium photo timeline", List(3) { photoRef }) +
                            ProjectCreator().create("Premium text and audio", refs.take(3)).let { base ->
                                base.copy(mediaRefs = base.mediaRefs + audio, timeline = base.timeline.copy(
                                    textOverlays = listOf(com.videoeditor.core.model.TextOverlay("premium-caption", "Waterfall memories",
                                        durationMicros = base.timeline.renderedDurationMicros, hasShadow = true)),
                                    audioClips = listOf(com.videoeditor.core.model.AudioClip("premium-audio", audio.id,
                                        sourceEndMicros = base.timeline.renderedDurationMicros))))
                            })
                        ids.writeText(projects.joinToString("\n") { it.id })
                        projects.forEach { check(repository.save(it).isSuccess) }
                        result.putString("projects", projects.joinToString { it.name })
                    }
                    "premium-cleanup" -> {
                        val ids = File(targetContext.filesDir, "owned-premium-projects.txt")
                        if (ids.exists()) ids.readLines().forEach { repository.deletePermanently(it) }
                        File(targetContext.filesDir, "owned-premium-media").deleteRecursively()
                        ids.delete()
                    }
                    "seed" -> {
                        check(!marker.exists()) { "Clean up the previous owned fixture first" }
                        directory.mkdirs()
                        val refs = (1..3).map { index ->
                            val file = File(directory, "waterfall-$index.mp4")
                            context.assets.open("waterfall.mp4").use { source -> file.outputStream().use(source::copyTo) }
                            checkNotNull(MediaMetadataExtractor().extract(targetContext, Uri.fromFile(file)).getOrNull())
                        }
                        val project = ProjectCreator().create("Release benchmark waterfall", refs)
                        check(repository.save(project).isSuccess)
                        marker.writeText(project.id)
                        result.putString("projectId", project.id)
                    }
                    "cleanup" -> {
                        if (marker.exists()) {
                            val id = marker.readText().trim()
                            repository.deletePermanently(id)
                            File(targetContext.filesDir, "exports").listFiles()?.filter {
                                it.name.startsWith("export_${id}_") || it.name == "pending_$id.txt"
                            }?.forEach(File::delete)
                        }
                        directory.deleteRecursively()
                        marker.delete()
                    }
                    "extend" -> {
                        check(marker.exists()) { "Seed an owned fixture first" }
                        val project = checkNotNull(ProjectDocumentStorage(targetContext).load(marker.readText().trim()).getOrNull())
                        var cursor = 0L
                        val clips = (1..20).flatMap {
                            project.timeline.videoClips.take(3).map { clip ->
                                clip.copy(id = java.util.UUID.randomUUID().toString(), timelineStartMicros = cursor)
                                    .also { cursor += it.timelineDurationMicros }
                            }
                        }
                        check(repository.save(project.copy(timeline = project.timeline.copy(videoClips = clips))).isSuccess)
                        result.putString("projectId", project.id)
                    }
                    else -> error("Use operation=seed, extend or cleanup")
                }
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally { db.close() }
    }
}
