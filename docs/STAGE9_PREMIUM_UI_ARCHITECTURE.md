# Premium editor UI architecture

The Android production editor remains connected to the existing immutable `Project`, command history, Room/document persistence and canonical `ProjectRenderPlan`. This change replaces workspace presentation and corrects concrete preview/export integration defects; it does not replace Stage 8 DSP or the saved-project format.

## Workspace

`EditorMetrics` owns common touch targets, panel spacing, header/dock/transport dimensions and window-relative preview allocation. `EditorWorkspace` owns the graphite/cyan palette, editor typography and reusable tool host. `EditorScreen` measures after IME insets and allocates four regions: compact project/history/output header; fitted live SurfaceView and canvas gestures; transport plus bounded multitrack timeline; one main/contextual dock or an active tool host. The landscape tool host receives an explicit share of its pane.

The global dock no longer persists under every active tool. Selection produces one horizontally scrollable contextual dock with a fixed return-to-main action. Panel cancel/reset/expand/confirm controls stay in the header while the content scrolls. Dense controls remain reachable without forcing their form height onto the preview. Standard Compose motion follows platform animation scaling; the video surface is not animated for every playback sample.

## Ownership

- **Project:** existing immutable model and `EditHistory`; no duplicate saved edit model.
- **Selection:** `TimelineSelection`; PiP identity remains derived from this selection.
- **Tool:** one `EditorToolState.active` route; legacy Boolean-style delegates address the same enum, not independent panel owners.
- **Draft:** text transaction in `EditorViewModel`; effect/filter/canvas/mask/chroma/blend drafts retain their existing explicit commit/rollback paths. Sticker replacement stores its target ID independently of new insertion.
- **Playback:** one `CompositionPreviewController`, owned surface and session render publication. Submitted frames now carry seek-generation ownership.
- **Export:** existing independent engine sessions, actual profile/progress/container validation.

`TextEditorPanel`, `FilterGallery` and `EffectThumbnailRenderer` are production components. The effect gallery uses the actual production GL shader in a separate offscreen context; filters use the shared affine preset matrices. Catalog cards are not a second implementation of effect values. Existing masks, 13 blends, 13 transitions, creative presets, audio processing and keyframes remain wired to their existing renderable models.

## Compatibility

Captions add optional `bold` and `italic` fields at the end of the constructor, both defaulting to false. Version-1 documents without them retain normal type. Shared caption shaping/rasterization and the bounds-cache key include the type style. No database migration, source-media rewrite, URI revocation or project reset is introduced.
