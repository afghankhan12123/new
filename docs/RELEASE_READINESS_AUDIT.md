# First-release correctness audit — 2026-10-09

**Release status: blocked. This is an implementation and verification audit, not a production-readiness certification. GPU filters and adjustments remain deferred.**

## Repository and environment

Initial state independently verified: branch `main`, commit `b121d5870edd21b2f79b3efe6dccaf2872c92ff9`, clean working tree. Native modules, Media3 1.5.1, non-destructive project documents and saved version-1 schema are retained. Implementation commits: `97988e8`, `710bd3e`, `6438331`, `f484352`, `6a24119`; test robustness commit `355a9d8`. The source working tree was clean at `6a24119` after those commits; audit/evidence files were then added in a separate documentation commit.

Testing uses the existing `cricpro_api36` Android 16/API 36 arm64 emulator. SurfaceFlinger reports Apple M4 Max/Metal host graphics, not SwiftShader. Only `emulator-5554` is attached; physical-device testing is unavailable. Production signing is not configured; the R8 artifact is unsigned until locally signed for emulator checks.

## Implemented corrections

- Visual clips determine the rendered canvas end. A six-second video with twenty-second music renders six seconds. Audio-only projects retain the audio end, including offsets. An explicit photo end card extends the visual track. Captions and longer music do not implicitly extend it.
- Audio render windows are derived copies; source trims and saved music are untouched. Clips starting at/after the canvas end are excluded from rendering. Audio speed and source in-points participate in the derived source out-point. Existing documents require no migration or destructive rewrite.
- Portrait exports request portrait presentation dimensions. Actual displayed dimensions account for MP4 rotation metadata; coded width/height alone may be landscape.
- A decoded-output regression exposed incorrect caption timestamps after clip boundaries and speed edits. Effect inputs carry sequence offsets, not zero-based clip-local times. Caption gating now happens before timestamp adjustment and maps local source progress through playback speed. Styled text is cached per effect rather than allocated every frame, and text size scales with the output canvas's short edge.
- Save to Files uses `ActivityResultContracts.CreateDocument("video/mp4")` and copies into the selected content URI on IO. Cancellation leaves the finished render available. Save messages follow stream closure, not picker opening. Destination failures leave the persistent render available to retry.
- Exports now live in `filesDir/exports`, with a matching FileProvider path, instead of eviction-prone cache storage. MediaStore failures clean up incomplete rows; publication is checked before gallery success. Save operations guard against overlapping taps.
- Ignored FPS choices are removed. Export and settings show automatic source timing. Completed-export duration, displayed dimensions and available container FPS are read from the produced MP4; unavailable metadata is identified explicitly. Remembered legacy FPS data is preserved, but no longer presented as an effective encoder selection.
- Project opening checks source access on IO. Missing sources block composition preparation and present an explicit Relink File picker. Replacement type and source duration must cover the original trims. Persistable SAF access is required. Relink is reversible; undo/redo recheck access. Timeline edits, effects, clip IDs and source placement survive the replacement, and undo restores the original reference.
- Synchronous Transformer startup errors produce failures rather than leaving the progress screen stuck. Incomplete output is removed on failed/cancelled export.
- Home shortcut cards, white plus badge, project borders and header settings action are closer to the supplied visual reference. Editor tool icons are restored within the existing design system.

## Verification and evidence

Audited application implementation revision: `6a24119`; final test-selector revision: `355a9d8` on `main` (preceded by `97988e8`, `710bd3e`, `6438331`, `f484352`). Debug and R8 release builds passed. Host tests: 124 per variant, zero failures. Core media: 26 device tests, zero failures; the final four-output caption matrix rerun also passed. Editor: 39 device tests, zero failures. App: the final direct-instrumentation suite passed all four tests (40.967 seconds). An earlier four-test suite had three passing tests and an editing-acceptance crash in the emulator vendor EGL/gralloc driver; that failure is retained. Subsequent idle and recorded acceptance reruns also passed. Counts are taken from JUnit XML, with raw output retained in `docs/evidence/release-audit`. Host variants execute the same logical tests and must not be summed as distinct coverage.

