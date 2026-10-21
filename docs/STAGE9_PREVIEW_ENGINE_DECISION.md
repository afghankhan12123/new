# Preview engine decision — 21 October 2026

**Implement Option A: persistent Media3 1.5.1 composition preview with corrected seek admission.** Keep the canonical project model, two transition lanes, Stage 8 audio and independent Transformer export sessions.

| Option | Benefit | Evidence / costs | Decision |
| --- | --- | --- | --- |
| A: repair existing preview | Reuse players, GL contexts, surfaces, shaders and codec reuse across periods | Source contains an unconditional full graph rebuild on every multi-input source-window crossing, premature reset acknowledgment and mismatched primary sink lifecycle. These can be corrected locally. | Implemented. |
| B: dedicated scrubbing renderer | Own frame-request scheduling, optional low-resolution source cache/prefetch | Must reproduce clipping, speed, transitions, effects, masks, text, PiP, color/rotation, source EOS and precise settle. A bare retriever frame is not a correct transition preview. Additional codec/GPU ownership and sync need qualification. | Defer until Option A is assessed on NE2211. |
| C: larger commercial/native migration | Could provide a mature editing execution engine | No runtime evidence proves our dependencies incapable. Requires commercial license, model migration, Stage 8 parity, shader port, device qualification, binary-size/maintenance work and export requalification. | Not justified for this delivery. |

## Media3 compatibility audit

Official [1.8.0 release notes](https://developer.android.com/jetpack/androidx/releases/media3#1.8.0) introduce ExoPlayer scrubbing mode, dynamic scheduling, higher codec operating rate and decode-only input flag usage. [Current parameters](https://developer.android.com/reference/androidx/media3/exoplayer/ScrubbingModeParameters) also describe optional flush/keyframe-reset avoidance and fractional tolerances. Current APIs must not be assumed to exist in 1.5.1. The linked Android blog failed to fetch; primary release notes corroborate the feature.

The local `buildSrc/.../Media3CompositorPatch.kt` transform specifically removes classes from **1.5.1** effect, exoplayer and transformer AARs. Local replacements include DefaultVideoCompositor, ClippingMediaPeriod, ExoAssetLoaderVideoRenderer and SequenceAssetLoader; preview also relies on package-private renderer/video-sink and composition internals. An arbitrary version bump could stop stripping duplicate classes or produce source/binary incompatibility. 1.8 changes muxer packaging, sequence gap/audio APIs and GL processing contracts relevant to these replacements. All Media3 artifacts must migrate together, with patched class/API diffing and preview/export/device/audio qualification. Merely compiling ExoPlayer against a new version is insufficient. No upgrade or AAR change was made.

[SeekParameters](https://developer.android.com/reference/androidx/media3/exoplayer/SeekParameters) distinguishes precise and sync-tolerant seeking. This build retains exact composed seeking for intermediate and final targets. Independent closest-sync seeks on child lanes are not enabled, because they can disagree about transition time. Newer skip-flush behavior cannot be copied independently of our texture lease and input-reset protocol.

[MediaCodec decode-only](https://developer.android.com/reference/android/media/MediaCodec#BUFFER_FLAG_DECODE_ONLY) is API 34+: dependencies decode without an output callback. Our renderer attaches source-window/PTS metadata to input and retires it on output, including period-end logic. Enabling that flag without reconciling suppressed callbacks risks stranded metadata/EOS. Operating-rate changes also need thermal/resource tests. Neither is blindly enabled. Existing transition dynamic scheduling, MediaCodec reuse/fallback and Stage 8 source-clock audio remain.

## Memory and performance limits

Reuse one composition graph, with the existing bounded maximum of eight visual inputs (primary + two transition lanes + up to five PiP layers). This does not guarantee a device supports eight hardware decoders: images/pads differ and fallback/resource behavior needs phone testing. No decoder per primary clip, third adjacent warm decoder, CPU full-frame cache or background predecode service was added.

Existing texture pools remain bounded (two pre-compositor outputs per input, one compositor output), and bitmap caching remains bounded. A 1080p RGBA picture alone is about 7.9 MiB; 120 pictures approach 949 MiB before textures/codec buffers. A future small cache should store owned low-resolution frames/textures, include model revision/source time in its keys and invalidate on edits. It cannot retain borrowed codec textures or count stale content as a fresh frame.

No measured latency, CapCut equivalence, maximum codec count or smoothness result is claimed. The selected repair removes source-proven work and lifecycle errors; the owner verifies actual performance.
