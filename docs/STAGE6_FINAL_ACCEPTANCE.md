# Stage 6 final acceptance

**STAGE 6 — ACCEPTED (API 36 EMULATOR SCOPE ONLY).** All current implemented technical gates pass, and the user-approved export performance benchmark stage6-export-v1 passes: 20/20 valid process-cold exports of the frozen four-second mixed-content fixture, medians 3414.560898 ms (720p, threshold 5000) and 3557.620126 ms (1080p, threshold 6000). The unrecoverable historical 4.75-second reference is superseded, never retrospectively qualified.

## 1. Retained engineering corrections

Generation-owned startup watchdog allows one synchronous release/rebuild at the latest target, preserving paused/Play intent. A second missing-output deadline reports terminal failure and releases resources. Deferred decoder-error release checks generation ownership. Stale callbacks cannot release a newer player.

Paused single-video dispatch uses the actual renderer flush barrier; composed pointer lift cancels superseded startup through release/rebuild. Exact pending microsecond scrub state publishes immediately. AAC preview yields after one PCM output per shared render turn, retains all samples/timestamps and stops paused decode-ahead only when ready. Video/image renderers run before audio. Supported AAC allocation may defer until explicit Play only for nonzero paused compositions with no transitions, one primary item and exclusively muted secondary visuals; otherwise all audio remains eager. Explicit Play restores actual PCM/clock readiness.

Bounded photo reuse verifies app-owned file bytes and retains a private ARGB8888 bitmap, providing independent graph copies because Media3 recycles consumed images. Epoch clearing prevents late cache repopulation. Active test fixtures/outputs moved to filesDir after observed installd cache eviction. Collector cleanup preserves output if Perfetto has already exited.

## 2. Reliability and failure evidence

Real-decoder/GL focused17/17 passes include bounded retry/cancellation/terminal release/stale errors, exact original main/PiP bytes, marker 9→missing3,933,341 us→118/RGB and explicit Play after paused landing, AAC-photo44.1/48kHz with single/multi-item recreation, and exact/odd/two-PiP dissolve boundaries. The fullmedia132 also passes exact committed production. Native Codec2 execution root cause remains unknown; injected observable missing-output tests do not claim deterministic reproduction of native failure.

All failed variants remain retained. The first full 240-sample campaign had240valid but photo-cold591.911083>570.714ms. D50 later passed full240 but failed exact CrossDissolve correctness and is superseded. Current1504 first campaign failed three-videos-alternating-017 (requestedframe22, retained49) during proven ENOSPC with 80 KB free; its truncated trace is retained. No application-cause conclusion is supported by that trace. Generated outputs/traces were host-archived and SHA-matched before exact duplicate removals; user sources/projects/database remain intact. Fresh complete campaign follows, never pooled.

## 3. Original seek budgets

One standalone fresh campaign,20 samples per cell,240/240 independently valid,0 invalid, driver failures, partial or missing records; 0 recoveries; all 12 cells PASS. Metric remains production request→matchingGL submission, including any recovery delay; GL submission is not display presentation. Process-cold means reopened/frame-zero-ready before seeking. Percentile method unchanged.

| Workload | Mode | Budget p95 ms | Measured p95 ms | p50 ms | Maximum ms | n | Verdict |
|---|---|---:|---:|---:|---:|---:|---|
| one-video | warm | 603.239 | 462.374 | 284.050 | 504.782 | 20 | PASS |
| one-video | process-cold | 598.739 | 493.866 | 426.904 | 500.089 | 20 | PASS |
| one-video | alternating | 519.381 | 289.414 | 232.089 | 290.641 | 20 | PASS |
| two-videos | warm | 1098.360 | 806.695 | 309.825 | 806.916 | 20 | PASS |
| two-videos | process-cold | 1006.814 | 798.580 | 723.354 | 839.003 | 20 | PASS |
| two-videos | alternating | 897.096 | 518.903 | 464.683 | 543.158 | 20 | PASS |
| three-videos | warm | 1460.879 | 1081.680 | 463.881 | 1130.966 | 20 | PASS |
| three-videos | process-cold | 1447.859 | 1117.755 | 1004.261 | 1132.261 | 20 | PASS |
| three-videos | alternating | 1164.546 | 751.873 | 604.009 | 759.224 | 20 | PASS |
| photo-overlay | warm | 613.557 | 488.770 | 337.479 | 531.205 | 20 | PASS |
| photo-overlay | process-cold | 570.714 | 505.833 | 472.708 | 531.998 | 20 | PASS |
| photo-overlay | alternating | 780.103 | 343.781 | 270.878 | 354.489 | 20 | PASS |

Photo-cold p95 record:  request → accepted decoded input442.952125ms, input → GL62.880667ms, total505.832792ms. Audio renderer overlap0.732044ms and SourceClock overlap0.077212ms (maximum0.004291ms). First full campaign p95 record had528.809583ms input/63.101500 ms GL tail, audio118.820959ms and SourceClock109.192327ms. These are different targets/processes, descriptive phase attribution rather than paired causal estimates; nested scopes are not additive. SQL/records/CSVs are retained.

## 4. Correctness gates

Host 342; full media 132; editor 52; app debug 20; opt-in release 20: all PASS, with zero failures, errors or skips. Focused 17 PASS followed only by JavaDoc edit; current full suites test committed production. Tooling 27 PASS. Ordinary nondebuggable R8 functional 26/26 PASS; continuous 4/4 independently verified, including EOS/recreation/replay/reopen/native cleanup and photo pause/scrub/explicitPlay probes.