The first device pass executed 24 core-media and 37 editor tests with zero failures. A first app acceptance attempt timed out waiting for playback; its idle rerun passed the editing acceptance path. A new destination test initially failed because the fixture was read from the target APK rather than the instrumentation APK; the setup was corrected. The caption matrix first failed with zero caption changes at 2.5 seconds on the third clip, establishing a genuine rendering defect. A later portrait assertion failed because it ignored the rotation flag; that test assumption was corrected rather than changing codec output to satisfy it. Original failures are retained.

The expanded caption matrix exports both caption-free and captioned edits at 720p/1080p, portrait/landscape. It decodes frames across reordered, split, trimmed and 2× clips with multiline Arabic/CJK text, opacity, scale, rotation and normalized placement. Pixel differences establish caption presence/absence at sampled times. They **do not establish pixel-perfect preview/export parity, exact glyph shaping, arbitrary font coverage, or exhaustive transform parity**. Endpoint samples use nearest decoded frames and are bounded by frame cadence.

App acceptance exercises real pointer gestures, save/reopen, text editing, audio volume editing, codec export, gallery saving and return to decoded preview. Destination tests read actual bytes through MediaStore/FileProvider content URIs, cancellation and unavailable-provider paths. A writable provider copy test is distinct from navigating Android's SAF picker. Missing-media coverage includes an actually moved file, timeline preservation and reversible relinking. Real permission-revocation and deleted-document provider workflows need separate validation.

## Performance

Historical controlled results are preserved in `docs/MILESTONE_2_5.md`: baseline median jank 11.86%, refined median 13.00%, refined histogram p95 23.5 ms, decoded-buffer seek about 232 ms. The historical jank regression is not reclassified as an improvement.

The first new decoded probe ran alongside host builds; it is explicitly retained as uncontrolled evidence. An idle run used the existing ImageReader/coarse source-identity observer rather than optimistic position-state updates. Its six seek samples had median 873.99 ms (545.01–1453.24 ms), materially worse than the historical decoded-buffer result. This is an environment/session result, not an isolated attribution to the caption change. A new paused 13-second project comparison used signed minified checkpoint/current APKs, no concurrent build or recording, and three sets of twelve identical 500px/200ms ruler swipes each. Baseline jank: 5.49%, 1.20%, 3.03%; current: 3.03%, 3.03%, 1.20%. Both medians are 3.03%; median histogram p95 is 17 ms in both. The first baseline run has a 27 ms p95. This small sequential comparison does not establish improvement or replace the historical controlled results. Raw gfxinfo is retained in `evidence/release-audit/performance`. A later idle decoded probe measured median 933.48 ms (423.51–1169.47 ms); matched repeated probes are detailed below. No quality reduction or editing-correctness tradeoff has been introduced.

## Remaining release gates and UI issues

- Repeatable frame pacing and decoded-output latency sign-off remains open. Emulator variability and historical/current regressions require investigation on physical hardware.
- Shorter/offset audio remains explicitly omitted from CompositionPlayer preview because Media3 1.5.1 does not support the required gaps; export retains it. This prevents complete audio-preview parity.
- Exhaustive decoded preview/export text comparisons, endpoint timestamps, RTL glyph order and transform parity remain open; a shared effect class is not proof of parity.
- Storage exhaustion, hostile/provider cancellation during copying, actual revoked URI grants, moved/deleted external documents and lifecycle interruptions require a broader provider/device matrix.
- Save files persist across ordinary app exit; process-death recovery of an in-progress export and a durable export-history interface are not implemented.
- Visual targets contain screens for deferred filters/adjustments and capabilities not implemented in this checkpoint. The native timeline still lacks the reference thumbnail strips and waveform detail, and the import screen uses the Android picker rather than the reference custom grid. Text tools and spacing also differ. Those tools remain visibly disabled. Home shortcut actions and several settings rows were already placeholders; a visual match does not make them functional. No full screenshot-exact match is claimed.
- Natural camera-motion footage, full accessibility coverage, smaller/font-scaled layouts, manufacturer codecs/GPU behavior, thermal/battery behavior and sustained physical-device editing remain to be validated.

## Next milestone

Close these first-release correctness, provider and performance gates before adding GPU filters or adjustments. Preserve the existing tests and media engine; do not begin AI functionality.

## Export compatibility

