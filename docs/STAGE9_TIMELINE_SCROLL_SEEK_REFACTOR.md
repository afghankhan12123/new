# Timeline scroll and seek stabilization

## Authoritative coordinates and gestures

`editing/.../TimelineViewport.kt` remains the sole conversion between integer project microseconds, scroll pixels, pixels/microsecond and the fixed playhead. Screen-to-time and zoom calculations now use Double intermediates; zoom clamps directly to duration × new scale, avoiding cancellation through viewport padding. A pinch captures one focal project time at gesture start and uses the current centroid for zoom AND two-finger translation, including at zoom limits. The live playhead is derived from that viewport. Lifting sends its final seek before playback-follow resumes; the old implementation restored the pre-pinch time and visibly undid focal placement.

`EditorViewModel.stopFling` retires the old fling synchronously. Its coroutine finalizer checks job identity before ending a gesture, so cancellation cannot end/refuse a newer drag. A fling stops at a scroll boundary and its velocity includes the final pointer sample. Existing trim/long-press reorder/free-move priorities remain. PiP and sticker rows now call the main timeline pointer machine in navigation mode: the same scrub, pinch, fling and cancellation code applies. A tap selects only within that item's interval; vertical movement can yield to row scrolling. Sticker lanes are vertically scrollable when more than two exist.

Primary/audio model duration and source mapping now use Double intermediates instead of first narrowing Long microseconds to Float. Persisted fields are unchanged; fractional-speed/frame identity still requires owner verification.

## Decode scheduling

Pointer motion updates `EditorUiState` immediately, independently of Media3. The controller coalesces seeks at display cadence. Actual frame completion is distinct from reset acknowledgment. Multi-input `endProjectSeek` acknowledges its queued flush barrier with target + generation checks, allowing obsolete in-period landings to be superseded without claiming that a picture arrived. Released-player callbacks and prior seek output remain generation-rejected. Deferred programmatic requests publish the newest requested position immediately. A paused scrub explicitly cancels playback intent.

Source-period boundaries are compiled/sorted once per edit from resolved half-open transition windows (including odd durations). Dispatch uses binary search, instead of allocating/scanning all clip/transition boundaries on every sample. The terminal source-sample metadata probe now runs on IO with bounded sample scanning, a bounded cache and source/range checks before endpoint refinement. It no longer opens/scans MediaExtractor on the UI thread.

## Assets and Compose

Visible thumbnail windows are quantized to half-viewport boundaries, giving repeat scrolling stable source-range cache keys. Scaled retriever output is requested on supported Android versions; the fallback still scales immediately. Empty strips count against cache size. Visible/overscan waveform references are bounded to sixteen; stale waveform completions are checked against the live document. Zoom signatures are built only on zoom-bucket changes. PiP/sticker lane clocks are read during Canvas drawing; document/selection presentation uses the clock-free screen flow. Main timeline keeps its existing Canvas, visible strip drawing and stable model identities; there are no Lazy-list keys to repair there. Accessibility volume-point insertion now reads the live playhead, not the shell's deliberately zeroed clock.

## Limits and verification

Cross-period multi-input seeks still retire/rebuild one bounded graph because the pinned Media3 child-period preparation contract remains asynchronous. Rotation, source crop, timing, source changes and canvas dimensions still need structural reconfiguration. This is an explicit remaining source of visible waits, not a claim of CapCut-equivalent latency. No new decode debounce or blocking decoder work was added to pointer handlers. Source tests cover focal math, interval frame selection and history; they were compiled, not executed. Sustained reversal, fling interruption, lane gestures, zoom and transition-boundary scrubbing require NE2211 verification.
