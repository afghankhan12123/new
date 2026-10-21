# AndroidJUnitRunner calls Trace from the app classloader. AGP excludes this
# shared dependency from the test APK, although the app otherwise inlines it.
# Keep only that runner dependency, only for the opt-in release test variant.
-keep class androidx.tracing.Trace { *; }
# Kotlin extension facades are referenced directly by AndroidX test libraries,
# but many are entirely inlined out of a production application.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
# Public APIs invoked across the independently compiled test APK must remain
# callable: R8's mapping cannot represent inlining or virtual-to-static rewrites.
# Other feature UI implementations and Media3 still undergo normal R8
# optimization; the directly embedded export/theme composables are kept below.
-keep,allowobfuscation class androidx.compose.** { *; }
-keep,allowobfuscation class androidx.room.** { *; }
-keep,allowobfuscation class androidx.lifecycle.** { *; }
-keep,allowobfuscation class androidx.activity.** { *; }
-keep,allowobfuscation class androidx.savedstate.** { *; }
-keep class androidx.core.view.** { *; }
-keep class com.videoeditor.core.common.result.** { *; }
-keep class com.videoeditor.core.model.** { *; }
-keep class com.videoeditor.core.database.** { *; }
-keep class com.videoeditor.core.media.MediaMetadataExtractor { *; }
-keep class com.videoeditor.core.media.composition.CompositionBuilder { *; }
-keep class com.videoeditor.core.media.export.** { *; }
-keep class com.videoeditor.core.media.audio.** { *; }
-keep class com.videoeditor.editing.** { *; }
-keep class com.videoeditor.feature.export.ExportViewModel { *; }
-keep class com.videoeditor.feature.export.ExportPhase** { *; }
-keep interface com.videoeditor.app.AcceptancePreferences { *; }

-keep class com.videoeditor.core.datastore.** { *; }
-keep class com.videoeditor.core.designsystem.theme.** { *; }
-keep class com.videoeditor.core.designsystem.color.** { *; }
-keep class com.videoeditor.feature.export.ExportScreenKt { *; }
-keep class com.videoeditor.feature.export.ExportUiState { *; }
-keep class dagger.hilt.android.EntryPointAccessors { *; }
-keep class dagger.hilt.EntryPoints { *; }

# Test-only timeline Canvas diagnostic key; no production keep rule.
-keep class com.videoeditor.feature.editor.timeline.TimelineDiagnostics { *; }