| Setting | Implemented behavior | Limits |
| --- | --- | --- |
| Container | MP4 | No alternate containers |
| Video | H.264/AVC, encoder fallback disabled | Device encoder support is required |
| Audio | AAC, requested 192 kb/s | Actual muxed track must be inspected per output |
| 720p | 1280×720 landscape / 720×1280 portrait display | Rotation metadata can swap coded dimensions |
| 1080p | 1920×1080 landscape / 1080×1920 portrait display | Same rotation consideration |
| Standard | Requested 5 Mb/s at 720p; 10 Mb/s at 1080p | Target bitrate is not a guaranteed measured average |
| High | Requested 8 Mb/s at 720p; 16 Mb/s at 1080p | Device-specific validation remains necessary |
| Frame rate | Source timestamps and speed edits; still images generate at 30 FPS | No user-selectable conversion; output may have variable cadence |
| Gallery | MediaStore pending row, byte copy, then publication | External provider/storage failures remain possible |
| Files | SAF CreateDocument followed by byte copy | Cancellation is separate from successful saving |
| Share | Read-granted FileProvider content URI | Receiving-app behavior is device-specific |

The new optional `MediaRef.originalUri` defaults to null for legacy documents. Explicit relinks retain the first original location in saved data; source edits and project schema version are preserved. Serialization regressions cover opening pre-field JSON and reopening provenance-bearing references.

## Fresh visual and destination evidence

Current-build portrait and landscape screenshots are in `evidence/release-audit/screens`. A minified release workflow imports a 15-second excerpt (85–100 seconds) of the user-supplied `/Users/PUBLIC_USER/Movies/CapCut/0826.mp4`; the original is unchanged. This is an app screen recording with visible screen changes, not natural camera footage. Android Photo Picker import, preview, scrub, 720p export, actual DocumentsUI Save to Files, completed-copy confirmation, gallery save and Android share sheet were exercised. Completion reports 15 seconds, 1280×720 display, 30 FPS container metadata. Evidence is fresh; no old screenshots serve as current proof. The vendor graphics crash during orientation/surface recreation is retained as an unresolved reliability gate.

The actual SAF-saved MP4 was pulled independently and fully decoded with FFmpeg without errors. Video duration is 15.000000 seconds and AAC duration 15.000091 seconds, with 1280×720, average 30/1 FPS. The Files copy remains accessible after force-stopping and relaunching the app. This confirms muxed track alignment, not perceptual lip-sync. Gallery publication and share-sheet launch were observed; no message was sent to a recipient.

Profiling separates model dispatch from observed output: the first matched current probe has median model edit 0.0399 ms, composition construction 0.0976 ms, initialization 2.965 ms, and structural-edit decoded output 362.005 ms. Input dispatch median is 0.1285 ms. These timings locate most delay beyond document commands/construction, in player rebuilding and decoded rendering delivery; they do not isolate a particular GPU driver queue. Cached caption styling avoids per-frame text allocation, but this caption-free benchmark cannot quantify that optimization.

Matched idle decoded probes were repeated three times per revision with six seeks and fifteen structural apply/undo/redo operations per run. Median of seek medians: checkpoint 230.952 ms, current 225.406 ms. Median of structural-edit medians: checkpoint 353.693 ms, current 358.638 ms. This small session comparison supports approximate seek parity; it does not establish physical-device performance or exact sought-frame arrival. The observer samples 36 decoded pixels against source identity. Earlier slow session results remain retained and must not be discarded. All six probes passed; raw observations and sample arrays are in `evidence/release-audit/performance`.

A subsequent recording attempt exposed an acceptance-selector ambiguity when another project was named “New Project.” The test now targets the first matching creation control. This is a test robustness correction; the initial failure remains in the evidence.

The final corrected editing acceptance run also passed with screen recording enabled (one test), producing `release-editor-acceptance.mp4`: home, import entry, timeline touch edits, caption and audio editing, portrait/landscape, export settings/progress/completion, gallery save and decoded return to editor. The separate user-footage recording includes the actual Android Photo Picker, DocumentsUI save and share sheet.

## Final verification state

Source and test revisions are committed through `355a9d8` on `main`; documentation and evidence form the following audit commit. The final direct app suite is four tests, zero failures. Host/device test totals are 124 logical host tests (run in both variants) plus 69 primary device tests (26 media, 39 editor, 4 app); targeted reruns and six benchmark probes are additional executions, not new logical tests. No physical Android device was attached. `git diff --check` passed. The final commit/tree status is reported with this audit; these remaining gates prevent a production-readiness claim.
