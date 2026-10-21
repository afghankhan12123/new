# Source guide for maintainers and coding agents

All 14 Android modules are declared in `settings.gradle.kts`. `buildSrc` supplies
convention plugins; `gradle/libs.versions.toml` pins dependency versions.

| Area | Entry points (repository-relative) |
| --- | --- |
| App/navigation | `app/src/main/java/com/videoeditor/app/VideoEditorApp.kt`, `MainActivity.kt`, `navigation/VideoEditorNavHost.kt` |
| Editor state/UI | `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorViewModel.kt`, `EditorScreen.kt`, `EditorWorkspace.kt`, `timeline/TimelineView.kt` |
| Timeline/undo | `editing/src/main/java/com/videoeditor/editing/commands/`, `history/EditHistory.kt`, `timeline/TimelineViewport.kt` |
| Project/render model | `core/model/src/main/java/com/videoeditor/core/model/`, especially `render/ProjectRenderPlan.kt`, `VisualTimeMapping.kt`, `SecondaryFrameSelector.kt` |
| Preview/seek | `core/media/src/main/java/com/videoeditor/core/media/preview/CompositionPreviewController.kt`, `SeekDispatchGate.kt`, `SeekResetPosition.kt`, `PreviewPeriodTime.kt`, `GraphOutputDeadline.kt`, `PlaybackOutputWatchdog.kt` |
| Media3 overrides | `core/media/src/main/java/androidx/media3/`, `buildSrc/src/main/kotlin/Media3CompositorPatch.kt` |
| Composition/transitions | `core/media/src/main/java/com/videoeditor/core/media/composition/CompositionBuilder.kt`, `OverlayCompositorSettings.kt`, `androidx/media3/effect/Stage9CompositorProgram.java` |
| GPU/effects/masks/chroma | `core/media/src/main/java/com/videoeditor/core/media/composition/*GlEffect.kt`, `androidx/media3/effect/Stage9CompositorProgram.java`; shader strings are embedded in source |
| Audio | `core/media/src/main/java/com/videoeditor/core/media/audio/`, Media3 audio graph/sink overrides |
| Text/stickers | `core/media/src/main/java/com/videoeditor/core/media/composition/TextOverlayEffect.kt`, `StickerOverlayEffect.kt`, `stickers/StickerCatalog.kt` |
| Media import/cache | `core/media/src/main/java/com/videoeditor/core/media/DurableMediaImport.kt`, `timeline/TimelineMediaRepository.kt`, metadata/thumbnail/waveform helpers |
| Export | `core/media/src/main/java/com/videoeditor/core/media/export/ExportEngine.kt`, `feature/export/` |
| Persistence | `core/database/`, `core/datastore/`, project serialization in `core/model/` |
| Tests | Each module's `src/test/` and `src/androidTest/`; required bundled synthetic/licensed media remains included |

Read the preview lifecycle, seek architecture, transition time model and tool
source audit before changing ownership or time mapping. Preview and export share
model/render-plan contracts but own separate mutable sessions and GL resources.
Never use a retained surface image or player position as proof of newly rendered
output. Media3's pinned overrides and its AAR transform must remain consistent.

Stage 9 changes exist beyond the historical local checkpoint `a33d8da7`.
Historical handoffs and external evidence hashes describe their own builds.
Refer to `PUBLICATION_AUDIT.md` for verification performed on this snapshot.