Current full-suite captured PCM: 30 phase/rate records accounts for all bytes, maximumRMSE0.5 signed16 units against independent FFmpeg; no unmatched buffers. Two actual UI exports at720p/1080p pass source/video/PiP/caption identity and nativeAndroid/FFmpeg audio: exact independent graph sum, authored source windows, encoded controls and28 zero-displacement mixed signatures. Scope: 48 kHz mono AAC on the qualified emulator.

## 5. Source and artifact identity

Production commit 1504b2ea23aa58b70b6f79431b3905f189ad6c38. Test-only durable-fixture checkout 7109da53729be909cfa455c6d97ab6358a52b24e; benchmark collector checkout 1ee33768a3ed13620f38c5350b4946cd168967a0. Source manifest/final audit matches all 263 production/build files before/after qualification.

Ordinary R8 APK SHA-256:8103a1ed84bcc505ba0c265e15074b52f5ac9f3900c883143b8be519887760ef.
AAB SHA-256:b0437dc86a8e95a58a17d4ec9ef21f3273dbfe51201dcf1c4fb2708d23a2c72f.
Build, signature, installed nondebuggable status/hash and AAB ZIP/config/manifest/dex checks PASS. Existing cricpro_api36/emulator-5554/API 36 arm64 preserved without wipe/data clear. Local test signing only; opt-in release instrumentation is a distinct artifact.

## 6. Export contract resolution

Observed current four-second workflow Transformer.start→onCompleted:720p 3338.241877 ms; 1080p 3413.040460 ms. Callback→output finalized:1.640750/1.519750ms respectively. These observations do not qualify the historical 4.75-second contract.

Original approved fixture/timing definition could not be recovered and was never retrospectively qualified. By explicit project-owner decision on 2026-10-18 it is superseded by the approved benchmark stage6-export-v1 (docs/STAGE6_EXPORT_BENCHMARK_CONTRACT.md): 10 valid process-cold exports per resolution of the frozen four-second mixed-content fixture (project af806bee-fdd0-499f-beea-0cfa9ca2646b), Transformer.start→onCompleted timing, medians ≤ 5000 ms (720p) and ≤ 6000 ms (1080p), median = mean of 5th/6th sorted values with no rounding, on the qualified API 36 emulator with the ordinary R8 consumer APK.

Campaign stage6-approved-export-qualification-20261018-215500: 20/20 attempts VALID, zero failures, timeouts, exclusions or retries. 720p sorted samples 3351.545–3848.864 ms, median (3400.974294+3428.147501)/2 = 3414.560898 ms PASS. 1080p sorted samples 3469.518–3660.299 ms, median (3551.942793+3563.297460)/2 = 3557.620126 ms PASS. All 20 outputs passed the frozen integrity oracle (H.264/AAC streams, exact dimensions, 4.0 s, 120 video + 190 AAC frames, monotonic DTS, full decode, non-black frames). Final samples 19-720p/20-1080p additionally passed independent FFmpeg-decoded frame identity (source markers, CrossDissolve alpha, PiP, caption) and full consumer audio qualification against the separate audio-only graph control (exact graph sum, authored windows, byte-exact encoded control, native decode within 1 PCM unit, 28 zero-displacement transient matches). Two disclosed interruptions (a driver oracle tooling bug after sample 1 and the frozen 400 MB storage guard after sample 12) were resolved without changing any contract value; both are documented in the campaign evidence.

## 7. Remaining limitations

Native Codec2 execution root cause remains unknown; bounded recovery is proven only at the observable missing-output boundary. No physical-device, Play or production-distribution certification. Protected directories were not inspected/modified during this continuation; the initial discovery exception before the restriction was read is disclosed in EVIDENCE_INDEX.md. Existing premium UI/user data retained; evidence is outside Git.

## 8. Verdict and retained evidence

**STAGE 6 — ACCEPTED (API 36 EMULATOR SCOPE ONLY).** Current implemented engineering/correctness/seek/continuous/export correctness gates PASS, and the user-approved export performance benchmark stage6-export-v1 PASSes both medians. Acceptance is limited to the qualified API 36 emulator scope; no physical-device, Play or production-distribution certification is claimed, and no waiver or claim of VERIFIED PROFESSIONAL is made beyond that scope.

Evidence roots: /Users/PUBLIC_USER/video-editor-professional-evidence/stage6-production-correction-20261018-115404 (prior gates) and /Users/PUBLIC_USER/video-editor-professional-evidence/stage6-approved-export-qualification-20261018-215500 (approved export campaign: STAGE6_FINAL_DECISION.md, FINAL_TEST_SUMMARY.json, EXPORT_VIDEO_AUDIO_VERIFICATION.json, ARTIFACT_IDENTITIES.json, campaign/EXPORT_MEDIAN_CALCULATION.json). Canonical FINAL_TEST_SUMMARY.json, FINAL_PERFORMANCE_COMPARISON.json/.md, FINAL_IDENTITY_AUDIT.json, FINAL_SOURCE_MANIFEST.json, ARTIFACT_IDENTITIES.json, GRAPH_STALL_FIX.md/VERIFICATION.json, SEEK_PHASE_BEFORE_AFTER.json, EXPORT_CONTRACT_RESOLUTION.md and EVIDENCE_INDEX.md. Raw XML, traces, screenshots, source hashes, native PCM, export files and failed attempts are retained.
