> Historical checkpoint: the subsequent 114-second recording exposed preview

> Current checkpoint (October 13, 2026): the integrated PiP source/report
> supersedes historical incomplete-PiP and omitted-audio entries below. See
> [integrated verification](MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md),
> [current feature inventory](COMPETITIVE_NON_AI_FEATURE_MATRIX.md) and
> [Android test-target correction](ANDROID_EMULATOR_COMPATIBILITY_FIX.md).
> This checkpoint changes test targeting/harnesses, not creative rendering.
> Prior performance measurements remain historical; physical-device gates remain open.

> failures that the passing workflow did not assert. Current findings and
> validation are documented in [the post-release audit](POST_RELEASE_AUDIT_FIXES.md).

# First-release feature verification matrix

Resumed checkpoint: c452a4b plus export-size/Home polish and explicit IME regression. This matrix records final emulator verification; it is not production certification. All edits use the integer-microsecond project document. Source media is preserved. All 28 UI states have fresh indexed captures; remaining coverage limits are in the release report.

| Feature | Actual UI location | Implementation | Preview / export | Persistence and history | Evidence / current status |
|---|---|---|---|---|---|
| Trim, split, reorder | Selected clip and timeline handles/long press | editing TimelineCommands, EditorViewModel, TimelineView | Shared source windows and sequence order | Project document; reversible commands | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Duplicate/delete | Contextual tools | TimelineCommands and AssetCommands | Rebuilds shared composition | Reversible; original media retained | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Crop/rotate/flip | Crop sheet, Rotate tool | VisualTransform, CompositionBuilder | Crop + ScaleAndRotate effects in both paths | Serialized transform; undo/redo | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Fit/fill/reposition | Canvas and Crop sheets | CanvasSettings, CanvasPlacementEffect | Presentation and matrix placement | Reversible canvas/transform commands | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Canvas ratio | Header/creation handoff | CanvasRatioSheet, ProjectCreator | Real canvas dimensions/orientation | Serialized; undo/redo | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Speed | Speed tool | SonicAudioProcessor, TimestampAdjustment, DurationAudioProcessor | 0.5/1/1.5/2; pitch preserved; silent sources independent of audio | Clip speed persisted; undo/redo | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Clip volume/mute | Audio sheet/contextual controls | VolumeAudioProcessor | Actual PCM gain/remove source audio | Commands persisted/undoable | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Photos | Import; Duration/Crop/Rotate tools | ImageThumbnails, photo duration command, Media3 image loader | Explicit frame duration, shared transformations | Reversible duration/order/transforms | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Clean cuts and fades | Clip Fade controls | VideoFadeEffect, FadeAudioProcessor | Fade through black, no timeline extension | Clip fade fields; reversible | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Text content/font/style | Text bottom sheet | TextOverlayEffect, StaticLayout bitmap overlay | Native multilingual shaping, font family, size, color, background, alignment, opacity, outline/shadow | Full caption model; undo/redo | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Text move/resize/rotate | Selected caption on canvas | EditorScreen canvas gesture handlers | Same normalized caption transform | One reversible command per gesture | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Text time/animations | Text controls/timeline | TextOverlayEffect | Caption gates across trim/split/speed; fade/slide/scale | Serialized timing/animation | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Audio import/trim/split/move | Audio menu/timeline | AssetCommands, CompositionBuilder | Shared source windows and separate mixed sequences | Serialized audio items; undo/redo | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Audio volume/mute/fades | Waveform Audio sheet | VolumeAudioProcessor, FadeAudioProcessor | PCM effects; clip-local envelope after seek | Reversible commands | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Short/offset audio | Timeline placement | Real WAV preview padding; export synthetic gaps | One CompositionPlayer clock; leading/trailing silence | Original window/speed/offset preserved | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Extraction | Audio → Extract Audio | EditorViewModel | Referenced audio source; original muted, no destructive conversion | Undo/redo and save/reopen | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Voiceover | Audio → Record Voiceover | MediaRecorder UI lifecycle and MediaRef import | Real AAC capture/review and mixed export | Recorded file + normal reversible audio clip | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Filters | Filters sheet | FilterPresets, ColorGradeEffect | None/Vivid/Warm/Cool/B&W/Film/Vintage; actual source previews and strength | Serialized settings; undo/redo | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Adjustments | Adjust sheet | Shared GPU ColorGradeEffect | Brightness/contrast/saturation/temperature/highlights/shadows/sharpness/vignette | Serialized values; undo/redo | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Home handoffs/camera | Four Home shortcuts | MainScreen/NavHost, CaptureVideo FileProvider integration | Music/Text/Ratio creation handoffs; native camera result imported | Normal project creation | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Search/favorites/trash/restore | Projects filters/search/options | Room DAO, ProjectRepository | Live project list and literal Unicode query | Room index + atomic documents | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Rename/duplicate/details | Project options sheet | ProjectRepository, ProjectOptionsSheet | Actual source cover and metadata | Saved documents/index consistent | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Themes/preferences | Settings | DataStore UserPreferencesRepository | System/Dark/Light; real default resolution/autosave/gallery behavior | DataStore across restart | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Cache/help/about | Settings | SettingsScreen/cache cleanup | Clears generated caches, preserves source edits; help/info dialogs | Preferences unaffected | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| MP4 export | Export settings/progress/completion | ExportEngine, DefaultMuxer, ExportViewModel | H.264/AAC, 720/1080 short-edge sizing, real quality bitrates and model duration | Saved project; output file | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Gallery/Files/share/play | Completion actions | MediaStore/SAF/FileProvider | Commits bytes before success; external native viewer/share | Retains rendered file on destination cancellation | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Interrupted export | Export failed/retry | Atomic pending marker | Removes owned partial output; project remains saved | Journal survives process death | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Storage failure | Export failure/retry | ExportStorageBudget | Honest preflight capacity error, no codec start | Project remains saved | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Missing media/relink | Editor Missing Media dialog | Metadata/access validation, RelinkMedia command | Blocks inaccessible source; rechecks reversible relink | Preserves full edit; undo restores old source | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Manual save/recovery | Close → Unsaved Changes | EditorViewModel save mutex/dirty state, Android AtomicFile | Save/Discard/Cancel real decisions | Atomic document recovery; autosave respects preference | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Responsive lanes/insets | Editor/Home/all sheets | TimelineGeometry, scrollable sheets, adaptive Compose layout | Overlap lanes retain selectable size; native Android safe areas | Layout-only | Host 268, media 45, editor 40 and app 13 all pass on API36; scope follows preview/export columns |
| Performance/resources | Preview and viewport assets | CompositionPreviewController, bounded strip/waveform caches | Scrub dispatch coalesced; player ownership and surface replacement | No viewport rebuilds | Six matched release runs/build + quiet decoded probe pass; distinct-source sustained use/hardware remain open |

