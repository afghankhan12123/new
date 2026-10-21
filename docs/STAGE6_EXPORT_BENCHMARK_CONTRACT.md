# Stage 6 export performance benchmark contract — `stage6-export-v1`

**Status: APPROVED** by explicit project-owner decision on 2026-10-18 (Option B of
`stage6-fable-continuation-20261018-211047/STAGE6_EXPORT_CONTRACT_DECISION.md`).

This is a user-approved contract created on 2026-10-18. It is not a historical requirement recovered from
Git. The historical 4.75-second export reference remains an unsourced figure from a 2026-10-17 working
document; it was never qualified, is not retrospectively qualified, and is superseded by this contract.

## Approved values (fixed)

| Field | Value |
|---|---|
| 720p threshold | Median ≤ 5,000 ms (inclusive) |
| 1080p threshold | Median ≤ 6,000 ms (inclusive) |
| Valid samples | 10 per resolution, 20 total |
| Timing start | Actual `Transformer.start()` invocation |
| Timing end | Actual `Transformer.Listener.onCompleted()` callback |
| Clock | `SystemClock.elapsedRealtimeNanos()` (existing passive `ExportTiming` instrumentation) |
| Statistic | Median of 10 sorted samples = average of the 5th and 6th values, no rounding before comparison |
| Device | Existing `cricpro_api36` emulator (emulator-5554), API 36, arm64-v8a |
| Build | Ordinary minified non-debuggable R8 consumer APK `8103a1ed…760ef`, production commit `1504b2ea` |
| Correctness | Independent artifact verification mandatory for every sample; deep video/audio identity verification on the final sample of each resolution |

The two historical measurements (720p 3338.241877 ms, 1080p 3413.040460 ms) are context only and are not
qualifying samples.

## Frozen fixture

The exact GPT-6 qualified four-second mixed-content consumer workflow: project
`af806bee-fdd0-499f-beea-0cfa9ca2646b` — two 1280×720 30 fps video clips (1–3 s source windows) joined by a
1,482,433 µs cross-dissolve, one muted PiP overlay (scale 0.2), one "TOP" caption, and two audio clips
(music 0.1 volume, voiceover 0.5 volume, 48 kHz mono AAC mix). Output: 4.000000 s H.264/AAC MP4 at 30 fps,
120 video frames and 190 AAC frames, at 1280×720 or 1920×1080, Standard quality preset (5/10 Mbps video,
192 kbps audio). Full machine-readable detail, including source media SHA-256 hashes, is in
`EXPORT_FIXTURE_MANIFEST.json` in the campaign evidence directory. Nothing was simplified: PiP, caption,
transition and both audio tracks are retained.

## Frozen protocol

- Deterministic alternating sample order: 720p, 1080p, … fixed before collection.
- Every sample is process-cold: force-stop, cold start, open the project via Home search, drive Export.
- No warm-up export. No timing measured outside the approved boundaries (finalization recorded separately
  as informational).
- Technical failures (Transformer error, timeout, corruption, crash, missing output) are retained as
  failures; environmental exclusions require independent evidence; slow-but-valid exports are never removed.
- An interrupted or contaminated campaign is preserved as incomplete and replaced by a new complete
  campaign; nothing is pooled or hidden.

The immutable machine-readable specification is `EXPORT_BENCHMARK_CONTRACT.json` (SHA-256
`11128e0edd1ef33a5c42cb50cb5d190a2c9bd387a30364f52b3e9debbdd4a487`), hashed before the first qualifying
export, in the campaign evidence directory.