## Resumed exact-source results

Fresh forced host tests: 268/268; media instrumentation: 45/45; editor: 40/40; app: 13/13; compact full workflow: 1/1.
JUnit evidence is external at `/Users/PUBLIC_USER/video-editor-release-evidence/20261011-resumed/`.
The release report records exact metadata, native destinations, installed minified
smoke verification and continuously recorded acceptance. All feature rows are
implemented and functionally tested on the emulator; preview/export checks apply
to media operations, while project/settings rows have UI/persistence checks. Physical-device
certification remains incomplete for every feature. The 3/10/30/50-clip editor
test checks bounded assets, scrub dispatch, autosave and release using three
repeated sources; it is not 50-distinct-source sustained-use certification.


## October 13, 2026 recovery checkpoint

PiP remains incomplete. The interrupted audio factory now reuses Media3 1.5.1's
sequence-aware renderer. Current host tests pass (302 executions); the overlay
device suite passes 10/11, with preview still failing. All visual sequences
currently share compositor input 0; multi-input routing and clean release are
open. No premium PiP UI, new professional milestones, runtime R8 certification
or updated performance/visual-parity claim is implied. See
[the recovery audit](STOPPED_SESSION_RECOVERY_AUDIT.md).

## Stage 5 continuation status

Extract Audio now groups audio addition and original mute into one history entry.
A device test reproduced the failure before the fix and passes afterward; host
tests cover repeated extraction, already-muted state, existing audio and deletion.
See [fresh evidence and outstanding acceptance](STAGE5_PREMIUM_UX_PERFORMANCE.md).
The new editor device test also verifies extraction save/reopen after trim/speed,
repetition and source deletion/Undo. The UI/performance audit and extraction-specific
decoded PCM export gate remain open. The fresh media suite has an unresolved
photo-overlay frame-parity failure (81/82 pass; isolated rerun also fails).
Cross-dissolve is **not implemented**; see [Stage 6 status](STAGE6_CROSS_DISSOLVE_IMPLEMENTATION.md).
